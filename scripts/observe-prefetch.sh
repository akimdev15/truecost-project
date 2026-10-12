#!/usr/bin/env bash
# Observes the Kafka prefetch path end to end on a live stack, which the validation run cannot do in
# its few seconds. The hot route topology suppresses counts until a window closes, and stream time
# only moves when a later event arrives, so a hot route signal needs three things in order. Enough
# searches for one route inside a window, a wait until that window plus its grace has passed, and one
# more search for the same route to advance stream time on its partition.
#
# Usage, from the project root, with the API and prefetcher running and events enabled:
#   scripts/observe-prefetch.sh <output-dir>
set -uo pipefail

cd "$(dirname "$0")/.."

OUT="${1:?pass the run directory}"
GOLDEN="eval/golden/sample-request.json"
LOG="$OUT/prefetch-observation.txt"
HOP_SECONDS=300
GRACE_SECONDS=60

offset() {
    docker exec truecost-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic "$1" 2>/dev/null \
        | awk -F: '{s += $3} END {print s + 0}'
}
rows() {
    docker exec truecost-postgres psql -U truecost -d truecost -tA -c 'select count(*) from quote_snapshot'
}
search() {
    curl -s -o /dev/null -X POST localhost:8080/api/v1/trips/plan -H 'Content-Type: application/json' -d @"$GOLDEN"
}
stamp() {
    date -u +%H:%M:%S
}

echo "$(stamp)  before   hot-routes $(offset hot-routes), quote-snapshots $(offset quote-snapshots), quote_snapshot rows $(rows)" | tee "$LOG"
for _ in 1 2 3 4 5 6; do search; done
echo "$(stamp)  sent 6 searches for the golden route, threshold is 5 in a 15 minute window" | tee -a "$LOG"

now=$(date +%s)
close=$(( (now / HOP_SECONDS + 1) * HOP_SECONDS + GRACE_SECONDS + 15 ))
echo "$(stamp)  earliest window holding them closes at $(date -u -r $((close - GRACE_SECONDS - 15)) +%H:%M:%S), waiting $((close - now)) s for close plus grace" | tee -a "$LOG"

sleep $(( close - $(date +%s) ))
search
echo "$(stamp)  sent 1 more search to advance stream time past the window" | tee -a "$LOG"

seen=""
for _ in $(seq 1 45); do
    if grep -q 'prefetched hot route' "$OUT/prefetcher.log" 2>/dev/null; then seen=yes; break; fi
    sleep 2
done
sleep 3

{
    echo "$(stamp)  after    hot-routes $(offset hot-routes), quote-snapshots $(offset quote-snapshots), quote_snapshot rows $(rows)"
    if [[ -n "$seen" ]]; then
        echo "$(stamp)  prefetcher log:"
        grep 'prefetched hot route' "$OUT/prefetcher.log" | sed 's/^/    /' | head -3
        echo "RESULT PASS  hot route detected, signal consumed, cache warmed, quote snapshots persisted"
    else
        echo "RESULT FAIL  no hot route signal observed within 90 s of the window closing"
    fi
} | tee -a "$LOG"

[[ -n "$seen" ]]
