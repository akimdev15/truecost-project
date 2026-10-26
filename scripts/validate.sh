#!/usr/bin/env bash
# Validates a running TrueCost stack and writes the evidence to a run directory. Every check has an
# identifier so the validation memo and the issue log can cite it. A check either passes, fails,
# or reproduces a known issue, and a known issue is reported as KNOWN rather than hidden as a pass
# or counted as a regression. The script never prints secret values, only whether a variable is set.
#
# Usage, from the project root, with the infra stack up, the API on 8080, and the prefetcher on 8082:
#   scripts/validate.sh [output-dir]
# Exit status is the number of FAIL results, so 0 means every non known check passed.
set -uo pipefail

cd "$(dirname "$0")/.."

OUT="${1:-docs/evidence/hs3/run-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
API="http://localhost:8080"
PLAN="$API/api/v1/trips/plan"
GOLDEN="eval/golden/sample-request.json"
CHECKS="$OUT/checks.txt"
: > "$CHECKS"
FAILS=0

record() {
    local id="$1" status="$2" detail="$3"
    printf "%-5s %-6s %s\n" "$id" "$status" "$detail" | tee -a "$CHECKS"
    [[ "$status" == "FAIL" ]] && FAILS=$((FAILS + 1))
    return 0
}

check() {
    local id="$1" detail="$2"
    shift 2
    if "$@" >/dev/null 2>&1; then record "$id" PASS "$detail"; else record "$id" FAIL "$detail"; fi
}

psql_q() {
    docker exec truecost-postgres psql -U truecost -d truecost -tA -c "$1" 2>/dev/null
}

post() {
    curl -s -X POST "$PLAN" -H 'Content-Type: application/json' -d "$1"
}

sha() {
    shasum -a 256 | cut -c1-16
}

