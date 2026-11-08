# Load test (k6)

Target (requirements §9 no. 6, build guide Fase 5): **50.000 koneksi WebSocket + 3.000 pesan/s + feed 2.000 rps**
di staging, error < 0,1%, p95 kirim→terima < 500 ms (NFR-10), p95 feed < 300 ms (NFR-11).

`senyap.js` punya tiga skenario yang jalan bersamaan:

| Skenario | Isi | Metrik |
|---|---|---|
| `chat` | 1 VU = 1 pasangan (A, B) dengan Bisik aktif, dua WebSocket. A kirim `msg.send` berkala, B menunggu `msg.new` | `senyap_msg_e2e_ms`, `senyap_msg_ack_ms`, `senyap_msg_without_ack` |
| `idle` | koneksi diam, ping 25 dtk (seperti app di latar depan) | `senyap_ws_connect_failed` |
| `feed` | `GET /v1/feed/latest` dengan laju tetap | `http_req_duration{scenario:feed}` |

Jumlah koneksi = 2 × `PAIRS` + `IDLE`. Laju per pengirim = `MSG_RATE / PAIRS` (jaga di bawah rate limit akun
baru 10 pesan/menit; profil `full`: 25.000 pasangan × 0,12/s).

## Lokal (versi kecil)

```bash
loadtest/run-local.sh                       # PROFILE=small: 100 pasangan + 300 idle = 500 WS, 30 pesan/s, feed 100 rps, 1 menit
PAIRS=300 IDLE=1000 MSG_RATE=100 FEED_RPS=300 DURATION=2m loadtest/run-local.sh
```

Skrip menyalakan infra **terpisah** (container sekali pakai: Postgres 5532, Redis 6479, Redpanda 29092, MinIO 9100),
api 8380 / worker 8382 / realtime 8390, menjalankan k6 lewat image `grafana/k6`, lalu membersihkan semuanya.
Tidak menyentuh `infra/docker-compose.yml`. Hasil di `loadtest/results/` (di-`.gitignore`).
Angka laptop **bukan** bukti target produksi: semua proses, infra, dan k6 berbagi CPU yang sama.

## Staging (skala penuh)

1. Satu generator k6 tidak cukup untuk 50.000 WS. Pakai beberapa mesin (≥ 4 × 8 vCPU) atau k6 Cloud / k6-operator,
   naikkan batas file descriptor (`ulimit -n 200000`) dan rentang port sumber.
2. Staging harus memakai OTP mock **atau** pasangan di-seed lebih dulu (OTP nyata tidak bisa di-load test):
   ```bash
   k6 run -e MODE=seed -e PROFILE=full -e API=https://api.staging.senyap.app loadtest/senyap.js 2>&1 \
     | sed -n 's/.*PAIR \(.*\)" source=console/\1/p' | sed 's/\\"/"/g' | jq -s . > pairs.json
   k6 run -e PROFILE=full -e PAIRS_FILE=pairs.json -e API=https://api.staging.senyap.app \
     -e WS=wss://rt.staging.senyap.app/v1/ws loadtest/senyap.js
   ```
   Access token berumur pendek: seed tepat sebelum test, atau pecah test jadi beberapa putaran.
3. Pantau dashboard Grafana (`infra/observability/grafana/dashboards/senyap.json`): koneksi per node, latensi,
   lag consumer, outbox pending. Catat hasil di `PROGRESS.md`.

## Hasil

Lihat entri Fase 5 di `PROGRESS.md` (hasil laptop) — target penuh **belum** diuji (belum ada staging).
