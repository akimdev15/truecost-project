"""Runs the reference cases in eval/reference against the running API and compares every expected
value exactly.

A reference case fixes a trip, states the expected result, and lists the assumptions and dated
sources that produced each expected number, so another developer can redo the arithmetic by hand.
The cases check the computation against published tariffs and program terms. They do not check
real market prices, so passing them is not evidence of accuracy against what a renter pays. That
is the golden set's job, eval/golden, which stays unverified until its scenarios are complete.

Usage, from the project root, with the API on 8080: python3 scripts/reference-check.py [base_url]
Exit status is the number of failed assertions.
"""
import glob
import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
failures = passes = 0


def post(body):
    req = urllib.request.Request(BASE + "/api/v1/trips/plan", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def check(case, label, ok, detail=""):
    global failures, passes
    if ok:
        passes += 1
    else:
        failures += 1
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{'  ' + detail if detail and not ok else ''}")


for path in sorted(glob.glob("eval/reference/*.json")):
    case = json.load(open(path))
    expect = case["expect"]
    tol = case.get("toleranceCents", 0)
    status, body = post(case["request"])
    print(f"{case['id']}")
    check(case, f"HTTP {expect['http']}", status == expect["http"], f"got {status}")
    if "code" in expect:
        check(case, f"code {expect['code']}", body.get("code") == expect["code"], f"got {body.get('code')}")
    for word in expect.get("errorMentions", []):
        check(case, f"error names {word}", word in body.get("error", ""), body.get("error", ""))
    options = {o["companyCode"]: o for o in body.get("options", [])}
    for company, want in expect.get("companies", {}).items():
        got = options.get(company)
        if got is None:
            check(case, f"{company} present", False, "missing from the response")
            continue
        s = got["strategy"]
        check(case, f"{company} chooses {want['strategy']}", s["kind"] == want["strategy"], f"got {s['kind']}")
        check(case, f"{company} toll total {want['totalCents']}", abs(s["totalCents"] - want["totalCents"]) <= tol,
              f"got {s['totalCents']}")
        cands = {c["strategy"]: c["totalCents"] for c in s.get("candidates", [])}
        for kind, cents in want.get("candidates", {}).items():
            check(case, f"{company} {kind} candidate {cents}", abs(cands.get(kind, -1) - cents) <= tol,
                  f"got {cands.get(kind)}")
        if "rentalCents" in expect:
            check(case, f"{company} rental {expect['rentalCents']}", got["rentalCents"] == expect["rentalCents"],
                  f"got {got['rentalCents']}")
        for key, value in expect.get("provenance", {}).items():
            check(case, f"{company} provenance {key} {value}", got["provenance"].get(key) == value,
                  f"got {got['provenance'].get(key)}")
    for key, value in expect.get("freshness", {}).items():
        check(case, f"freshness {key} {value}", body.get("freshness", {}).get(key) == value,
              f"got {body.get('freshness', {}).get(key)}")

print(f"reference cases, {passes} assertions passed, {failures} failed. These check computation against "
      f"published tariffs, not market accuracy, which stays unverified until the golden set is complete.")
sys.exit(min(failures, 125))