# ---------------------------------------------------------------------------------------------
# Environment snapshot. Names the exact artifacts every result below was produced by.
{
    echo "captured   $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "os         $(sw_vers -productName 2>/dev/null) $(sw_vers -productVersion 2>/dev/null) $(uname -m)"
    echo "cpu        $(sysctl -n hw.ncpu 2>/dev/null) cores"
    echo "memory     $(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 )) GB host"
    echo "disk free  $(df -h . | tail -1 | awk '{print $4 " of " $2 ", " $5 " used"}')"
    echo "java       $(java -version 2>&1 | head -1)"
    echo "maven      $(./mvnw -v 2>/dev/null | head -1)"
    echo "docker     $(docker version --format '{{.Server.Version}}' 2>/dev/null), $(( $(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0) / 1073741824 )) GB allocated"
    echo "git        $(git rev-parse --short HEAD) $(git describe --tags --always 2>/dev/null)"
    echo "  product  $( [[ -n "$(git status --porcelain -- src pom.xml deploy)" ]] && echo modified || echo unchanged ), src, pom.xml, deploy"
    echo "  harness  $( [[ -n "$(git status --porcelain -- scripts)" ]] && echo uncommitted || echo committed ), scripts"
    echo
    echo "artifacts"
    for jar in target/truecost-0.0.1-SNAPSHOT.jar target/truecost-0.0.1-SNAPSHOT-prefetcher.jar; do
        [[ -f "$jar" ]] && echo "  $(shasum -a 256 "$jar" | cut -c1-16)  $jar"
    done
    echo
    echo "container images, by digest"
    for c in $(docker ps --format '{{.Names}}' | sort); do
        echo "  $c  $(docker inspect --format '{{.Config.Image}}' "$c")  $(docker inspect --format '{{.Image}}' "$c" | cut -c8-23)"
    done
    echo
    echo "data provenance"
    echo "  $(shasum -a 256 data/osrm/nyc-metro.osm.pbf | cut -c1-16)  data/osrm/nyc-metro.osm.pbf, the clipped source extract"
    echo "  $(cat data/osrm/nyc-metro.osrm.* | shasum -a 256 | cut -c1-16)  data/osrm/nyc-metro.osrm.*, the prepared graph, all files in name order"
    for f in data/seeds/*.csv; do echo "  $(shasum -a 256 "$f" | cut -c1-16)  $f"; done
    echo
    echo "environment variables in the validation shell, names only. The jars' own settings are in"
    echo "process-settings.txt when the run came from scripts/clean-run.sh"
    for v in EIA_API_KEY AMADEUS_CLIENT_ID AMADEUS_CLIENT_SECRET APICURIO_REGISTRY_URL TRUECOST_OTLP_ENDPOINT TRUECOST_EVENTS_ENABLED SPRING_PROFILES_ACTIVE; do
        if [[ -n "${!v:-}" ]]; then echo "  $v set"; else echo "  $v unset"; fi
    done
} > "$OUT/environment.txt"

echo "== toolchain =="
check T1 "JDK major version is 21" bash -c 'java -version 2>&1 | head -1 | grep -q "\"21\."'
check T2 "Docker daemon reachable with at least 7 GB allocated" \
    bash -c '[[ $(docker info --format "{{.MemTotal}}") -ge 7000000000 ]]'
running=$(docker ps --format '{{.Names}}' | grep -c '^truecost-')
check T3 "all 8 infrastructure containers running, found $running" test "$running" -ge 8
unhealthy=$(docker ps --filter health=unhealthy --format '{{.Names}}' | tr '\n' ' ')
check T4 "no container reports unhealthy ${unhealthy:-none}" test -z "$unhealthy"
closed=""
for port in 5432 6379 9092 8081 5001 9090 3000 3200 8080 8082; do
    nc -z localhost "$port" 2>/dev/null || closed="$closed $port"
done
check T5 "all 10 service ports accepting connections, closed:${closed:- none}" test -z "$closed"
check T6 "seed CSVs readable and OSRM graph files present" \
    bash -c 'for f in data/seeds/*.csv; do [[ -r "$f" ]] || exit 1; done; ls data/osrm/nyc-metro.osrm.ebg data/osrm/nyc-metro.osrm.cell_metrics'
check T7 "API health UP with Postgres and Redis UP" \
    bash -c "curl -s $API/actuator/health | jq -e '.status==\"UP\" and .components.db.status==\"UP\" and .components.redis.status==\"UP\"'"
check T8 "prefetcher health UP" bash -c "curl -s localhost:8082/actuator/health | jq -e '.status==\"UP\"'"
free_pct=$(df . | tail -1 | awk '{print 100 - $5}')
if [[ "$free_pct" -lt 5 ]]; then
    record T9 KNOWN "host disk ${free_pct}% free, little headroom for re-preparing the map, I-13"
else
    record T9 PASS "host disk ${free_pct}% free"
fi

echo "== data =="
check D1 "Flyway applied versions 1 and 2, both successful" \
    test "$(psql_q "select string_agg(version||':'||success, ',' order by installed_rank) from flyway_schema_history")" = "1:true,2:true"
counts=$(psql_q "select 'crossings='||(select count(*) from toll_crossing)||' rates='||(select count(*) from toll_rate)||' programs='||(select count(*) from toll_program)||' companies='||(select count(*) from rental_company)||' classes='||(select count(*) from vehicle_class)||' schedules='||(select count(*) from congestion_schedule)||' credits='||(select count(*) from congestion_credit)")
echo "$counts" > "$OUT/row-counts.txt"
csv_rows() { echo $(( $(grep -cv '^\s*$' "$1") - 1 )); }
check D2 "toll_crossing rows equal crossings.csv data rows, $(csv_rows data/seeds/crossings.csv)" \
    test "$(psql_q 'select count(*) from toll_crossing')" = "$(csv_rows data/seeds/crossings.csv)"
check D3 "toll_rate rows equal toll_rates.csv data rows, $(csv_rows data/seeds/toll_rates.csv)" \
    test "$(psql_q 'select count(*) from toll_rate')" = "$(csv_rows data/seeds/toll_rates.csv)"
check D4 "vehicle_class rows equal vehicle_classes.csv data rows, $(csv_rows data/seeds/vehicle_classes.csv)" \
    test "$(psql_q 'select count(*) from vehicle_class')" = "$(csv_rows data/seeds/vehicle_classes.csv)"
check D5 "toll_program rows equal toll_programs.csv data rows, $(csv_rows data/seeds/toll_programs.csv)" \
    test "$(psql_q 'select count(*) from toll_program')" = "$(csv_rows data/seeds/toll_programs.csv)"
check D9 "congestion.csv rows equal congestion_schedule plus congestion_credit rows, $(csv_rows data/seeds/congestion.csv)" \
    test "$(psql_q 'select (select count(*) from congestion_schedule) + (select count(*) from congestion_credit)')" = "$(csv_rows data/seeds/congestion.csv)"
check D6 "no toll_rate row points at a missing crossing" \
    test "$(psql_q 'select count(*) from toll_rate r left join toll_crossing c on c.id = r.crossing_id where c.id is null')" = "0"
psql_q "select 'toll_rate latest '||max(effective_date)||', future dated '||count(*) filter (where effective_date > current_date) from toll_rate
        union all select 'toll_program latest '||max(effective_date)||', future dated '||count(*) filter (where effective_date > current_date) from toll_program
        union all select 'congestion_schedule latest '||max(effective_date)||', future dated '||count(*) filter (where effective_date > current_date) from congestion_schedule" \
    > "$OUT/effective-dates.txt"
record D7 INFO "effective dates recorded, see effective-dates.txt"
missing_src=""
for f in data/seeds/*.csv; do grep -q "$(basename "$f")" data/seeds/SOURCES.md || missing_src="$missing_src $(basename "$f")"; done
check D8 "every seed CSV is named in SOURCES.md, missing:${missing_src:- none}" test -z "$missing_src"

echo "== routing =="
curl -s 'localhost:5001/route/v1/driving/-73.99,40.75;-75.16,39.95?overview=false' > "$OUT/osrm-philadelphia.json"
check R1 "OSRM Manhattan to Philadelphia returns Ok in a plausible 6000 to 9000 s" \
    jq -e '.code=="Ok" and .routes[0].duration > 6000 and .routes[0].duration < 9000' "$OUT/osrm-philadelphia.json"
curl -s 'localhost:5001/route/v1/driving/-73.99,40.75;-71.0589,42.3601?overview=false' > "$OUT/osrm-boston.json"
snap_gap_km=$(jq '.waypoints[1].location as [$lng,$lat] | ((($lat - 42.3601) * 111) | fabs) | floor' "$OUT/osrm-boston.json")
if [[ "${snap_gap_km:-0}" -gt 20 ]]; then
    record R2 KNOWN "Boston destination snaps ${snap_gap_km} km short at the clipped map edge and still returns Ok, I-09"
else
    record R2 PASS "Boston destination snapped within ${snap_gap_km} km"
fi

echo "== api correctness =="
post "$(cat "$GOLDEN")" | jq -S . > "$OUT/golden-response.json"
R="$OUT/golden-response.json"
check A1 "golden request returns 6 ranked options" jq -e '(.options|length)==6' "$R"
check A2 "every option's true total equals the sum of its six cost components" \
    jq -e 'all(.options[]; .trueTotalCents == (.rentalCents + .tollProgramFeeCents + .tollTotalCents + .congestionCents + .fuelCents + .hotelCents))' "$R"
check A3 "every strategy candidate total equals fee plus tolls plus congestion" \
    jq -e 'all(.options[].strategy.candidates[]; .totalCents == (.feeCents + .tollCents + .congestionCents))' "$R"
check A4 "every chosen strategy is the cheapest of its candidates" \
    jq -e 'all(.options[]; .strategy.totalCents == ([.strategy.candidates[].totalCents] | min))' "$R"
check A5 "options are ranked ascending by true total" \
    jq -e '[.options[].trueTotalCents] as $t | $t == ($t | sort)' "$R"
check A6 "recommendation is the first option and savings equals the gap to the second" \
    jq -e '.recommendation.companyCode == .options[0].companyCode and .recommendation.trueTotalCents == .options[0].trueTotalCents and .recommendation.savingsOverNextCents == (.options[1].trueTotalCents - .options[0].trueTotalCents)' "$R"
check A7 "every money field is an integer" \
    jq -e '[.. | objects | to_entries[] | select(.key | endswith("Cents")) | .value] | all(. == floor)' "$R"
post "$(jq '.hasPersonalEzpass=false' "$GOLDEN")" | jq -S . > "$OUT/no-tag-response.json"
post "$(jq '.hasPersonalEzpass=true' "$GOLDEN")" | jq -S . > "$OUT/tag-response.json"
check A8 "PERSONAL_TAG is offered on every option only when the renter owns a tag" \
    bash -c "jq -e 'all(.options[]; any(.strategy.candidates[]; .strategy==\"PERSONAL_TAG\"))' '$OUT/tag-response.json' && jq -e 'all(.options[]; all(.strategy.candidates[]; .strategy!=\"PERSONAL_TAG\"))' '$OUT/no-tag-response.json'"
: > "$OUT/repeat-hashes.txt"
for i in 1 2 3 4 5; do post "$(cat "$GOLDEN")" | jq -S . | sha >> "$OUT/repeat-hashes.txt"; done
check A9 "five repeated golden requests return identical responses, compared as key sorted JSON" \
    test "$(sort -u "$OUT/repeat-hashes.txt" | wc -l | tr -d ' ')" = "1"
jq -S . "$R" | sha > "$OUT/golden-hash.txt"
bad_code=$(curl -s -o "$OUT/malformed-response.json" -w '%{http_code}' -X POST "$PLAN" -H 'Content-Type: application/json' -d '{}')
check A10 "a request with no fields is rejected with 400, got $bad_code" test "$bad_code" = "400"
check A11 "freshness block names route, tolls, fuel, and hotel" \
    jq -e '.freshness | has("route") and has("tolls") and has("fuel") and has("hotel")' "$R"
post "$(jq '.destLat=42.3601 | .destLng=-71.0589 | .destinationCode="BOS"' "$GOLDEN")" | jq -S . > "$OUT/boston-response.json"
if jq -e '(.options|length) > 0' "$OUT/boston-response.json" >/dev/null 2>&1; then
    record A12 KNOWN "a destination outside seeded toll coverage still returns a ranking instead of a rejection, I-01"
else
    record A12 PASS "a destination outside seeded toll coverage is rejected"
fi

OVERRIDES='[{"companyCode":"THRIFTY","companyName":"Thrifty","totalCents":14500},{"companyCode":"HERTZ","companyName":"Hertz","totalCents":12900},{"companyCode":"AVIS","companyName":"Avis","totalCents":15800}]'
post "$(jq --argjson o "$OVERRIDES" '.rentalRateOverrides=$o' "$GOLDEN")" | jq -S . > "$OUT/user-rates-response.json"
U="$OUT/user-rates-response.json"
check A13 "user supplied base rates are used as given, one option per quote, each marked USER_SUPPLIED" \
    jq -e --argjson o "$OVERRIDES" '($o | map({(.companyCode): .totalCents}) | add) as $m
        | (.options | length) == ($o | length)
        and all(.options[]; .provenance.rentalSource == "USER_SUPPLIED" and .rentalCents == $m[.companyCode])' "$U"
check A14 "the same arithmetic, strategy, and ranking rules hold on the user supplied response" \
    jq -e 'all(.options[]; .trueTotalCents == (.rentalCents + .tollProgramFeeCents + .tollTotalCents + .congestionCents + .fuelCents + .hotelCents))
        and all(.options[]; .strategy.totalCents == ([.strategy.candidates[].totalCents] | min))
        and ([.options[].trueTotalCents] as $t | $t == ($t | sort))
        and .recommendation.companyCode == .options[0].companyCode' "$U"
jq -r '"user supplied winner " + .recommendation.companyCode + " at " + (.recommendation.trueTotalCents|tostring) + " cents, ranking " + ([.options[] | .companyCode + " " + (.trueTotalCents|tostring)] | join(", "))' "$U" > "$OUT/user-rates-summary.txt"
if jq -e 'any(.options[]; .provenance.hotelSource == "SYNTHETIC") and .freshness.hotel == "FRESH"' "$R" >/dev/null 2>&1; then
    record A15 KNOWN "the hotel price is synthetic but freshness reports it FRESH, only provenance shows the fallback, I-18"
else
    record A15 PASS "a synthetic hotel price is not reported as FRESH"
fi

echo "== kafka and schema registry =="
topics=$(docker exec truecost-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null | tr '\n' ' ')
echo "$topics" > "$OUT/kafka-topics.txt"
check K1 "topics trip-searches, hot-routes, and quote-snapshots exist" \
    bash -c "echo '$topics' | grep -qw trip-searches && echo '$topics' | grep -qw hot-routes && echo '$topics' | grep -qw quote-snapshots"
offset() {
    docker exec truecost-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic "$1" 2>/dev/null \
        | awk -F: '{s += $3} END {print s + 0}'
}
before=$(offset trip-searches)
for i in 1 2 3; do post "$(cat "$GOLDEN")" >/dev/null; done
sleep 2
after=$(offset trip-searches)
check K2 "three plan requests publish three SearchRequested events, offset $before to $after" test $((after - before)) -eq 3
curl -s 'localhost:8081/apis/registry/v3/search/artifacts?limit=50' > "$OUT/apicurio-artifacts.json"
check K3 "Apicurio holds the SearchRequested Avro schema" \
    jq -e 'any(.artifacts[]; .artifactId | test("SearchRequested|trip-searches"))' "$OUT/apicurio-artifacts.json"
{
    echo "trip-searches   $(offset trip-searches)"
    echo "hot-routes      $(offset hot-routes)"
    echo "quote-snapshots $(offset quote-snapshots)"
    echo "quote_snapshot rows $(psql_q 'select count(*) from quote_snapshot')"
} > "$OUT/kafka-offsets.txt"
record K4 INFO "topic offsets and snapshot rows recorded, see kafka-offsets.txt"

echo "== observability =="
sleep 6
curl -s 'localhost:9090/api/v1/query?query=up' > "$OUT/prometheus-up.json"
check M1 "Prometheus scrapes both the API and the prefetcher as up" \
    jq -e '[.data.result[] | select(.metric.job=="truecost-api" or .metric.job=="truecost-prefetcher") | .value[1]] | length == 2 and all(. == "1")' "$OUT/prometheus-up.json"
check M2 "the toll strategy decision counter is exported" \
    bash -c "curl -s $API/actuator/prometheus | grep -q '^truecost_strategy_chosen_total'"
start=$(( $(date +%s) - 900 ))
q=$(printf '%s' '{resource.service.name="truecost" && name=~".*trips/plan.*"}' | jq -sRr @uri)
# Every plan trace carries the same span count while I-14 orphans the route lookups, so the cold
# request is identified by duration rather than by span count.
best=$(curl -s "localhost:3200/api/search?q=$q&start=$start&end=$(date +%s)&limit=200" | jq -r '.traces | max_by(.durationMs // 0) | .traceID // empty')
best_n=$( [[ -n "$best" ]] && curl -s "localhost:3200/api/traces/$best" | jq '[(.batches // .resourceSpans)[] | (.scopeSpans // .instrumentationLibrarySpans)[] | .spans[]] | length' )
if [[ -n "$best" ]]; then
    curl -s "localhost:3200/api/traces/$best" > "$OUT/trace.json"
    jq -r '[(.batches // .resourceSpans)[] as $b | ($b.resource.attributes[]? | select(.key=="service.name") | .value.stringValue) as $svc
            | ($b.scopeSpans // $b.instrumentationLibrarySpans)[] | .spans[]
            | {svc: $svc, name, start: (.startTimeUnixNano|tonumber), ms: (((.endTimeUnixNano|tonumber) - (.startTimeUnixNano|tonumber)) / 1000000)}]
           | sort_by(.start) | (.[0].start) as $t0
           | .[] | "\(((.start - $t0) / 1000000) | floor | tostring | (" " * (6 - length)) + .) ms  \(.ms | floor | tostring | (" " * (5 - length)) + .) ms  \(.svc)  \(.name)"' \
        "$OUT/trace.json" > "$OUT/trace-spans.txt"
    record O1 PASS "Tempo holds the slowest, cold plan request trace $best with $best_n spans, see trace-spans.txt"
else
    record O1 FAIL "no plan request trace found in Tempo for the last 15 minutes"
fi

osrm_q=$(printf '%s' '{name="osrm-route"}' | jq -sRr @uri)
osrm_t=$(curl -s "localhost:3200/api/search?q=$osrm_q&start=$start&end=$(date +%s)&limit=1" | jq -r '.traces[0].traceID // empty')
if [[ -z "$osrm_t" ]]; then
    record O2 INFO "no osrm-route span in the window, every route lookup was a cache hit"
elif curl -s "localhost:3200/api/traces/$osrm_t" | jq -e '[(.batches // .resourceSpans)[] | (.scopeSpans // .instrumentationLibrarySpans)[] | .spans[] | select(.name=="osrm-route") | (.parentSpanId // "")] | all(. == "")' >/dev/null; then
    record O2 KNOWN "osrm-route spans are separate root traces, the virtual thread fan-out drops trace context, I-14"
else
    record O2 PASS "osrm-route spans nest under the plan request"
fi

if [[ -f "$OUT/api.log" && -f "$OUT/prefetcher.log" ]]; then
    errors=$(grep -c ' ERROR ' "$OUT/api.log" "$OUT/prefetcher.log" | awk -F: '{s += $2} END {print s + 0}')
    check L1 "API and prefetcher logs contain no ERROR line, found $errors" test "$errors" -eq 0
else
    record L1 INFO "no process logs in this run directory, run through scripts/clean-run.sh to check them"
fi

echo
echo "fails: $FAILS   known: $(grep -c ' KNOWN ' "$CHECKS")   pass: $(grep -c ' PASS ' "$CHECKS")" | tee "$OUT/summary.txt"
echo "evidence written to $OUT"
exit "$FAILS"
