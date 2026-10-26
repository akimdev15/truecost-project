#!/usr/bin/env bash
# One validation run from a clean restart. Tears the compose stack down with its volumes, proves the
# state really is empty, brings the infrastructure back, seeds it from the CSVs, starts the API and
# the prefetcher from the built jars, runs scripts/validate.sh, and stops both processes.
#
# The prepared OSRM graph is a bind mount, not a volume, so it survives the teardown on purpose.
# The clipped extract and the prepared graph are both checksummed in the run's environment.txt.
# The prefetcher's Kafka Streams state lives outside every volume, in the OS temp directory, so it
# is deleted here explicitly. Before that was added, counts carried from one run into the next, I-16.
#
# Usage, from the project root, after ./mvnw -B verify has built both jars:
#   scripts/clean-run.sh [run-label]
set -uo pipefail

cd "$(dirname "$0")/.."

LABEL="${1:-run}"
OUT="docs/evidence/hs3/${LABEL}-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
COMPOSE="docker compose -f deploy/compose.yaml"
API_JAR="target/truecost-0.0.1-SNAPSHOT.jar"
PREFETCH_JAR="target/truecost-0.0.1-SNAPSHOT-prefetcher.jar"
TIMINGS="$OUT/timings.txt"
: > "$TIMINGS"

phase_start=$(date +%s)
phase() {
    local now
    now=$(date +%s)
    printf "%-28s %4d s\n" "$1" $((now - phase_start)) | tee -a "$TIMINGS"
    phase_start=$now
}

wait_for() {
    local what="$1" limit="$2"
    shift 2
    for _ in $(seq 1 "$limit"); do
        "$@" >/dev/null 2>&1 && return 0
        sleep 2
    done
    echo "timed out waiting for $what" | tee -a "$OUT/clean-state.txt"
    return 1
}

[[ -f "$API_JAR" && -f "$PREFETCH_JAR" ]] || { echo "build the jars first, ./mvnw -B verify"; exit 1; }

# A prefetcher still shutting down writes its checkpoint into the state directory, so deleting the
# directory before it exits logs ERRORs into the previous run's log. Wait for both jars to exit.
stop_jars() {
    pkill -f "$API_JAR" 2>/dev/null
    pkill -f "$PREFETCH_JAR" 2>/dev/null
    for _ in $(seq 1 30); do
        pgrep -f "$API_JAR|$PREFETCH_JAR" >/dev/null || return 0
        sleep 1
    done
    pkill -9 -f "$API_JAR|$PREFETCH_JAR" 2>/dev/null
}
stop_jars
STREAMS_STATE="${TRUECOST_STREAM_STATE_DIR:-./var/kafka-streams}/truecost-prefetcher"
LEGACY_STREAMS_STATE="${TMPDIR:-/tmp}/kafka-streams/truecost-prefetcher"

echo "== teardown with volumes =="
rm -rf "$STREAMS_STATE" "$LEGACY_STREAMS_STATE"
$COMPOSE --profile app down -v --remove-orphans > "$OUT/teardown.log" 2>&1
phase "teardown"

echo "== infrastructure up =="
$COMPOSE up -d > "$OUT/up.log" 2>&1
wait_for postgres 60 bash -c "docker inspect --format '{{.State.Health.Status}}' truecost-postgres | grep -q healthy"
wait_for kafka 90 bash -c "docker inspect --format '{{.State.Health.Status}}' truecost-kafka | grep -q healthy"
wait_for osrm 60 bash -c "docker inspect --format '{{.State.Health.Status}}' truecost-osrm | grep -q healthy"
wait_for redis 60 bash -c "docker inspect --format '{{.State.Health.Status}}' truecost-redis | grep -q healthy"
wait_for apicurio 90 curl -sf localhost:8081/apis/registry/v3/system/info
phase "infrastructure healthy"

{
    echo "state immediately after the volume teardown, before anything is seeded"
    echo "postgres tables      $(docker exec truecost-postgres psql -U truecost -d truecost -tA -c "select count(*) from information_schema.tables where table_schema='public'")"
    echo "redis keys           $(docker exec truecost-redis redis-cli dbsize)"
    echo "kafka topics         $(docker exec truecost-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null | grep -v '^__' | grep -c .)"
    echo "apicurio artifacts   $(curl -s 'localhost:8081/apis/registry/v3/search/artifacts' | jq '.count')"
    echo "streams state dir    $( [[ -e "$STREAMS_STATE" ]] && echo present || echo absent )"
} | tee "$OUT/clean-state.txt"

echo "== seed =="
SPRING_PROFILES_ACTIVE=seed java -jar "$API_JAR" > "$OUT/seed.log" 2>&1
grep -o 'seed loaded.*' "$OUT/seed.log" | tee -a "$OUT/clean-state.txt"
phase "migrate and seed"

{
    echo "prefetcher  SPRING_PROFILES_ACTIVE=dev  truecost.events.enabled=true  server.port=8082"
    echo "api         SPRING_PROFILES_ACTIVE=dev  TRUECOST_EVENTS_ENABLED=true  server.port=8080"
    echo "dev profile turns trace sampling to 1.0, provider credentials are not set for either"
} > "$OUT/process-settings.txt"

echo "== start prefetcher and API =="
SPRING_PROFILES_ACTIVE=dev nohup java -Dtruecost.events.enabled=true -Dserver.port=8082 \
    -jar "$PREFETCH_JAR" > "$OUT/prefetcher.log" 2>&1 &
wait_for prefetcher 90 bash -c "curl -s localhost:8082/actuator/health | jq -e '.status==\"UP\"'"
SPRING_PROFILES_ACTIVE=dev TRUECOST_EVENTS_ENABLED=true nohup java -jar "$API_JAR" > "$OUT/api.log" 2>&1 &
wait_for api 90 bash -c "curl -s localhost:8080/actuator/health | jq -e '.status==\"UP\"'"
phase "prefetcher and API healthy"

echo "== validate =="
scripts/validate.sh "$OUT"
rc=$?
phase "validation"

if [[ "${KEEP_RUNNING:-false}" != "true" ]]; then
    stop_jars
fi
echo "clean run finished with $rc failing checks, evidence in $OUT"
exit "$rc"
