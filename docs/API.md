# API contract

One endpoint. Every payload below is copied from a real response captured on 4 October 2026
against the running stack, not written by hand.

## POST /api/v1/trips/plan

Values a trip and returns every rental option ranked by true total, with the cheapest
recommended.

### Request

`eval/golden/sample-request.json` is the canonical fixture used by the smoke test and the
snapshot test.

```json
{
  "originLat": 40.7505,
  "originLng": -73.9934,
  "pickupLocationCode": "EWR",
  "destLat": 39.9500,
  "destLng": -75.1600,
  "destinationCode": "PHL",
  "departureAt": "2026-08-07T22:00:00Z",
  "returnAt": "2026-08-09T18:00:00Z",
  "hasPersonalEzpass": false,
  "carClass": "MIDSIZE"
}
```

| Field | Meaning |
| --- | --- |
| `originLat`, `originLng` | Trip origin |
| `pickupLocationCode` | Rental pickup location |
| `destLat`, `destLng` | Destination |
| `destinationCode` | Destination airport or city code |
| `departureAt`, `returnAt` | ISO 8601 instants. The return leg is priced separately |
| `hasPersonalEzpass` | Whether the renter owns a personal tag, which changes which strategies can compete |
| `carClass` | Vehicle class, resolved to a toll class |
| `rentalRateOverrides` | Optional. Per company base rates the renter was quoted. When present the rental provider fetch is bypassed entirely |

`rentalRateOverrides` is the path that matters for the thesis, because base rates are user
supplied input by design. Absent an override the synthetic provider fills in so the endpoint
stays usable.

### Response

Abbreviated to one option. The real response carried six.

```json
{
  "options": [
    {
      "companyCode": "THRIFTY",
      "companyName": "Thrifty",
      "carClassCode": "MIDSIZE",
      "rentalCents": 9921,
      "tollProgramFeeCents": 4298,
      "tollTotalCents": 0,
      "congestionCents": 2700,
      "fuelCents": 2199,
      "hotelCents": 22474,
      "trueTotalCents": 41592,
      "strategy": {
        "kind": "COMPANY_UNLIMITED",
        "programName": "PlatePass All-Inclusive",
        "totalCents": 6998,
        "explanation": "Thrifty PlatePass All-Inclusive wins at 69.98. Next best is Thrifty PlatePass per-crossing at 92.28, a margin of 22.30. The flat unlimited plan fee of 42.98 covers every toll, which comes out cheaper than paying per crossing here.",
        "candidates": [
          {
            "strategy": "COMPANY_UNLIMITED",
            "programName": "PlatePass All-Inclusive",
            "totalCents": 6998,
            "feeCents": 4298,
            "tollCents": 0,
            "congestionCents": 2700,
            "feeDays": 2
          },
          {
            "strategy": "COMPANY_PER_CROSSING",
            "programName": "PlatePass",
            "totalCents": 9228,
            "feeCents": 1998,
            "tollCents": 4530,
            "congestionCents": 2700,
            "feeDays": 2
          }
        ]
      },
      "provenance": {
        "rentalSource": "SYNTHETIC",
        "fuelPriceIsFallback": true,
        "hotelSource": "SYNTHETIC"
      },
      "partial": true
    }
  ],
  "recommendation": {
    "companyCode": "THRIFTY",
    "carClassCode": "MIDSIZE",
    "trueTotalCents": 41592,
    "savingsOverNextCents": 872,
    "summary": "Thrifty MIDSIZE is the cheapest option at $415.92 all in, saving $8.72 over the next best option."
  },
  "context": {
    "rentalDays": 2,
    "totalDistanceMiles": 191.6542392428219,
    "tollClass": "PASSENGER",
    "outboundRouteHash": "dr5ru4-dr4e38",
    "returnRouteHash": "dr4e38-dr5ru4",
    "departureBucket": "2026080718",
    "returnBucket": "2026080914",
    "dateBucket": "2026-08-07"
  },
  "freshness": {
    "route": "FRESH",
    "tolls": "FRESH",
    "fuel": "UNAVAILABLE",
    "hotel": "FRESH"
  }
}
```

Every money field is an integer count of cents. Nothing in the ranking path is a floating point
number, because a rounding difference that flips which option ranks first would be an accuracy
failure attributed to the wrong cause.

`candidates` carries every strategy the engine evaluated, not only the winner, so a reader can
check the decision rather than trust it. `partial` is true when any input came from a fallback.
`provenance` and `freshness` are how a fallback stays visible instead of blending into the total.

## Strategies

The engine prices four and picks the cheapest that applies.

| Strategy | When it competes |
| --- | --- |
| `PERSONAL_TAG` | The renter owns a personal E-ZPass |
| `COMPANY_PER_CROSSING` | A per crossing programme, daily fee plus tolls, fee possibly capped |
| `COMPANY_UNLIMITED` | A flat daily fee covering every toll |
| `NO_ARRANGEMENT` | Currently only when the trip has no tolls and no congestion cost |

The `NO_ARRANGEMENT` gate is the open question flagged as I-03 in `docs/RISK_LOG.md`.
`StrategyOracle` in the test tree copies the same gate, so the property test cannot detect a
wrong reading of it.

## Stub and fallback behaviour

There are no credentials in the baseline, so the documented stub paths are what actually runs.
This is the behaviour a peer will see on a clean clone.

| Provider | Default | Behaviour unset | Response marker |
| --- | --- | --- | --- |
| Rental | disabled | `SyntheticRentalProvider`, calibrated from `data/seeds/rental_calibration.csv`, 48 rows | `provenance.rentalSource` is `SYNTHETIC` |
| Fuel | disabled | Static `fallback-price-per-gallon`, currently 3.50 | `freshness.fuel` is `UNAVAILABLE`, `provenance.fuelPriceIsFallback` is true |
| Hotel | disabled | Synthetic estimate | `provenance.hotelSource` is `SYNTHETIC` |

In tests the outbound HTTP providers are stubbed with WireMock rather than by these fallbacks,
so the connector code itself is exercised against recorded responses. See
`RapidApiRentalProviderTest`, `FuelCostServiceTest`, and `AmadeusHotelProviderTest`.

## Other endpoints

| Endpoint | Purpose |
| --- | --- |
| `GET /actuator/health` | Liveness, includes Postgres and Redis component status |
| `GET /actuator/prometheus` | Metrics scrape |
| `GET /actuator/metrics/truecost.strategy.chosen` | Counter of which strategy won, tagged by kind |
| `GET /` | The demo UI |

## Smoke check

```
make demo-check
```

```
api    UP
osrm   UP
plan   HTTP 200
```

Full captured output is in `docs/evidence/sprint-01-smoke.txt`.
