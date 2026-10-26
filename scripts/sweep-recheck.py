"""Recomputes every candidate fee in a strategy sweep from data/seeds/toll_programs.csv alone.

This is independent of the engine code. It reads the seeded program terms and checks the fee the
engine charged for each candidate, and that each candidate total is its fee plus tolls plus
congestion. It does not check toll amounts or congestion, which come from route detection.

Usage: python3 scripts/sweep-recheck.py docs/evidence/hs4/sweep-run1/responses.jsonl
"""
import csv
import json
import sys

programs = {}
for p in csv.DictReader(open("data/seeds/toll_programs.csv")):
    programs[(p["company_code"], p["program_name"])] = p

checked = failed = 0
for line in open(sys.argv[1]):
    entry = json.loads(line)
    days = entry["response"]["context"]["rentalDays"]
    for option in entry["response"]["options"]:
        for c in option["strategy"]["candidates"]:
            checked += 1
            problems = []
            if c["totalCents"] != c["feeCents"] + c["tollCents"] + c["congestionCents"]:
                problems.append("total is not fee plus tolls plus congestion")
            if c["strategy"] in ("COMPANY_UNLIMITED", "COMPANY_PER_CROSSING"):
                p = programs[(option["companyCode"], c["programName"])]
                fee_days = days if p["fee_day_basis"] == "RENTAL_DAYS" else c["feeDays"]
                expected = int(p["daily_fee_cents"]) * fee_days
                if p["cap_cents"]:
                    expected = min(expected, int(p["cap_cents"]))
                if p["fee_requires_usage"] == "true" and c["tollCents"] == 0:
                    expected = 0
                if c["feeCents"] != expected:
                    problems.append(f"fee {c['feeCents']} expected {expected}")
                if p["tolls_included"] == "true" and c["tollCents"] != 0:
                    problems.append("unlimited plan still charged tolls")
            elif c["feeCents"] != 0:
                problems.append("own tag or no arrangement charged a program fee")
            if problems:
                failed += 1
                print(entry["route"], entry["days"], entry["tag"], option["companyCode"], c["strategy"], ", ".join(problems))

print(f"candidates rechecked from the seed file {checked}, mismatches {failed}")
