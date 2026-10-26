#!/usr/bin/env bash
# Sweeps the trip planner across a fixed grid of routes, rental lengths, and tag ownership, and
# records every toll strategy the engine priced for every company. The comparison baseline is the
# one SC9 names, always buying the company's unlimited plan, which is what a renter does by default
# at the counter. Each run records the commit and the artifact checksums it ran against, so a result
# can be traced to exactly what produced it.
#
# Usage, from the project root, with the API running on 8080:
#   scripts/strategy-sweep.sh <output-dir>
# Writes metadata.txt, responses.jsonl, options.csv, and summary.txt into the output directory.
set -uo pipefail

OUT="${1:?pass an output directory}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
cd "$(dirname "$0")/.."
PLAN="http://localhost:8080/api/v1/trips/plan"
DEPART_DATE="2026-10-30"
DEPART_TIME="22:00:00Z"
RETURN_TIME="18:00:00Z"
DAYS="1 2 3 4 5 7"

# id|origin lat|origin lng|pickup code|destination lat|destination lng|destination code|in coverage
ROUTES="R1|40.7505|-73.9934|EWR|39.9500|-75.1600|PHL|yes
R2|40.7505|-73.9934|EWR|40.3573|-74.6672|PRN|yes
R3|40.7505|-73.9934|EWR|41.7476|-74.0868|NPZ|yes
R4|40.7505|-73.9934|EWR|41.0362|-71.9509|MTK|yes
R5|40.6782|-73.9442|BKN|41.0362|-71.9509|MTK|yes
R6|40.7505|-73.9934|EWR|41.0534|-73.5387|STM|yes
R7|40.7505|-73.9934|EWR|42.3601|-71.0589|BOS|no"

{
    echo "captured   $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "commit     $(git rev-parse --short HEAD), product $( [[ -n "$(git status --porcelain -- src pom.xml)" ]] && echo modified || echo unchanged )"
    echo "api jar    $(shasum -a 256 target/truecost-0.0.1-SNAPSHOT.jar | cut -c1-16)"
    echo "map source $(cut -c1-16 data/osrm/nyc-metro.osm.pbf.sha256), the extract the routing graph was built from"
    echo "script     $(shasum -a 256 "$0" | cut -c1-16)"
    echo "grid       routes R1 to R7, rental days $DAYS, personal tag false and true"
    echo "departure  $DEPART_DATE $DEPART_TIME, return at $RETURN_TIME on departure date plus rental days"
    echo "vehicle    MIDSIZE, rental base rates synthetic, which does not affect any toll strategy figure"
} > "$OUT/metadata.txt"

: > "$OUT/responses.jsonl"
echo "route,rental_days_requested,personal_tag,http_status,seconds" > "$OUT/timing.csv"
echo "route,in_coverage,rental_days_requested,personal_tag,company,rental_days,chosen,chosen_cents,unlimited_cents,per_crossing_cents,personal_tag_cents,no_arrangement_cents,candidate_count,true_total_cents,rank" > "$OUT/options.csv"

