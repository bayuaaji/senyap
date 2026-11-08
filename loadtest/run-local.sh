#!/usr/bin/env bash
# Load test versi kecil di laptop, terisolasi dari docker compose di infra/ (yang mungkin sedang dipakai app):
# container sekali pakai di port lain (Postgres 5532, Redis 6479, Redpanda 29092, MinIO 9100), lalu api :8380,
# worker :8382, realtime :8390, lalu k6 (image grafana/k6). Semua container + proses dihentikan di akhir.
#
#   loadtest/run-local.sh                 # PROFILE=small
#   PAIRS=200 MSG_RATE=60 loadtest/run-local.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$ROOT/loadtest/results}"
mkdir -p "$OUT"
P=senyap-lt
cleanup() {
  for pid in ${PIDS:-}; do kill "$pid" 2>/dev/null || true; done
  wait 2>/dev/null || true
  docker rm -f $P-pg $P-redis $P-kafka $P-minio >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "== infra terisolasi"
docker run -d --rm --name $P-pg -e POSTGRES_DB=senyap -e POSTGRES_USER=senyap_admin -e POSTGRES_PASSWORD=senyap_admin \
  -p 5532:5432 postgres:16-alpine -c max_connections=400 >/dev/null
docker run -d --rm --name $P-redis -p 6479:6379 redis:7-alpine >/dev/null
docker run -d --rm --name $P-kafka -p 29092:29092 redpandadata/redpanda:v24.3.18 redpanda start --mode=dev-container \
  --smp=1 --memory=1G --overprovisioned --kafka-addr=internal://0.0.0.0:9092,external://0.0.0.0:29092 \
  --advertise-kafka-addr=internal://localhost:9092,external://localhost:29092 >/dev/null
docker run -d --rm --name $P-minio -p 9100:9000 -e MINIO_ROOT_USER=senyap -e MINIO_ROOT_PASSWORD=senyap-minio \
  cgr.dev/chainguard/minio:latest server /data >/dev/null
until docker exec $P-pg pg_isready -U senyap_admin -d senyap >/dev/null 2>&1; do sleep 1; done
until docker exec $P-kafka rpk cluster health 2>/dev/null | grep -q 'Healthy:.*true'; do sleep 1; done

echo "== build jar"
(cd "$ROOT/backend" && ./mvnw -q -o -DskipTests package)
API_JAR=$(ls "$ROOT"/backend/api/target/api-*.jar | grep -v -e plain -e original | head -1)
RT_JAR=$(ls "$ROOT"/backend/realtime/target/realtime-*.jar | grep -v -e plain -e original | head -1)

export SENYAP_DB_URL=jdbc:postgresql://localhost:5532/senyap SENYAP_DB_BOOTSTRAP_URL=jdbc:postgresql://localhost:5532/senyap
export SENYAP_REDIS_PORT=6479 SPRING_DATA_REDIS_PORT=6479 SENYAP_KAFKA_BOOTSTRAP=localhost:29092
export SENYAP_S3_ENDPOINT=http://localhost:9100 SENYAP_MEDIA_PUBLIC_URL=http://localhost:9100/media
export SENYAP_JWKS_URI=http://localhost:8380/.well-known/jwks.json
# Seed ratusan akun dari satu IP: longgarkan rate limit OTP per IP (hanya untuk load test lokal).
ARGS="--senyap.auth.otp.per-ip-per-hour=100000 --senyap.auth.otp.verify-per-ip-per-hour=100000 --senyap.media.gallery-per-category=5"

echo "== api :8380, worker :8382, realtime :8390"
SENYAP_API_PORT=8380 java -jar "$API_JAR" --spring.profiles.active=local,api $ARGS >"$OUT/api.log" 2>&1 & PIDS="$!"
SENYAP_API_PORT=8382 java -jar "$API_JAR" --spring.profiles.active=local,worker $ARGS >"$OUT/worker.log" 2>&1 & PIDS="$PIDS $!"
SENYAP_RT_PORT=8390 SENYAP_API_URL=http://localhost:8380 java -jar "$RT_JAR" >"$OUT/realtime.log" 2>&1 & PIDS="$PIDS $!"
for port in 8380 8382 8390; do
  until curl -sf "localhost:$port/actuator/health/readiness" >/dev/null; do sleep 2; done
done
# Galeri latar diisi worker secara asinkron.
until [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:8380/v1/backgrounds?category=curhat)" = 401 ]; do sleep 1; done
sleep 5

echo "== k6"
docker run --rm --add-host=host.docker.internal:host-gateway -v "$ROOT/loadtest:/scripts" grafana/k6:1.4.2 run \
  -e API=http://host.docker.internal:8380 -e WS=ws://host.docker.internal:8390/v1/ws \
  -e PROFILE="${PROFILE:-small}" ${PAIRS:+-e PAIRS=$PAIRS} ${IDLE:+-e IDLE=$IDLE} ${MSG_RATE:+-e MSG_RATE=$MSG_RATE} \
  ${FEED_RPS:+-e FEED_RPS=$FEED_RPS} ${DURATION:+-e DURATION=$DURATION} -e SUMMARY_OUT=/scripts/results/summary.json \
  /scripts/senyap.js | tee "$OUT/k6.txt"

echo "== metrik server (Prometheus)"
curl -s localhost:8390/actuator/prometheus | grep -E '^senyap_rt_(connections|send_latency_seconds_(count|sum))' | tee "$OUT/metrics.txt"
curl -s localhost:8382/actuator/prometheus | grep -E '^senyap_(chat_delivery_lag_seconds_(count|sum)|outbox_pending)' | tee -a "$OUT/metrics.txt"
