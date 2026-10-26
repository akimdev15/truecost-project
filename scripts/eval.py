#!/usr/bin/env python3
"""Accuracy runner for the golden dataset.

Validates every scenario in eval/golden/scenarios against eval/golden/scenario.schema.json, posts each
complete one to the running API, and scores the response against the hand collected reference. The
runner never supplies a reference value. A complete scenario with a null, a missing source, or a
missing capture date is refused before any request is sent.

Usage, from the project root, with the API running on 8080 unless overridden:
  scripts/eval.py [--base-url URL] [--scenarios DIR] [--out DIR]
  scripts/eval.py --selftest eval/selftest/selftest-fixture.json

Exit codes: 0 scored (or nothing complete to score), 1 a scenario was refused or the self test
failed, 2 the API could not be reached or returned an error for a scenario.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEMA_PATH = ROOT / "eval/golden/scenario.schema.json"
SCENARIO_DIR = ROOT / "eval/golden/scenarios"
RESULTS_DIR = ROOT / "eval/results"
JAR_PATH = ROOT / "target/truecost-0.0.1-SNAPSHOT.jar"

SC1_MAPE_LIMIT_PCT = 8.0
REQUEST_TIMEOUT_SECONDS = 30

COMPONENTS = ("rental", "tollProgramFee", "tolls", "congestion", "fuel", "hotel")
RESPONSE_FIELD = {
    "rental": "rentalCents",
    "tollProgramFee": "tollProgramFeeCents",
    "tolls": "tollTotalCents",
    "congestion": "congestionCents",
    "fuel": "fuelCents",
    "hotel": "hotelCents",
}
DURATION_BANDS = ("1-2", "3", "4+")
CROSSING_BANDS = ("0-1", "2-3", "4+")


class SchemaValidator:
    """Validates the JSON Schema keyword subset that scenario.schema.json uses, standard library only."""

    def __init__(self, schema):
        self.root = schema

    def validate(self, instance):
        errors = []
        self._check(instance, self.root, "$", errors)
        return errors

    def _resolve(self, schema):
        while "$ref" in schema:
            node = self.root
            for part in schema["$ref"].lstrip("#/").split("/"):
                node = node[part]
            schema = node
        return schema

    @staticmethod
    def _type_ok(value, name):
        if name == "null":
            return value is None
        if name == "boolean":
            return isinstance(value, bool)
        if name == "integer":
            return isinstance(value, int) and not isinstance(value, bool)
        if name == "number":
            return isinstance(value, (int, float)) and not isinstance(value, bool)
        if name == "string":
            return isinstance(value, str)
        if name == "array":
            return isinstance(value, list)
        if name == "object":
            return isinstance(value, dict)
        return False

    def _check(self, value, schema, path, errors):
        schema = self._resolve(schema)

        if "type" in schema:
            names = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
            if not any(self._type_ok(value, n) for n in names):
                found = "null" if value is None else type(value).__name__
                errors.append(f"{path}: expected {' or '.join(names)}, found {found}")
                return
        if "const" in schema and value != schema["const"]:
            errors.append(f"{path}: expected {schema['const']!r}")
            return
        if "enum" in schema and value not in schema["enum"]:
            errors.append(f"{path}: {value!r} is not one of {schema['enum']}")
            return

        if isinstance(value, str):
            if len(value.strip()) < schema.get("minLength", 0):
                errors.append(f"{path}: must not be empty")
            if "pattern" in schema and not re.search(schema["pattern"], value):
                errors.append(f"{path}: {value!r} does not match {schema['pattern']}")
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            if "minimum" in schema and value < schema["minimum"]:
                errors.append(f"{path}: {value} is below the minimum {schema['minimum']}")
        if isinstance(value, list):
            if len(value) < schema.get("minItems", 0):
                errors.append(f"{path}: needs at least {schema['minItems']} items, found {len(value)}")
            for i, item in enumerate(value):
                if "items" in schema:
                    self._check(item, schema["items"], f"{path}[{i}]", errors)
        if isinstance(value, dict):
            if len(value) < schema.get("minProperties", 0):
                errors.append(f"{path}: needs at least {schema['minProperties']} entries, found {len(value)}")
            for key in schema.get("required", []):
                if key not in value:
                    errors.append(f"{path}: missing required field {key}")
            props = schema.get("properties", {})
            for key, child in value.items():
                if key in props:
                    self._check(child, props[key], f"{path}.{key}", errors)
                elif schema.get("additionalProperties") is False:
                    errors.append(f"{path}: unexpected field {key}")
                elif isinstance(schema.get("additionalProperties"), dict):
                    self._check(child, schema["additionalProperties"], f"{path}.{key}", errors)
                if "propertyNames" in schema:
                    self._check(key, schema["propertyNames"], f"{path}.{key} (name)", errors)

        if "if" in schema:
            probe = []
            self._check(value, schema["if"], path, probe)
            if not probe and "then" in schema:
                self._check(value, schema["then"], path, errors)


def load_json(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def sha16(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()[:16]


def consistency_errors(scn):
    """Checks that need more than one field. Only meaningful for a scenario that passed the schema."""
    errors = []
    req = scn["request"]
    ref = scn["reference"]
    override_codes = [o["companyCode"] for o in req["rentalRateOverrides"]]
    if len(set(override_codes)) != len(override_codes):
        errors.append("request.rentalRateOverrides repeats a company")
    if set(override_codes) != set(ref["companies"]):
        errors.append(f"reference.companies {sorted(ref['companies'])} must match "
                      f"request.rentalRateOverrides companies {sorted(override_codes)}")
        return errors
    if req["returnAt"] <= req["departureAt"]:
        errors.append("request.returnAt must be after request.departureAt")

    totals = {}
    for code, entry in ref["companies"].items():
        totals[code] = sum(entry["components"][c]["value"] for c in COMPONENTS)
    for o in req["rentalRateOverrides"]:
        quoted = ref["companies"][o["companyCode"]]["components"]["rental"]["value"]
        if quoted != o["totalCents"]:
            errors.append(f"{o['companyCode']}: reference rental {quoted} differs from the quoted override "
                          f"{o['totalCents']}, both are the same base rate and must match")
    cheapest = ref["cheapestCompany"]["value"]
    if cheapest not in totals:
        errors.append(f"reference.cheapestCompany {cheapest} is not one of the scenario companies")
    elif totals[cheapest] != min(totals.values()):
        errors.append(f"reference.cheapestCompany {cheapest} is not the lowest summed reference total "
                      f"{ {k: totals[k] for k in sorted(totals)} }")
    return errors


def capture_dates(scn):
    dates = {scn["reference"]["cheapestCompany"]["capturedOn"]}
    for entry in scn["reference"]["companies"].values():
        dates.add(entry["tollStrategy"]["capturedOn"])
        for c in COMPONENTS:
            dates.add(entry["components"][c]["capturedOn"])
    return sorted(dates)


def load_scenarios(directory, validator):
    """Returns (complete, drafts, refusals). A refusal is (name, [messages])."""
    complete, drafts, refusals = [], [], []
    seen = {}
    for path in sorted(Path(directory).glob("*.json")):
        try:
            scn = load_json(path)
        except ValueError as exc:
            refusals.append((path.name, [f"not valid JSON: {exc}"]))
            continue
        errors = validator.validate(scn)
        if not errors and scn["status"] == "complete":
            errors = consistency_errors(scn)
        if not errors:
            if scn["id"] in seen:
                errors = [f"id {scn['id']} is already used by {seen[scn['id']]}"]
            else:
                seen[scn["id"]] = path.name
        if errors:
            refusals.append((path.name, errors))
        elif scn["status"] == "draft":
            drafts.append(path.name)
        else:
            scn["_file"] = path.name
            scn["_sha"] = sha16(path)
            complete.append(scn)
    return complete, drafts, refusals


def pct_error(reference, actual):
    """Absolute percentage error. None when the reference is zero and the system is not."""
    if reference == 0:
        return 0.0 if actual == 0 else None
    return abs(actual - reference) / reference * 100.0


def mean(values):
    return sum(values) / len(values) if values else None


def score_scenario(scn, response):
    ref = scn["reference"]
    options = {o["companyCode"]: o for o in response.get("options", [])}
    recommendation = response.get("recommendation") or {}
    recommended = recommendation.get("companyCode")
    if recommended is None and response.get("options"):
        recommended = response["options"][0]["companyCode"]

    companies = {}
    for code, entry in ref["companies"].items():
        option = options.get(code)
        if option is None:
            companies[code] = {"missing": True}
            continue
        ref_total = sum(entry["components"][c]["value"] for c in COMPONENTS)
        comps = {}
        for c in COMPONENTS:
            expected = entry["components"][c]["value"]
            actual = option[RESPONSE_FIELD[c]]
            comps[c] = {"reference": expected, "actual": actual, "errorCents": actual - expected,
                        "pctError": pct_error(expected, actual)}
        total_actual = option["trueTotalCents"]
        provenance = option.get("provenance") or {}
        companies[code] = {
            "missing": False,
            "components": comps,
            "total": {"reference": ref_total, "actual": total_actual, "errorCents": total_actual - ref_total,
                      "pctError": pct_error(ref_total, total_actual)},
            "strategy": {"expected": entry["tollStrategy"]["value"],
                         "actual": (option.get("strategy") or {}).get("kind")},
            "partial": bool(option.get("partial")),
            "fallbackInputs": [k for k, v in (("fuel", provenance.get("fuelPriceIsFallback") is True),
                                              ("rental", str(provenance.get("rentalSource", "")).upper() == "SYNTHETIC"),
                                              ("hotel", str(provenance.get("hotelSource", "")).upper() == "SYNTHETIC")) if v],
        }
        companies[code]["strategy"]["agree"] = (companies[code]["strategy"]["expected"]
                                                == companies[code]["strategy"]["actual"])

    expected_cheapest = ref["cheapestCompany"]["value"]
    return {
        "id": scn["id"],
        "grid": scn["grid"],
        "companies": companies,
        "ranking": {"expectedCheapest": expected_cheapest, "systemCheapest": recommended,
                    "inversion": recommended != expected_cheapest},
        "rentalDays": (response.get("context") or {}).get("rentalDays"),
        "freshness": response.get("freshness"),
    }


def aggregate(scored):
    pairs = [(s, code, c) for s in scored for code, c in s["companies"].items()]
    present = [(s, code, c) for s, code, c in pairs if not c["missing"]]
    total_pcts = [c["total"]["pctError"] for _, _, c in present if c["total"]["pctError"] is not None]
    total_errs = [c["total"]["errorCents"] for _, _, c in present]

    cheapest_pcts = []
    for s, code, c in present:
        if code == s["ranking"]["expectedCheapest"] and c["total"]["pctError"] is not None:
            cheapest_pcts.append(c["total"]["pctError"])

    mape = mean(total_pcts)
    sc1 = None
    if mape is not None:
        sc1 = "PASS" if mape <= SC1_MAPE_LIMIT_PCT and len(present) == len(pairs) else (
            "FAIL" if mape > SC1_MAPE_LIMIT_PCT else "INCOMPLETE")

    components = {}
    abs_sums = {}
    for comp in COMPONENTS:
        errs = [c["components"][comp]["errorCents"] for _, _, c in present]
        pcts = [c["components"][comp]["pctError"] for _, _, c in present]
        defined = [p for p in pcts if p is not None]
        abs_sums[comp] = sum(abs(e) for e in errs)
        components[comp] = {
            "n": len(errs),
            "maeCents": mean([abs(e) for e in errs]),
            "biasCents": mean(errs),
            "mape": mean(defined),
            "mapeN": len(defined),
            "pctUndefined": len(pcts) - len(defined),
        }
    grand = sum(abs_sums.values())
    for comp in COMPONENTS:
        components[comp]["shareOfAbsError"] = abs_sums[comp] / grand if grand else None

    by_company = {}
    for _, code, c in present:
        slot = by_company.setdefault(code, {"n": 0, "agree": 0})
        slot["n"] += 1
        slot["agree"] += 1 if c["strategy"]["agree"] else 0
    agree = sum(v["agree"] for v in by_company.values())
    n_strategy = sum(v["n"] for v in by_company.values())

    inversions = sum(1 for s in scored if s["ranking"]["inversion"])
    return {
        "scenarios": len(scored),
        "options": len(present),
        "missingOptions": len(pairs) - len(present),
        "optionsWithFallbackInputs": sum(1 for _, _, c in present if c["fallbackInputs"]),
        "total": {"n": len(total_pcts), "mape": mape, "maeCents": mean([abs(e) for e in total_errs]),
                  "biasCents": mean(total_errs), "mapeCheapestOption": mean(cheapest_pcts)},
        "sc1": {"limitPct": SC1_MAPE_LIMIT_PCT, "verdict": sc1},
        "ranking": {"scenarios": len(scored), "inversions": inversions,
                    "rate": inversions / len(scored) if scored else None,
                    "sc2Verdict": None if not scored else ("PASS" if inversions == 0 else "FAIL")},
        "components": components,
        "strategy": {"n": n_strategy, "agree": agree, "rate": agree / n_strategy if n_strategy else None,
                     "byCompany": by_company},
    }


def split_by(scored, key, bands):
    return {b: aggregate([s for s in scored if s["grid"][key] == b]) for b in bands
            if any(s["grid"][key] == b for s in scored)}


def band_warnings(scored):
    """Flags a scenario whose duration band disagrees with the rental days the engine priced."""
    warnings = []
    for s in scored:
        days = s["rentalDays"]
        if days is None:
            continue
        band = "1-2" if days <= 2 else "3" if days == 3 else "4+"
        if band != s["grid"]["durationBand"]:
            warnings.append(f"{s['id']}: tagged duration band {s['grid']['durationBand']} but the engine priced "
                            f"{days} rental days, which is band {band}")
    return warnings


def fmt(value, places=2, suffix=""):
    return "n/a" if value is None else f"{value:.{places}f}{suffix}"


def render_aggregate(agg, indent=""):
    lines = []
    t = agg["total"]
    lines.append(f"{indent}scenarios {agg['scenarios']}, priced options {agg['options']}, "
                 f"missing options {agg['missingOptions']}, options with fallback inputs "
                 f"{agg['optionsWithFallbackInputs']}")
    lines.append(f"{indent}SC1 true total MAPE   {fmt(t['mape'], 2, ' percent')} over {t['n']} options, "
                 f"limit {agg['sc1']['limitPct']:.1f}, {agg['sc1']['verdict'] or 'no data'}")
    lines.append(f"{indent}  mean abs error       {fmt(t['maeCents'], 0)} cents, bias {fmt(t['biasCents'], 0)} cents")
    lines.append(f"{indent}  MAPE, reference cheapest option only {fmt(t['mapeCheapestOption'], 2, ' percent')}")
    r = agg["ranking"]
    lines.append(f"{indent}SC2 ranking inversions {r['inversions']} of {r['scenarios']} scenarios, "
                 f"target 0, {r['sc2Verdict'] or 'no data'}")
    s = agg["strategy"]
    lines.append(f"{indent}toll strategy agreement {s['agree']} of {s['n']} options ({fmt(None if s['rate'] is None else s['rate'] * 100, 1, ' percent')})")
    for code, v in sorted(s["byCompany"].items()):
        lines.append(f"{indent}  {code:<12} {v['agree']} of {v['n']}")
    lines.append(f"{indent}error by component, cents unless marked")
    lines.append(f"{indent}  {'component':<15}{'MAE':>10}{'bias':>10}{'MAPE pct':>10}{'share':>8}{'undef':>7}")
    for comp in COMPONENTS:
        c = agg["components"][comp]
        share = None if c["shareOfAbsError"] is None else c["shareOfAbsError"] * 100
        lines.append(f"{indent}  {comp:<15}{fmt(c['maeCents'], 0):>10}{fmt(c['biasCents'], 0):>10}"
                     f"{fmt(c['mape'], 2):>10}{fmt(share, 0, '%'):>8}{c['pctUndefined']:>7}")
    return lines


def render_summary(meta, drafts, warnings, scored, overall, by_duration, by_crossing, errors):
    lines = [f"TrueCost accuracy evaluation, {meta['captured']}", ""]
    lines += render_aggregate(overall)
    for title, groups in (("duration band", by_duration), ("crossing band", by_crossing)):
        for band, agg in groups.items():
            lines += ["", f"by {title} {band}"] + render_aggregate(agg, "  ")
    lines += ["", "per scenario"]
    for s in scored:
        parts = []
        for code, c in sorted(s["companies"].items()):
            parts.append(f"{code} missing" if c["missing"] else f"{code} {fmt(c['total']['pctError'], 2, '%')}")
        mark = "INVERSION" if s["ranking"]["inversion"] else "ok"
        lines.append(f"  {s['id']:<28}{mark:<10}{', '.join(parts)}")
    if drafts:
        lines += ["", f"skipped drafts {len(drafts)}: {', '.join(drafts)}"]
    for w in warnings:
        lines.append(f"warning {w}")
    for e in errors:
        lines.append(f"error {e}")
    return "\n".join(lines) + "\n"


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True).stdout.strip()


def metadata(base_url, scenarios, script_path):
    dirty = git("status", "--porcelain", "--", "src", "pom.xml")
    return {
        "captured": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "commit": git("rev-parse", "--short", "HEAD"),
        "product": "modified" if dirty else "unchanged",
        "jar": sha16(JAR_PATH) if JAR_PATH.exists() else "absent",
        "script": sha16(script_path),
        "schema": sha16(SCHEMA_PATH),
        "baseUrl": base_url,
        "scenarios": {s["id"]: s["_sha"] for s in scenarios},
    }


def post_plan(base_url, request):
    body = json.dumps(request).encode("utf-8")
    req = urllib.request.Request(base_url.rstrip("/") + "/api/v1/trips/plan", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_SECONDS) as resp:
        return json.loads(resp.read().decode("utf-8"))


def lookup(tree, dotted):
    node = tree
    for part in dotted.split("."):
        node = node[part]
    return node


def selftest(fixture_path):
    fixture = load_json(fixture_path)
    if fixture.get("fixture") is not True:
        print(f"refusing {fixture_path}: not marked as a synthetic fixture", file=sys.stderr)
        return 1
    validator = SchemaValidator(load_json(SCHEMA_PATH))
    scored = []
    failures = []
    for entry in fixture["scenarios"]:
        mock = entry.pop("mockResponse")
        errors = validator.validate(entry) or consistency_errors(entry)
        if errors:
            failures.append(f"fixture scenario {entry.get('id')} is invalid: {errors}")
            continue
        scored.append(score_scenario(entry, mock))
    overall = aggregate(scored)
    groups = {"overall": overall,
              "byDuration": split_by(scored, "durationBand", DURATION_BANDS),
              "byCrossing": split_by(scored, "crossingBand", CROSSING_BANDS)}

    assert pct_error(0, 0) == 0.0
    assert pct_error(0, 5) is None
    assert math.isclose(pct_error(200, 150), 25.0)

    for dotted, want in fixture["expected"].items():
        tree = groups["overall"] if dotted.split(".")[0] not in ("byDuration", "byCrossing") else groups
        try:
            got = lookup(tree, dotted)
        except (KeyError, TypeError):
            failures.append(f"{dotted}: not present in the scored output")
            continue
        ok = got == want if isinstance(want, str) else (got is not None and math.isclose(got, want, abs_tol=1e-4))
        if not ok:
            failures.append(f"{dotted}: expected {want}, got {got}")
    if failures:
        for f in failures:
            print(f"selftest FAIL {f}", file=sys.stderr)
        return 1
    print(f"selftest ok, {len(fixture['expected'])} checks over {len(scored)} synthetic scenarios")
    return 0


def run(args):
    validator = SchemaValidator(load_json(SCHEMA_PATH))
    complete, drafts, refusals = load_scenarios(args.scenarios, validator)

    for name, messages in refusals:
        print(f"REFUSED {name}", file=sys.stderr)
        for m in messages:
            print(f"  {m}", file=sys.stderr)
    if refusals:
        print(f"{len(refusals)} scenario file(s) refused, fix them or mark them \"status\": \"draft\"", file=sys.stderr)
        return 1
    for name in drafts:
        print(f"warning: skipping draft scenario {name}", file=sys.stderr)

    if not complete:
        print(f"No scenarios are complete ({len(drafts)} draft). Nothing was scored and no API request was sent.")
        print("Collect ground truth by hand per eval/golden/README.md, then set \"status\": \"complete\".")
        return 0

    warnings = []
    for scn in complete:
        spread = capture_dates(scn)
        if len(spread) > 1:
            warnings.append(f"{scn['id']}: reference values were captured on {len(spread)} different dates "
                            f"{spread}, the same day rule is broken")

    scored, errors, raw = [], [], {}
    for scn in complete:
        try:
            response = post_plan(args.base_url, scn["request"])
        except urllib.error.HTTPError as exc:
            errors.append(f"{scn['id']}: HTTP {exc.code} {exc.read().decode('utf-8', 'replace')[:200]}")
            continue
        except (urllib.error.URLError, OSError) as exc:
            print(f"cannot reach the API at {args.base_url}: {exc}", file=sys.stderr)
            return 2
        raw[scn["id"]] = response
        scored.append(score_scenario(scn, response))

    meta = metadata(args.base_url, complete, Path(__file__).resolve())
    overall = aggregate(scored)
    by_duration = split_by(scored, "durationBand", DURATION_BANDS)
    by_crossing = split_by(scored, "crossingBand", CROSSING_BANDS)
    warnings += band_warnings(scored)

    out = Path(args.out) if args.out else RESULTS_DIR / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out.mkdir(parents=True, exist_ok=True)
    summary = render_summary(meta, drafts, warnings, scored, overall, by_duration, by_crossing, errors)
    (out / "summary.txt").write_text(summary, encoding="utf-8")
    (out / "metadata.txt").write_text(
        f"captured   {meta['captured']}\n"
        f"commit     {meta['commit']}, product {meta['product']}\n"
        f"api jar    {meta['jar']}\n"
        f"script     {meta['script']}\n"
        f"schema     {meta['schema']}\n"
        f"api        {meta['baseUrl']}\n"
        f"scenarios  {len(complete)} complete, {len(drafts)} draft skipped\n"
        + "".join(f"  {k}  {v}\n" for k, v in meta["scenarios"].items()), encoding="utf-8")
    (out / "results.json").write_text(json.dumps({
        "metadata": meta, "overall": overall, "byDuration": by_duration, "byCrossing": by_crossing,
        "scenarios": scored, "responses": raw, "warnings": warnings, "errors": errors,
    }, indent=2) + "\n", encoding="utf-8")

    print(f"results written to {out}")
    print(summary)
    return 2 if errors else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=os.environ.get("EVAL_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--scenarios", default=str(SCENARIO_DIR))
    parser.add_argument("--out", default=None)
    parser.add_argument("--selftest", metavar="FIXTURE", default=None)
    args = parser.parse_args()
    return selftest(args.selftest) if args.selftest else run(args)


if __name__ == "__main__":
    sys.exit(main())