while IFS='|' read -r id olat olng pcode dlat dlng dcode cov; do
    for d in $DAYS; do
        ret=$(date -j -v+"${d}"d -f %Y-%m-%d "$DEPART_DATE" +%Y-%m-%d)
        for tag in false true; do
            body=$(jq -n --argjson olat "$olat" --argjson olng "$olng" --arg pc "$pcode" \
                --argjson dlat "$dlat" --argjson dlng "$dlng" --arg dc "$dcode" \
                --arg dep "${DEPART_DATE}T${DEPART_TIME}" --arg ret "${ret}T${RETURN_TIME}" --argjson tag "$tag" \
                '{originLat:$olat, originLng:$olng, pickupLocationCode:$pc, destLat:$dlat, destLng:$dlng,
                  destinationCode:$dc, departureAt:$dep, returnAt:$ret, hasPersonalEzpass:$tag, carClass:"MIDSIZE"}')
            raw=$(curl -s -w '\n%{http_code} %{time_total}' -X POST "$PLAN" -H 'Content-Type: application/json' -d "$body")
            resp=$(sed '$d' <<< "$raw")
            read -r code secs <<< "$(tail -n1 <<< "$raw")"
            echo "$id,$d,$tag,$code,$secs" >> "$OUT/timing.csv"
            jq -c --arg id "$id" --argjson d "$d" --argjson tag "$tag" \
                '{route:$id, days:$d, tag:$tag, response:.}' <<< "$resp" >> "$OUT/responses.jsonl"
            jq -r --arg id "$id" --arg cov "$cov" --argjson d "$d" --argjson tag "$tag" '
                .context.rentalDays as $rd
                | .options | to_entries[] | .key as $rank | .value
                | (.strategy.candidates) as $c
                | def cost(k): ([$c[] | select(.strategy == k) | .totalCents] | first // "");
                  [$id, $cov, $d, $tag, .companyCode, $rd, .strategy.kind, .strategy.totalCents,
                   cost("COMPANY_UNLIMITED"), cost("COMPANY_PER_CROSSING"), cost("PERSONAL_TAG"),
                   cost("NO_ARRANGEMENT"), ($c | length), .trueTotalCents, ($rank + 1)]
                | @csv' <<< "$resp" >> "$OUT/options.csv"
        done
    done
done <<< "$ROUTES"

# The summary is computed from options.csv alone, so it can be recomputed without the stack.
python3 - "$OUT/options.csv" > "$OUT/summary.txt" <<'PY'
import csv, statistics, sys
rows = list(csv.DictReader(open(sys.argv[1])))
num = lambda v: int(v) if v not in ("", None) else None
for r in rows:
    for k in ("chosen_cents", "unlimited_cents", "per_crossing_cents", "personal_tag_cents",
              "no_arrangement_cents", "true_total_cents", "rank", "rental_days"):
        r[k] = num(r[k])
inside = [r for r in rows if r["in_coverage"] == "yes"]
scen = {(r["route"], r["rental_days_requested"], r["personal_tag"]) for r in rows}
print(f"options priced            {len(rows)} across {len(scen)} trips")

bad = [r for r in rows if r["chosen_cents"] != min(v for v in (r["unlimited_cents"], r["per_crossing_cents"],
        r["personal_tag_cents"], r["no_arrangement_cents"]) if v is not None)]
print(f"chosen is not the cheapest {len(bad)} of {len(rows)} options")

with_unl = [r for r in inside if r["unlimited_cents"] is not None]
wrong = [r for r in with_unl if r["chosen"] != "COMPANY_UNLIMITED"]
print(f"default is the wrong pick  {len(wrong)} of {len(with_unl)} in-coverage options with an unlimited plan "
      f"({100 * len(wrong) / len(with_unl):.0f} percent)")

save_opt = [(r["unlimited_cents"] - r["chosen_cents"]) / 100 for r in with_unl]
print(f"saving per option          median {statistics.median(save_opt):.2f}, mean {statistics.mean(save_opt):.2f}, "
      f"max {max(save_opt):.2f} dollars")

# Per trip, the default renter buys each company's unlimited plan, or its only plan when it has no
# unlimited one, and books the cheapest company on that basis. The saving is that best total minus
# the engine's best total, so a company switch caused by the toll decision is counted too.
trips = {}
for r in inside:
    base = r["unlimited_cents"] if r["unlimited_cents"] is not None else r["chosen_cents"]
    t = trips.setdefault((r["route"], r["rental_days_requested"], r["personal_tag"]), {"engine": None, "default": None})
    t["engine"] = r["true_total_cents"] if t["engine"] is None else min(t["engine"], r["true_total_cents"])
    d = r["true_total_cents"] - r["chosen_cents"] + base
    t["default"] = d if t["default"] is None else min(t["default"], d)
saving = {k: (t["default"] - t["engine"]) / 100 for k, t in trips.items()}
med = statistics.median(saving.values())
print(f"SC9 saving per trip        median {med:.2f} dollars over {len(saving)} in-coverage trips, "
      f"target 10.00, {'PASS' if med >= 10 else 'FAIL'} on the generated set")
print(f"  trips saving under 10    {sum(1 for v in saving.values() if v < 10)} of {len(saving)}")
for tag in ("false", "true"):
    s = [v for k, v in saving.items() if k[2] == tag]
    print(f"  personal tag {tag:5}       median {statistics.median(s):.2f}, min {min(s):.2f}, max {max(s):.2f}")
by_days = {}
for k, v in saving.items():
    by_days.setdefault(int(k[1]), []).append(v)
print("  by rental days            " + ", ".join(f"{d} day {statistics.median(v):.2f}" for d, v in sorted(by_days.items())))
by_route = {}
for k, v in saving.items():
    by_route.setdefault(k[0], []).append(v)
print("  by route                  " + ", ".join(f"{r} {statistics.median(v):.2f}" for r, v in sorted(by_route.items())))

print("winning strategy counts    " + ", ".join(f"{k} {sum(1 for r in inside if r['chosen'] == k)}"
      for k in ("COMPANY_UNLIMITED", "COMPANY_PER_CROSSING", "PERSONAL_TAG", "NO_ARRANGEMENT")))
PY

python3 - "$OUT/timing.csv" >> "$OUT/summary.txt" <<'PY'
import csv, sys
rows = list(csv.DictReader(open(sys.argv[1])))
ms = sorted(float(r["seconds"]) * 1000 for r in rows)
p = lambda q: ms[min(len(ms) - 1, int(round(q * (len(ms) - 1))))]
print(f"request latency            n {len(ms)}, p50 {p(0.5):.0f} ms, p95 {p(0.95):.0f} ms, max {ms[-1]:.0f} ms, "
      f"non 200 {sum(1 for r in rows if r['http_status'] != '200')}")
PY

echo "sweep written to $OUT"
cat "$OUT/summary.txt"
