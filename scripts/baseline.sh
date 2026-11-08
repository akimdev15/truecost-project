#!/usr/bin/env bash
# Latency and cache baseline, issue #8. Runs the cold, warm, and skewed k6 profiles in that order
# against a stack started fresh by `KEEP_RUNNING=true scripts/clean-run.sh`, reads the cache
# counters from Prometheus before and after each profile so every hit rate covers that profile
# alone, then runs the cold start test from midpoint adjustment A4. Every provider is on its
# documented fallback, so these are floors for SC4 to SC6, not results with live providers.
#
# Usage, from the project root: scripts/baseline.sh <output-dir>
set -uo pipefail

OUT="${1:?pass an output directory}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
cd "$(dirname "$0")/.."

API="http://localhost:8080"
API_JAR="target/truecost-0.0.1-SNAPSHOT.jar"

{
    echo "captured   $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "commit     $(git rev-parse --short HEAD), product $( [[ -n "$(git status --porcelain -- src pom.xml)" ]] && echo modified || echo unchanged )"
    echo "api jar    $(shasum -a 256 "$API_JAR" | cut -c1-16)"
    echo "k6 script  $(shasum -a 256 eval/k6/baseline.js | cut -c1-16)"
    echo "script     $(shasum -a 256 "$0" | cut -c1-16)"
    echo "k6         $(k6 version 2>/dev/null | head -1)"
    echo "machine    $(sysctl -n machdep.cpu.brand_string 2>/dev/null), $(( $(sysctl -n hw.memsize) / 1073741824 )) GB, disk free $(df -h . | tail -1 | awk '{print $4}')"
    echo "providers  rental synthetic, fuel fallback, hotel synthetic, no credentials set"
} > "$OUT/metadata.txt"

cache_counts() {
    curl -s "$API/actuator/prometheus" | grep '^truecost_cache_request_total' | sed 's/^truecost_cache_request_total//'
}

hit_rates() {
    python3 - "$1" "$2" <<'PY'
import re, sys
def parse(path):
    out = {}
    for line in open(path):
        m = re.match(r'\{(.*)\}\s+([0-9.eE+]+)', line.strip())
        if not m:
            continue
        tags = dict(re.findall(r'(\w+)="([^"]*)"', m.group(1)))
        out[(tags.get("class"), tags.get("outcome"))] = float(m.group(2))
    return out
before, after = parse(sys.argv[1]), parse(sys.argv[2])
delta = {k: after.get(k, 0) - before.get(k, 0) for k in set(before) | set(after)}
classes = sorted({k[0] for k in delta})
total_hits = total = 0
for c in classes:
    hits = sum(v for (cc, o), v in delta.items() if cc == c and o in ("l1_hit", "l2_hit", "stale"))
    n = sum(v for (cc, o), v in delta.items() if cc == c)
    total_hits += hits
    total += n
    if n:
        print(f"  {c:10} requests {int(n):6}  hit rate {100 * hits / n:5.1f} percent")
if total:
    print(f"  {'all':10} requests {int(total):6}  hit rate {100 * total_hits / total:5.1f} percent, SC6 target above 85")
PY
}

for profile in cold warm skewed; do
    echo "== $profile =="
    cache_counts > "$OUT/$profile-cache-before.txt"
    PROFILE=$profile k6 run --quiet --summary-export "$OUT/$profile-summary.json" eval/k6/baseline.js \
        > "$OUT/$profile-k6.txt" 2>&1
    echo "k6 exit $?" >> "$OUT/$profile-k6.txt"
    cache_counts > "$OUT/$profile-cache-after.txt"
    {
        echo "$profile"
        jq -r '.metrics.http_req_duration | "  latency    p50 \(.med|floor) ms, p95 \(.["p(95)"]|floor) ms, p99 \(.["p(99)"]|floor) ms, max \(.max|floor) ms"' "$OUT/$profile-summary.json"
        jq -r '"  requests   \(.metrics.http_reqs.count), failed rate \(.metrics.http_req_failed.value)"' "$OUT/$profile-summary.json"
        hit_rates "$OUT/$profile-cache-before.txt" "$OUT/$profile-cache-after.txt"
        tail -1 "$OUT/$profile-k6.txt" | sed 's/^/  /'
    } >> "$OUT/summary.txt"
done

# Cold start, A4. Hard Stop 3 saw first requests of 759 to 1,088 ms and suspected the 2.4 GB map
# hash the harness ran just before them. Each trial restarts the API, waits for health, and times
# one request for a trip no earlier trial used, with the hash running alongside in half the trials.
stop_api() {
    pkill -f "java -jar $API_JAR" 2>/dev/null
    for _ in $(seq 1 30); do pgrep -f "java -jar $API_JAR" >/dev/null || return 0; sleep 1; done
    pkill -9 -f "java -jar $API_JAR" 2>/dev/null
}
echo "cold start, first request after an API restart" >> "$OUT/summary.txt"
for trial in 1 2 3 4 5 6; do
    stop_api
    SPRING_PROFILES_ACTIVE=dev TRUECOST_EVENTS_ENABLED=true nohup java -jar "$API_JAR" > "$OUT/coldstart-api-$trial.log" 2>&1 &
    for _ in $(seq 1 90); do curl -s "$API/actuator/health" | jq -e '.status=="UP"' >/dev/null 2>&1 && break; sleep 1; done
    hash_pid=""
    label="no hash"
    if (( trial % 2 == 0 )); then
        ( cat data/osrm/nyc-metro.osrm.* | shasum -a 256 > /dev/null ) &
        hash_pid=$!
        label="map hash running"
        sleep 0.5
    fi
    body=$(jq -n --argjson day "$trial" '{originLat:40.7505, originLng:-73.9934, pickupLocationCode:"EWR",
        destLat:(40.3 + $day * 0.07), destLng:-74.6, destinationCode:"COLD",
        departureAt:("2027-03-0" + ($day|tostring) + "T23:00:00Z"), returnAt:("2027-03-1" + ($day|tostring) + "T18:00:00Z"),
        hasPersonalEzpass:false, carClass:"MIDSIZE"}')
    t=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' -X POST "$API/api/v1/trips/plan" -H 'Content-Type: application/json' -d "$body")
    [[ -n "$hash_pid" ]] && wait "$hash_pid"
    echo "  trial $trial  $label  http $(cut -d' ' -f1 <<< "$t")  $(awk '{printf "%.0f ms", $2 * 1000}' <<< "$t")" >> "$OUT/summary.txt"
done
echo "baseline written to $OUT"
cat "$OUT/summary.txt"
