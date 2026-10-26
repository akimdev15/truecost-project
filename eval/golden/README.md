# Golden dataset

The golden dataset is the ground truth behind the thesis accuracy claim. Each scenario pairs one
defined trip with a reference cost per rental company, assembled component by component from dated
published sources. `scripts/eval.py` (`make eval`) posts each trip to the running API and scores the
response against that reference.

Every reference value in this directory is collected by hand. Nothing here is estimated, derived
from the system under test, or filled in by a tool. A value that cannot be sourced stays out, and
the scenario stays a draft.

## Layout

| Path | Purpose |
| --- | --- |
| `scenarios/*.json` | one file per scenario, named after its `id` |
| `scenario.schema.json` | the JSON Schema the runner validates every scenario against |
| `TEMPLATE.json` | an empty scenario with every value null, copy it to start one |
| `sample-request.json` | the smoke and snapshot request, not a scenario and not scored |

## Scenario fields

`TEMPLATE.json` is strict JSON and cannot carry comments, so each field is explained here.

| Field | Meaning |
| --- | --- |
| `id` | lower case letters, digits, and hyphens, unique across files, same as the file name without `.json` |
| `status` | `draft` while any value is missing, `complete` once every value is filled. The runner skips drafts with a warning and refuses a complete scenario that has a null |
| `description` | one line saying what trip this is |
| `notes` | anything a reviewer should know, such as a quote that was unusual that day |
| `grid.crossingBand` | `0-1`, `2-3`, or `4+`, the count of tolled crossings on the trip counted over both legs |
| `grid.durationBand` | `1-2`, `3`, or `4+` rental days |
| `request` | the body posted to `POST /api/v1/trips/plan`, the same shape as `sample-request.json` |
| `request.rentalRateOverrides` | one entry per company with the whole trip base rate that company quoted you, in cents. This bypasses the rental provider so the quoted base rate is what the engine prices |
| `reference.companies.<CODE>` | one block per company in `rentalRateOverrides`, keyed by the same company code. The set of companies must match exactly |
| `reference.companies.<CODE>.components.rental` | the quoted base rate in cents. It must equal the override for that company |
| `...components.tollProgramFee` | the company's toll program fee for this crossing set and rental length under the expected strategy, computed by hand from the company's published terms |
| `...components.tolls` | toll cents payable on top of the program fee under the expected strategy. Under an unlimited plan this is usually 0 |
| `...components.congestion` | the Central Business District congestion charge for the trip |
| `...components.fuel` | trip distance divided by the class MPG, times the EIA price on the capture date |
| `...components.hotel` | the lodging quote for the stay |
| `...tollStrategy` | the strategy you worked out is cheapest for this company: `PERSONAL_TAG`, `COMPANY_PER_CROSSING`, `COMPANY_UNLIMITED`, or `NO_ARRANGEMENT` |
| `reference.cheapestCompany` | the company with the lowest sum of its six components. The runner checks this against your own numbers and refuses a mismatch |

Every leaf under `reference` is an object with three keys:

| Key | Meaning |
| --- | --- |
| `value` | integer cents, or the strategy name for `tollStrategy`, or the company code for `cheapestCompany` |
| `source` | the URL or the document name the value came from, specific enough to find it again |
| `capturedOn` | the date you read the source, `YYYY-MM-DD` |

The `tolls` component depends on the strategy. The system reports toll cents under the strategy it
chose, so the reference must be computed under the strategy you recorded as expected. If you think
the system picks a different strategy, record your own answer and let the strategy agreement metric
show the disagreement.

## Collection procedure

Collect all values for one scenario on one calendar day. A toll schedule read a week before a quote
does not describe a single trip, and the runner warns when a complete scenario spans more than one
`capturedOn` date.

1. Pick the trip. Fix origin, destination, pickup location, departure and return instants, car
   class, and whether the renter holds a personal E-ZPass. Place it in the grid cell you are
   filling. Dates must be in the future relative to your capture day so live quotes exist.
2. Rental base rate. Get a live quote from each company for exactly those dates, pickup, and car
   class. Record the all in base rate for the whole trip, before any toll program, in cents, once
   as `components.rental` and once in `request.rentalRateOverrides`. Source is the company quote
   page or a saved confirmation, named by company and time.
3. Route tolls. Take the published agency tariff for each crossing at the trip's time of day and
   day of week, using the car's toll class and the E-ZPass versus cash or toll by plate rate that
   applies under the strategy. Cross check the total against TollGuru and note any difference in
   `notes`. The agency tariff is the reference, TollGuru is the check.
4. Congestion. Read the MTA congestion relief zone schedule for the vehicle class, time of day, and
   number of zone entries in the trip. Count entries per day as the schedule defines them.
5. Toll program fees. For each company, read its published toll program terms, then compute by hand
   the fee for this crossing set and rental length. Include daily caps, per use day versus per
   rental day rules, and the unlimited plan price. Do this for each of the strategies the company
   offers and record the cheapest as `tollStrategy`. Source is the company terms page, named with
   the section.
6. Fuel. Take the EIA weekly retail gasoline price for the region on the capture date. Take the
   class MPG and the route distance, and compute gallons times price. Record the arithmetic in
   `notes` so a reviewer can check it.
7. Hotel. Get a lodging quote for the destination and the stay nights, captured the same day as
   the rental quotes. Source is the listing and the time.
8. Sum the six components per company, pick the lowest as `reference.cheapestCompany`, and set
   `status` to `complete`. Run `make eval-selftest` and `make eval`. A refused scenario prints the
   exact field that is missing.

Where a real E-ZPass statement or an itemized rental invoice is obtainable, use it, and say so in
`source` and `notes` so assembled and billed accuracy can be reported separately.

## The grid and the target

The toll strategy decision flips along crossing count and rental duration, so the grid matters more
than the raw count. Three crossing bands by three duration bands gives nine cells.

|  | 1 to 2 days | 3 days | 4 or more days |
| --- | --- | --- | --- |
| 0 to 1 crossings | cell 1 | cell 2 | cell 3 |
| 2 to 3 crossings | cell 4 | cell 5 | cell 6 |
| 4 or more crossings | cell 7 | cell 8 | cell 9 |

The target is 20 scenarios, staged so there is usable evidence early:

| Stage | Total complete | Coverage |
| --- | --- | --- |
| 1 | 8 | cells 1, 3, 5, 7, and 9 once each, and the other three in the 1 to 2 day column, cells 1, 4, and 7 |
| 2 | 14 | every one of the nine cells filled at least once |
| 3 | 20 | no cell below two, and at least 10 of the 20 in the 1 to 2 day column |

At least half of all scenarios should be 1 or 2 day trips. The Hard Stop 4 sweep showed the weekend
case is where the saving over the default unlimited plan is smallest, so it is the case the
accuracy claim is most exposed on. The runner reports every metric per duration band, so the
weekend result is visible on its own however the reporting definition of a weekend trip is decided.

## Running it

```
make eval-selftest
make eval
```

`make eval-selftest` checks the scoring math on `eval/selftest/selftest-fixture.json`, which holds
synthetic numbers and is the only place in the repository where invented values may appear. `make
eval` needs the API running and writes `eval/results/<timestamp>/`. See `docs/RUNBOOK.md`.
