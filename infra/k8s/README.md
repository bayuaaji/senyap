# Infra produksi (Kubernetes + Kustomize)

**Kenapa Kustomize, bukan Helm:** hanya tiga workload dengan konfigurasi yang hampir sama antar lingkungan.
Kustomize sudah ada di `kubectl` (`kubectl apply -k`), tanpa bahasa template; beda staging/produksi cukup
patch kecil di `overlays/`. Pindah ke Helm bila nanti manifest dipakai banyak tim/lingkungan dengan banyak parameter.

```text
base/                      # sama untuk semua lingkungan
  api.yaml                 # Deployment + Service + HPA + PDB (profil Spring `api`)
  worker.yaml              # Deployment + PDB (profil `worker`: relay outbox, consumer, job)
  realtime.yaml            # gateway WebSocket: preStop + drain, Service, HPA, PDB
  ingress.yaml             # ingress-nginx: api + WebSocket (timeout 3600 dtk) + konsol (internal)
  config.yaml              # ConfigMap env non-rahasia
  db-bootstrap.yaml        # Job: role + schema per modul (infra/k8s/base/bootstrap.sql)
overlays/staging, overlays/production   # replika, host, image tag, rahasia
```

## Layanan terkelola (di luar cluster)

| Kebutuhan | Pilihan (contoh) | Yang dipasang di env |
|---|---|---|
| Postgres 16 + **PITR** | AWS RDS / Cloud SQL / Neon / Supabase: aktifkan *automated backups* + *point-in-time recovery* (retensi ≥ 7 hari), Multi-AZ | `SENYAP_DB_URL` (`jdbc:postgresql://…:5432/senyap?sslmode=require`), `SENYAP_DB_<MODUL>_PASSWORD` |
| Redis 7 **Cluster** | ElastiCache (cluster mode) / Memorystore / Upstash | `SPRING_DATA_REDIS_CLUSTER_NODES`, `SPRING_DATA_REDIS_SSL_ENABLED=true`, `SPRING_DATA_REDIS_PASSWORD` |
| Kafka terkelola | Confluent Cloud / MSK / Redpanda Cloud. Topik `<modul>.events`, RF 3, `min.insync.replicas=2` | `SENYAP_KAFKA_BOOTSTRAP`, `SPRING_KAFKA_SECURITY_PROTOCOL=SASL_SSL`, `SPRING_KAFKA_PROPERTIES_SASL_MECHANISM`, `SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG` |
| Object storage + CDN | Cloudflare R2 + CDN (atau S3 + CloudFront). Bucket privat; hanya `public/*` lewat CDN | `SENYAP_S3_ENDPOINT`, `SENYAP_S3_REGION`, `SENYAP_S3_BUCKET`, `SENYAP_S3_ACCESS_KEY`, `SENYAP_S3_SECRET_KEY`, `SENYAP_MEDIA_PUBLIC_URL` (URL CDN) |

Kode sudah siap Redis Cluster: kunci yang di-`RENAME` memakai hash tag (`{feed:hot}`, `{posts:heart_delta}`).
Pub/Sub node channel gateway jalan di cluster (broadcast). Untuk volume besar pertimbangkan sharded pub/sub (Redis 7).

## Rahasia

Tidak ada kredensial di repo. `overlays/*/secrets.env` (di-`.gitignore`) dibaca `secretGenerator`; contoh nama
kunci di `secrets.example.env`. Di produksi sebaiknya External Secrets Operator / Sealed Secrets dari KMS.
`SENYAP_AUTH_PHONE_PEPPER` **tidak boleh berubah** setelah ada data.

## Urutan deploy pertama

```bash
# 1. Role + schema DB (sekali; idempotent, juga untuk rotasi password)
kubectl apply -k infra/k8s/overlays/staging          # membuat namespace, secret, config, Job db-bootstrap
kubectl -n senyap wait --for=condition=complete job/db-bootstrap --timeout=120s
# 2. Workload (Flyway tiap modul berjalan saat api/worker start, dengan role modul itu)
kubectl -n senyap rollout status deploy/senyap-api deploy/senyap-worker deploy/senyap-realtime
```

Cek manifest tanpa cluster: `kubectl kustomize infra/k8s/overlays/staging`.

## Gateway WebSocket

- Load balancer harus mendukung WebSocket dengan **idle timeout ≥ 120 dtk** (client ping tiap 25 dtk; gateway
  menutup sesi diam 75 dtk). ingress-nginx: `proxy-read-timeout`/`proxy-send-timeout` 3600 (di `ingress.yaml`).
  AWS ALB: `alb.ingress.kubernetes.io/load-balancer-attributes: idle_timeout.timeout_seconds=3600`.
  GKE: `BackendConfig.timeoutSec: 3600`.
- **Drain saat deploy**: `preStop` tidur 10 dtk (LB berhenti mengirim koneksi baru), lalu SIGTERM → Spring menandai
  readiness `REFUSING_TRAFFIC`, `RealtimeNode.stop()` menutup sesi dengan **1012** disebar 10 dtk
  (`SENYAP_RT_DRAIN_SPREAD`), menunggu ≤ 15 dtk, membersihkan presence. Client menyambung ulang dalam 0,2–3 dtk ke
  pod lain dan sinkron `last_seq`. `terminationGracePeriodSeconds: 60` > 10 + 10 + 15 + sisa. `maxUnavailable: 0`,
  `maxSurge: 25%` supaya kapasitas tidak turun saat rollout.

## Observability

Pod diberi anotasi `prometheus.io/scrape` pada port management `9464` (tidak lewat ingress). Trace OTLP ke
collector: `SENYAP_OTLP_ENABLED=true`, `SENYAP_OTLP_TRACES_ENDPOINT=http://otel-collector.observability:4318/v1/traces`.
Dashboard: `infra/observability/grafana/dashboards/senyap.json`.
