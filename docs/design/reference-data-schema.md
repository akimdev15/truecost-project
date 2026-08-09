# Reference data schema, Phase 1

This document finalizes the Postgres schema for TrueCost Phase 1 reference data. It is the design of record for the tables `toll_crossing`, `toll_rate`, `congestion_schedule`, `congestion_credit`, `rental_company`, `toll_program`, `vehicle_class`, and `quote_snapshot`, together with the seed CSV shapes and the repository lookup semantics. A draft SQL appendix at the end gives CREATE statements the Phase 1 implementation agent can adopt as a strong starting point for the Flyway migration.

This document does not create the migration. Phase 0 is being built concurrently in a separate worktree and it creates the Flyway migration directory with an empty baseline. Once Phase 0 has merged, the Phase 1 agent places the appendix SQL as the first non baseline versioned migration, for example `V1__reference_data.sql` or `V2__reference_data.sql` depending on how the Phase 0 baseline is numbered. No gradle, docker, or psql was run to produce this document and no database exists yet.

The prose here follows the project code standard, no em dashes, no en dashes, and no semicolons. Ordinary SQL statement terminators inside the appendix code block are mandatory syntax and are covered by the project exemption clause.

## Cross cutting conventions

These conventions apply to every table below and are stated once here.

- Money is stored as `BIGINT` whole cents, never a floating type, matching the domain `Money` value type and the long cents used in the Avro event schemas. Column names carrying money end in `_cents`.
- All wall clock time and day of week reasoning is in `America/New_York`. Local time windows are stored as `TIME` without time zone and are interpreted in that zone. Instants that are true points in time are stored as `TIMESTAMPTZ` and are UTC on the wire.
- Reference rows that change over time are versioned by an `effective_date DATE`. History is never overwritten. A newer rate is a new row with a later `effective_date` that supersedes the older row. Lookups select the row with the greatest `effective_date` that is less than or equal to the trip date. There is deliberately no `end_date` column, since supersession by the next effective date fully expresses the timeline and avoids the risk of a gap or overlap between an end date and the next start date.
- Coordinates on fixed reference points use `NUMERIC(9,6)`, which is exact and reproducible across reseeds and gives roughly a tenth of a meter of resolution, which matters because Phase 2 matches crossings against the route polyline under a fifty meter threshold. Coordinates that arrive from events, in `quote_snapshot`, stay `DOUBLE PRECISION` to match the Avro `double` fields exactly.
- Closed value domains use native Postgres enum types created with `CREATE TYPE ... AS ENUM`. Enums are compact, self documenting in `\d` output, and Postgres casts a CSV text token to the enum automatically under `COPY`, so seed loading is unaffected. The one evolution caveat is that adding a value later needs an `ALTER TYPE ... ADD VALUE` migration, which is acceptable for these stable domains.
- Day of week is a seven bit mask in a `SMALLINT`. Bit zero is Monday and bit six is Sunday, matching `java.time.DayOfWeek.getValue()` minus one. So Monday through Friday is `31`, Saturday plus Sunday is `96`, and every day is `127`. The lookup predicate is `(day_of_week_mask & (1 << (isoDow - 1))) <> 0`. See the toll_rate section for why a bitmask beats a boolean array or seven boolean columns.

## Enum types

The design uses these enum types. Their members are the closed domains the reference data needs.

- `toll_agency`, members `PANYNJ`, `MTABT`, `NJTA`, `NYSTA`. Port Authority of NY and NJ, MTA Bridges and Tunnels, NJ Turnpike Authority, NY State Thruway Authority.
- `crossing_type`, members `BRIDGE`, `TUNNEL`, `ROADWAY_SEGMENT`. The roadway segment member covers the NJ Turnpike, Garden State Parkway, and NY Thruway ticket segments, which are not a single span. See the toll_crossing section for the simplification this implies.
- `tolled_direction`, members `FORWARD`, `BOTH`. See toll_crossing for why `REVERSE` is not needed.
- `payment_type`, members `EZPASS`, `TOLLS_BY_MAIL`, `CASH`.
- `toll_class`, members `PASSENGER`, `MOTORCYCLE`, `TWO_AXLE`. The agency toll classification, not the rental car class. See vehicle_class for why these are separate.
- `congestion_period`, members `PEAK`, `OVERNIGHT`.
- `program_type`, members `USAGE_DAY`, `ALL_RENTAL_DAYS`, `UNLIMITED_DAILY`.
- `fee_day_basis`, members `USAGE_DAYS`, `RENTAL_DAYS`.
- `toll_rate_basis`, members `EZPASS_RATE`, `MAX_CASH_RATE`.

## 1. toll_crossing

Holds the roughly twenty crossings that cover the NYC weekend trip space, with the geometry the Phase 2 crossing detector needs.

Columns.

- `id BIGINT` generated identity, primary key.
- `code TEXT` unique, a short stable identifier such as `GWB`, `LINCOLN`, `HOLLAND`, `VERRAZZANO`. This is the join key used by `toll_rate` and by the seed CSVs, so a human can read and verify the seed without chasing numeric ids.
- `name TEXT` not null, the full crossing name.
- `agency toll_agency` not null.
- `crossing_type crossing_type` not null.
- `latitude NUMERIC(9,6)` not null, decimal degrees.
- `longitude NUMERIC(9,6)` not null, decimal degrees.
- `travel_bearing_deg SMALLINT` not null, check between 0 and 359.
- `tolled_directions tolled_direction` not null.

Direction representation, the real design choice. The Phase 2 detector matches the route polyline against a crossing point using haversine distance under a threshold and then does a bearing check so a road that passes near but does not traverse the crossing detects nothing, and so wrong direction travel on a one way tolled crossing detects nothing. The cleanest representation that serves both needs is a single numeric bearing plus a small enum, and the trick that makes it clean is to define the stored bearing as the tolled direction of travel.

- `travel_bearing_deg` is the compass bearing, 0 to 359, of the reference direction of travel across the crossing, and the reference direction is defined to be the tolled direction. The opposite direction of travel is `(travel_bearing_deg + 180) mod 360`.
- `tolled_directions` is `FORWARD` when only the reference bearing direction is tolled, and `BOTH` when travel in either direction is tolled.

Because the stored bearing is defined as the tolled direction, a one way crossing is always `FORWARD` and a `REVERSE` value is never needed, which removes an entire class of seed ambiguity. The detector logic falls out directly.

- Find the polyline point within the distance threshold of the crossing coordinate. If none, no crossing.
- Compute the vehicle bearing at that point from the polyline.
- If `tolled_directions` is `BOTH`, accept when the vehicle bearing is within tolerance of `travel_bearing_deg` or of its reverse. Either way it is tolled.
- If `tolled_directions` is `FORWARD`, accept only when the vehicle bearing is within tolerance of `travel_bearing_deg`. A match against the reverse means the untolled direction, so detect nothing.

This matches the real toll geography. The Port Authority Hudson crossings, the George Washington Bridge, Lincoln Tunnel, Holland Tunnel, Bayonne Bridge, Goethals Bridge, and Outerbridge Crossing, toll only the eastbound trip into New York, so they are `FORWARD` with the bearing set to the eastbound heading. Crossings that toll both ways are `BOTH`. The exact per crossing tolled direction is verified against the agency page at seed time and recorded in `crossings.csv`.

Simplification to flag for Phase 2. The NJ Turnpike, Garden State Parkway, and NY Thruway ticket segments are `ROADWAY_SEGMENT` crossings. Their true price is an entry to exit ticketed distance charge, not a single point crossing. For Phase 1 reference data they are modeled as a representative point with a representative segment toll for the covered corridors, and their `travel_bearing_deg` is the corridor direction of travel. Phase 2 owns any refinement of ticketed entry to exit pricing. This is called out so the detection agent is not surprised.

`crossing_type` is included beyond the fields the plan lists because the congestion credit rule is defined for the four tunnels, so knowing which crossings are tunnels is genuinely part of the domain and it is cheap to carry.

## 2. vehicle_class and rental_company

### vehicle_class

The rental facing car class, carrying the default MPG for the fuel fallback path and a mapping to the agency toll classification.

Columns.

- `id BIGINT` generated identity, primary key.
- `code TEXT` unique, for example `ECONOMY`, `COMPACT`, `MIDSIZE`, `STANDARD`, `FULLSIZE`, `SUV`, `MINIVAN`, `LUXURY`.
- `name TEXT` not null.
- `toll_class toll_class` not null, the agency classification this rental class tolls as.
- `default_mpg NUMERIC(4,1)` not null, the fallback miles per gallon used when the EPA per model lookup is unavailable, described in the fuel section of the plan.

Why `toll_class` is separate from the rental class. Toll agencies bill by axle count and height, not by rental marketing class. An Economy and a full size SUV are both two axle passenger vehicles and pay the identical passenger toll. If `toll_rate` were keyed by the rental `vehicle_class`, every rate row would be duplicated across eight or more rental classes for no reason. So `toll_rate` and the congestion tables are keyed by `toll_class`, and each rental `vehicle_class` carries the `toll_class` it maps to. For the entire NYC weekend rental space covered here, every rental class maps to `PASSENGER`, so in practice the seed uses a single toll class, but the decoupling is the correct model and it keeps the rate tables compact. The repository rate lookup for a rental class resolves the class to its `toll_class`, then queries `toll_rate` by that toll class, so the Phase 1 acceptance test that fetches a rate for a vehicle class is satisfied through this one hop join.

### rental_company

A small table of the companies whose toll programs are modeled.

Columns.

- `id BIGINT` generated identity, primary key.
- `code TEXT` unique, for example `HERTZ`, `AVIS`, `BUDGET`, `DOLLAR`, `THRIFTY`, `ENTERPRISE`.
- `name TEXT` not null.

The plan does not list a companies CSV, so the loader upserts `rental_company` from the distinct company codes present in `toll_programs.csv`, which carries the company code and display name on every program row. This keeps the seed file list exactly as the plan specifies.

## 3. toll_rate

One row per crossing, payment type, toll class, day mask, local time window, and effective date, with the amount in cents.

Columns.

- `id BIGINT` generated identity, primary key.
- `crossing_id BIGINT` not null, references `toll_crossing(id)`.
- `payment_type payment_type` not null.
- `toll_class toll_class` not null.
- `day_of_week_mask SMALLINT` not null, check between 0 and 127.
- `window_start TIME` not null.
- `window_end TIME` not null, check `window_end > window_start`.
- `amount_cents BIGINT` not null, check greater than or equal to 0.
- `effective_date DATE` not null.

Day of week representation, the decision the plan flags. The plan recommends a day of week bitmask plus start and end local time in `America/New_York`, and this design confirms that recommendation and pins the exact types. The mask is a `SMALLINT` bitmask, not a boolean array and not seven boolean columns, for three reasons. A `SMALLINT` is a single indexable column that maps directly to an `int` bitmask in the Java lookup, so the predicate is one bitwise `AND`. A Postgres boolean array is awkward to query by a specific day and maps poorly across JDBC. Seven boolean columns sprawl the schema and make the same query a seven way `OR`. The bit convention is fixed in the conventions section, bit zero is Monday through bit six is Sunday.

Local time window representation. Windows are half open, `window_start` inclusive and `window_end` exclusive, so the lookup predicate is `localTime >= window_start AND localTime < window_end`. Half open windows make a boundary land in exactly one window, which is the whole point of the peak versus off peak boundary test. A window that runs to end of day uses `window_end = '24:00:00'`, which Postgres accepts as a valid `TIME` value, so a flat all day rate is a single row with `window_start '00:00:00'` and `window_end '24:00:00'`.

Midnight crossing windows, does the edge case arise. The stored rule is that no single window ever crosses midnight. If a real schedule window would wrap past midnight, it is split into two rows, one ending at `24:00:00` and one starting at `00:00:00`, which keeps the lookup predicate a plain `between` with no wrap logic. For the NYC area crossings this project covers, a defining window that crosses midnight does not arise, so the split is a documented mechanism rather than a live requirement.

- The MTA Bridges and Tunnels crossings, the NJ Turnpike Authority, and the NY Thruway charge a flat amount by class with no time of day variation, so each is a single row with mask `127` and the full day window.
- The Port Authority Hudson crossings are the only time varying crossing tolls. Their peak windows are mid day, weekday mornings and weekday late afternoons plus a weekend midday peak, none of which wrap past midnight. The off peak periods are the complement, which is encoded as up to three non overlapping off peak rows per day, for example `00:00` to `06:00`, `10:00` to `16:00`, and `20:00` to `24:00` on a weekday, rather than one wrapping overnight window. So even the complement never needs a midnight crossing row.

The only place an overnight window truly spans midnight is congestion pricing, which lives in its own table and is handled there by the same non wrapping split.

Carpool rates, out of scope note. The Port Authority publishes a reduced NY and NJ E-ZPass carpool rate for registered three plus occupant trips during off peak. It is not modeled in Phase 1. If it is added later it is a new `payment_type` member or a rate row variant, and it does not change the table shape. This is noted so its absence is a deliberate choice, not an oversight.

Data invariant. For any fixed `crossing_id`, `payment_type`, `toll_class`, and `effective_date`, the day mask and time windows across rows must not overlap for a given day and time, so a lookup resolves to exactly one row. This is a seed invariant enforced by a repository test rather than by a database exclusion constraint, since a GiST exclusion constraint over a bitmask and a time range is heavy for a small hand curated table. A unique constraint on `(crossing_id, payment_type, toll_class, day_of_week_mask, window_start, effective_date)` catches accidental exact duplicates cheaply.

## 4. congestion_schedule and congestion_credit

The MTA Congestion Relief Zone rule set has two grains, a period based charge that depends on class, payment type, day, and time, and a per tunnel credit that only applies to the four tunnels entering the zone during peak. Two grains means two tables, which avoids a wide sparse single table full of nulls.

### congestion_schedule

The period based congestion charge. It reuses the same day mask plus local time window shape as `toll_rate`, so the Phase 2 congestion detection reuses the identical find the row whose day and time window contains the arrival time logic.

Columns.

- `id BIGINT` generated identity, primary key.
- `period congestion_period` not null. `PEAK` or `OVERNIGHT`. Stored explicitly because the credit rule only applies during peak, so the engine reads the period rather than reinferring it from the time.
- `toll_class toll_class` not null.
- `payment_type payment_type` not null.
- `day_of_week_mask SMALLINT` not null, check between 0 and 127.
- `window_start TIME` not null.
- `window_end TIME` not null, check `window_end > window_start`.
- `amount_cents BIGINT` not null, check greater than or equal to 0.
- `once_per_day BOOLEAN` not null default true, the once per day charge flag. It is `TRUE` for the Congestion Relief Zone, modeled as data rather than hardcoded so the once per day rule is visible in the seed and can be varied for the thesis.
- `effective_date DATE` not null.

How the real rule set maps. Peak is weekdays 5am to 9pm and weekends 9am to 9pm, overnight is all other times, so the schedule is a small set of rows per class and payment type.

- Peak weekday, mask `31`, window `05:00` to `21:00`, period `PEAK`.
- Peak weekend, mask `96`, window `09:00` to `21:00`, period `PEAK`.
- Overnight weekday, two rows, mask `31`, windows `00:00` to `05:00` and `21:00` to `24:00`, period `OVERNIGHT`.
- Overnight weekend, two rows, mask `96`, windows `00:00` to `09:00` and `21:00` to `24:00`, period `OVERNIGHT`.

The passenger vehicle amounts from the published schedule are the E-ZPass peak nine dollars and E-ZPass overnight two dollars twenty five cents, with higher Tolls by Mail amounts, all carried as separate rows by `payment_type`. The overnight windows are split at midnight exactly as the toll_rate section describes, so no window wraps. The once per day charge is enforced by the engine, which dedups the congestion charge to one occurrence per calendar day in `America/New_York` per trip, reading `once_per_day` from the matched row.

### congestion_credit

The crossing credit for the four tunnels that enter the zone during peak.

Columns.

- `id BIGINT` generated identity, primary key.
- `crossing_id BIGINT` not null, references `toll_crossing(id)`. One of the four tunnels, Lincoln, Holland, Hugh Carey, or Queens Midtown.
- `toll_class toll_class` not null.
- `payment_type payment_type` not null.
- `applies_period congestion_period` not null default `PEAK`. The credit only applies when entering the zone during peak, so this is `PEAK` for every seeded row, kept as a column so the peak only rule is data rather than code.
- `credit_cents BIGINT` not null, check greater than or equal to 0.
- `effective_date DATE` not null.

How the real rule set maps. Entering the congestion zone through one of the four tunnels during peak reduces the congestion charge by the tunnel credit, and the reduced charge is floored at zero, so the engine applies `max(charge - credit, 0)`. The credit is not given during overnight and it does not stack beyond the single once per day charge. The passenger vehicle E-ZPass peak credits from the published schedule are larger for the two Hudson tunnels, Lincoln and Holland, and smaller for Hugh Carey and Queens Midtown, and each is a row keyed by crossing, toll class, and payment type. Exact cent amounts are verified at seed time and recorded, and the thesis records the snapshot date.

Why a separate credit table rather than columns on the schedule. The credit is per crossing, a grain the period schedule does not have, so folding it into `congestion_schedule` would need a nullable crossing column and would repeat the schedule rows once per tunnel. A child table keyed by crossing is the normal form and it keeps both tables clean.

## 5. toll_program

The rental company toll program terms. The design goal the plan sets is that a single flat table expresses all three program shapes, usage day fee, all rental days fee, and unlimited flat daily, without a type specific column explosion, and that the engine reads the behavior from data rather than switching on the program name.

Columns.

- `id BIGINT` generated identity, primary key.
- `company_id BIGINT` not null, references `rental_company(id)`.
- `program_name TEXT` not null, the marketed plan name, for example `PlatePass`, `e-Toll`, `e-Toll Unlimited`.
- `program_type program_type` not null, the canonical shape, `USAGE_DAY`, `ALL_RENTAL_DAYS`, or `UNLIMITED_DAILY`. This is the human facing categorization the plan and thesis reference.
- `daily_fee_cents BIGINT` not null, check greater than or equal to 0, the per day fee.
- `fee_day_basis fee_day_basis` not null, what `daily_fee_cents` multiplies, `USAGE_DAYS` or `RENTAL_DAYS`.
- `fee_requires_usage BOOLEAN` not null, whether the fee is only charged when at least one toll is incurred.
- `cap_cents BIGINT` null, the maximum total program fee per rental, null meaning no cap. Check greater than or equal to 0 when present.
- `tolls_included BOOLEAN` not null, whether actual toll charges are covered by the fee rather than passed through in addition to it.
- `toll_rate_basis toll_rate_basis` null, for a pass through program the rate the company bills each toll at, `EZPASS_RATE` or `MAX_CASH_RATE`. Null when `tolls_included` is true.
- `covers_congestion BOOLEAN` not null, whether the program covers the congestion charge.
- `effective_date DATE` not null.

How the three shapes map onto the behavioral columns, no type specific columns needed.

- Usage day fee, the Hertz PlatePass shape. `program_type USAGE_DAY`, `fee_day_basis USAGE_DAYS`, `fee_requires_usage true`, a per rental `cap_cents`, `tolls_included false`, `toll_rate_basis MAX_CASH_RATE`. The fee is charged only on days a toll is incurred, capped, and the actual tolls are billed at the company cash rate.
- All rental days fee, the Avis and Budget e-Toll shape. `program_type ALL_RENTAL_DAYS`, `fee_day_basis RENTAL_DAYS`, `fee_requires_usage true`, a per rental `cap_cents`, `tolls_included false`, `toll_rate_basis EZPASS_RATE`. Once any toll is incurred the fee applies to every rental day, capped, and the actual tolls are billed at the E-ZPass rate.
- Unlimited flat daily, the Dollar and Thrifty all inclusive shape and the Avis e-Toll Unlimited shape. `program_type UNLIMITED_DAILY`, `fee_day_basis RENTAL_DAYS`, `fee_requires_usage false`, `cap_cents` usually null, `tolls_included true`, `toll_rate_basis` null, and `covers_congestion` typically true for these plans, verified at seed time. The fee is charged every rental day regardless of usage and all tolls are included.

The engine computes each program without a name switch. The fee is `daily_fee_cents` times the day count chosen by `fee_day_basis`, being the usage day estimate or the rental day count, gated to zero when `fee_requires_usage` is true and no toll is incurred, then capped at `cap_cents` when present. The toll pass through is zero when `tolls_included` is true, otherwise the sum of the route tolls priced at `toll_rate_basis`. The congestion component is zero when `covers_congestion` is true, otherwise the computed congestion charge. `program_type` remains as the label and as a validation guard, and the loader or a CHECK should assert the behavioral columns are consistent with it, for example `UNLIMITED_DAILY` implies `tolls_included` true and `toll_rate_basis` null.

Cross check against the toll strategy engine design. As of writing, `docs/design/toll-strategy-engine.md` does not exist, since that document is being produced concurrently by a separate agent. This table is therefore designed defensively from the plan description of the three program shapes. When the engine document lands, the Phase 3 implementation must reconcile these column names and semantics with the engine input contract, in particular the names `fee_day_basis`, `fee_requires_usage`, `tolls_included`, `toll_rate_basis`, and `covers_congestion`, and the usage day versus rental day distinction. If the engine names a field differently, prefer aligning this table to the engine so the persistence to engine mapping stays a direct field copy. This reconciliation is a Phase 3 checkpoint and is noted here so it is not lost.

## 6. quote_snapshot

Empty in Phase 1 and filled in Phase 6 by the Spring Kafka listener that consumes the `quote-snapshots` topic and batch inserts rows. The table aligns field for field with the `QuoteSnapshot` Avro record in `docs/design/kafka-avro-design.md` and `src/main/avro/QuoteSnapshot.avsc`, so the sink is a direct field copy with no translation.

Field for field mapping. Every field in the Avro record maps to exactly one column and no Avro field is dropped.

| Avro field | Avro type | Column | Postgres type |
| --- | --- | --- | --- |
| snapshotId | string uuid | snapshot_id | UUID primary key |
| routeKey | string | route_key | TEXT not null |
| originLat | double | origin_lat | DOUBLE PRECISION not null |
| originLng | double | origin_lng | DOUBLE PRECISION not null |
| destLat | double | dest_lat | DOUBLE PRECISION not null |
| destLng | double | dest_lng | DOUBLE PRECISION not null |
| company | string | company | TEXT not null |
| carClass | string | car_class | TEXT not null |
| provider | string | provider | TEXT not null |
| baseRateCents | long | base_rate_cents | BIGINT not null |
| taxesAndFeesCents | long | taxes_and_fees_cents | BIGINT not null |
| totalRentalCents | long | total_rental_cents | BIGINT not null |
| pickupAt | long timestamp-millis | pickup_at | TIMESTAMPTZ not null |
| returnAt | long timestamp-millis | return_at | TIMESTAMPTZ not null |
| rentalDays | int | rental_days | INTEGER not null |
| stale | boolean default false | stale | BOOLEAN not null default false |
| fetchedAt | long timestamp-millis | fetched_at | TIMESTAMPTZ not null |

Notes on the mapping.

- The uuid logical type maps to native `UUID`, the long cents fields map to `BIGINT`, the timestamp-millis instants map to `TIMESTAMPTZ` since they are true instants and UTC on the wire, the doubles stay `DOUBLE PRECISION` to match the Avro coordinate doubles exactly rather than the exact `NUMERIC` used for fixed crossing points, `rentalDays` is `INTEGER` matching Avro `int`, and `stale` keeps the Avro default of false.
- `car_class` is stored as free `TEXT` and is intentionally not a foreign key to `vehicle_class`. A snapshot is a raw fetched quote captured for accuracy analysis, and a live connector might return a class label that is not one of the modeled rental classes, so constraining it would risk rejecting a valid snapshot at the sink. Analysis joins by string when needed.
- There are no Avro fields without a column equivalent. Every one of the seventeen fields has a home.
- One column exists that is not in the Avro record, `ingested_at TIMESTAMPTZ not null default now()`. It is a database only bookkeeping column recording when the sink wrote the row, useful for debugging sink lag against `fetched_at`. It is explicitly not part of the event and it carries a default so the sink insert never has to supply it. It is called out here so the extra column is a known, deliberate addition rather than a silent divergence from the schema.

Indexes for the analysis queries the thesis runs, `route_key` for joining a route back to its snapshots, `company` for per company breakdowns, and `fetched_at` for time range scans.

## 7. Seed CSV to table mapping

Confirming the plan repository layout, the Phase 1 seed files under `data/seeds/` are `crossings.csv`, `toll_rates.csv`, `congestion.csv`, `toll_programs.csv`, and `vehicle_classes.csv`. The sixth file listed in the layout, `rental_calibration.csv`, is Phase 4 synthetic provider calibration data and is not reference data, so it is out of scope for Phase 1 and is not loaded here. `quote_snapshot` has no seed file, it is empty until Phase 6. The exact column headers each loader should expect are below, so the Phase 1 implementation agent writes the loader without guessing.

The loader is either `scripts/seed.sh` or a Flyway repeatable migration, as the plan allows. Whichever is chosen, the CSVs are the source and the headers are fixed as follows.

### crossings.csv, loads toll_crossing

Header, `code,name,agency,crossing_type,latitude,longitude,travel_bearing_deg,tolled_directions`.

- `agency` is one of `PANYNJ`, `MTABT`, `NJTA`, `NYSTA`.
- `crossing_type` is one of `BRIDGE`, `TUNNEL`, `ROADWAY_SEGMENT`.
- `travel_bearing_deg` is an integer 0 to 359, the compass bearing of the tolled direction of travel.
- `tolled_directions` is `FORWARD` or `BOTH`.
- Example row, `GWB,George Washington Bridge,PANYNJ,BRIDGE,40.851700,-73.952000,100,FORWARD`.

### toll_rates.csv, loads toll_rate

Header, `crossing_code,payment_type,toll_class,day_of_week_mask,window_start,window_end,amount_cents,effective_date`.

- `crossing_code` matches a `crossings.csv` `code`, the loader resolves it to `crossing_id`.
- `payment_type` is `EZPASS`, `TOLLS_BY_MAIL`, or `CASH`.
- `toll_class` is `PASSENGER`, `MOTORCYCLE`, or `TWO_AXLE`, and is `PASSENGER` for the covered rental space.
- `day_of_week_mask` is a seven character Monday first binary string, leftmost character Monday, for readability during hand verification. Weekdays is `1111100`, weekend is `0000011`, every day is `1111111`. The loader converts this string to the `SMALLINT` bitmask with Monday as bit zero, so `1111100` becomes `31`. This keeps the curated CSV human checkable against the agency page while the stored value stays a compact bitmask.
- `window_start` and `window_end` are `HH:MM` twenty four hour local time, and `window_end` may be `24:00` for end of day.
- Example rows for a Port Authority peak versus off peak weekday morning, `GWB,EZPASS,PASSENGER,1111100,06:00,10:00,1550,2025-01-05` and `GWB,EZPASS,PASSENGER,1111100,00:00,06:00,1350,2025-01-05`.

### congestion.csv, loads congestion_schedule and congestion_credit

The plan lists a single congestion file, so this one file carries both grains distinguished by a `record_type` column, and the loader routes `SCHEDULE` rows to `congestion_schedule` and `CREDIT` rows to `congestion_credit`. The header is the superset of both.

Header, `record_type,toll_class,payment_type,period,day_of_week_mask,window_start,window_end,amount_cents,once_per_day,crossing_code,credit_cents,effective_date`.

- For a `SCHEDULE` row, fill `toll_class,payment_type,period,day_of_week_mask,window_start,window_end,amount_cents,once_per_day,effective_date` and leave `crossing_code` and `credit_cents` empty.
- For a `CREDIT` row, fill `toll_class,payment_type,period,crossing_code,credit_cents,effective_date` and leave `day_of_week_mask,window_start,window_end,amount_cents,once_per_day` empty. For credit rows `period` is `PEAK`.
- `period` is `PEAK` or `OVERNIGHT`. `day_of_week_mask` follows the same Monday first binary string convention as `toll_rates.csv`. `once_per_day` is `true` or `false`.
- Example schedule row, `SCHEDULE,PASSENGER,EZPASS,PEAK,1111100,05:00,21:00,900,true,,,2025-06-01`.
- Example credit row, `CREDIT,PASSENGER,EZPASS,PEAK,,,,,,LINCOLN,300,2025-06-01`.

If the wide sparse single file proves unpleasant to maintain, an equivalent split into `congestion.csv` for schedule rows and a companion credits file is acceptable, but the single file with `record_type` is the default so the plan file list is honored exactly.

### toll_programs.csv, loads rental_company and toll_program

Header, `company_code,company_name,program_name,program_type,daily_fee_cents,fee_day_basis,fee_requires_usage,cap_cents,tolls_included,toll_rate_basis,covers_congestion,effective_date`.

- The loader upserts `rental_company` from the distinct `company_code` and `company_name` pairs, then inserts the program rows.
- `program_type` is `USAGE_DAY`, `ALL_RENTAL_DAYS`, or `UNLIMITED_DAILY`.
- `fee_day_basis` is `USAGE_DAYS` or `RENTAL_DAYS`.
- `fee_requires_usage`, `tolls_included`, and `covers_congestion` are `true` or `false`.
- `cap_cents` empty means no cap. `toll_rate_basis` is `EZPASS_RATE` or `MAX_CASH_RATE`, and is empty for an unlimited program.
- Example usage day row, `HERTZ,Hertz,PlatePass,USAGE_DAY,699,USAGE_DAYS,true,,false,MAX_CASH_RATE,false,2025-01-01`.
- Example all rental days row, `AVIS,Avis,e-Toll,ALL_RENTAL_DAYS,599,RENTAL_DAYS,true,3595,false,EZPASS_RATE,false,2025-01-01`.
- Example unlimited row, `DOLLAR,Dollar,All-Inclusive Tolls,UNLIMITED_DAILY,1399,RENTAL_DAYS,false,,true,,true,2025-01-01`.

The plan acceptance criterion needs at least six programs across five companies, so the seed carries programs for at least Hertz, Avis, Budget, Dollar, and Thrifty, with numbers verified at seed time against the current company policy pages and the snapshot date recorded.

### vehicle_classes.csv, loads vehicle_class

Header, `code,name,toll_class,default_mpg`.

- `toll_class` is the agency classification, `PASSENGER` for every standard rental class here.
- `default_mpg` is a decimal, the fuel fallback value.
- Example row, `MIDSIZE,Midsize,PASSENGER,30.5`.

## 8. Repository test scenarios

The Phase 1 acceptance criterion requires a repository test that fetches the correct rate for crossing, vehicle class, payment type, and timestamp, including a peak versus off peak boundary case. This section gives concrete worked fixtures so the implementer builds against real values rather than an abstract description. The rate amounts below are illustrative fixture values that the seed must reproduce, and they are what the test asserts. The actual seeded numbers are verified against the agency page at seed time, and once seeded these fixtures are updated to the verified values so the test stays a check on the lookup logic, not on stale numbers.

### The lookup contract

Given a crossing code, a vehicle class, a payment type, and a trip timestamp, the repository resolves the vehicle class to its `toll_class`, converts the trip timestamp to `America/New_York` to obtain the local date, the ISO day of week, and the local time, then selects the single `toll_rate` row where the crossing and toll class and payment type match, the effective date is less than or equal to the local date, the day of week bit is set in the mask, and the local time falls in the half open window, ordered by effective date descending, taking the first. The reference SQL is in the appendix.

### Scenario A, peak versus off peak boundary at the George Washington Bridge

Fixture. Crossing `GWB`, George Washington Bridge, Port Authority. Vehicle class `MIDSIZE`, which maps to `toll_class PASSENGER`. Payment type `EZPASS`. Seeded rates effective `2025-01-05`, weekday morning peak window `06:00` to `10:00` at `1550` cents, weekday overnight and early morning off peak window `00:00` to `06:00` at `1350` cents.

The boundary is `06:00:00` local on a weekday, and the windows are half open so `06:00:00` belongs to the peak window.

- Just before the boundary. Trip timestamp `2026-01-07T05:59:00` in `America/New_York`, which is a Wednesday. Local time `05:59:00` falls in the `00:00` to `06:00` off peak window. Expected rate `1350` cents.
- At the boundary. Trip timestamp `2026-01-07T06:00:00` in `America/New_York`, same Wednesday. Local time `06:00:00` falls in the `06:00` to `10:00` peak window. Expected rate `1550` cents.

The evening peak boundary gives the mirror case with the same fixture extended by a `16:00` to `20:00` peak row and a `20:00` to `24:00` off peak row.

- Trip timestamp `2026-01-07T19:59:00`, expected peak `1550` cents.
- Trip timestamp `2026-01-07T20:00:00`, expected off peak `1350` cents.

January is chosen deliberately so the fixture is nowhere near a daylight saving transition, which keeps the local time interpretation unambiguous. A separate note for the implementer, the lookup must interpret the trip instant in `America/New_York` before extracting the local time and day, since the stored windows are wall clock, and the boundary cases above only hold under that interpretation.

### Scenario B, effective date supersession

Fixture. The same `GWB`, `PASSENGER`, `EZPASS` peak window exists at two effective dates, `1550` cents effective `2025-01-05` and a superseding `1650` cents effective `2026-01-01`. History is never overwritten, both rows coexist.

- A trip on `2025-07-01` at a peak local time selects the `2025-01-05` row and expects `1550` cents.
- A trip on `2026-03-01` at a peak local time selects the `2026-01-01` row and expects `1650` cents, because the lookup orders by effective date descending and both effective dates are less than or equal to the trip date.

This proves the supersession semantics and that no `end_date` column is needed.

### Scenario C, flat crossing and toll class resolution

Fixture. Crossing `RFK` or another MTA Bridges and Tunnels crossing seeded as a single flat row, mask `1111111`, window `00:00` to `24:00`, `EZPASS`, `PASSENGER`, a fixed amount. Vehicle class `SUV`, which also maps to `toll_class PASSENGER`.

- A trip at any timestamp on any day returns the single flat amount, confirming the full day window and the every day mask.
- Querying with vehicle class `SUV` returns the same amount as querying with `MIDSIZE`, confirming that the rate is keyed by `toll_class` and both rental classes resolve to `PASSENGER`, which is the join the acceptance test exercises when it fetches by vehicle class.

## Appendix, draft SQL

This is a strong starting point for the Phase 1 Flyway migration. It is draft SQL, not run against any database, and the Phase 1 agent adapts identity syntax, constraint names, and index choices as the implementation requires. Semicolons here are mandatory SQL statement terminators and are exempt from the prose style rule.

```sql
CREATE TYPE toll_agency AS ENUM ('PANYNJ', 'MTABT', 'NJTA', 'NYSTA');
CREATE TYPE crossing_type AS ENUM ('BRIDGE', 'TUNNEL', 'ROADWAY_SEGMENT');
CREATE TYPE tolled_direction AS ENUM ('FORWARD', 'BOTH');
CREATE TYPE payment_type AS ENUM ('EZPASS', 'TOLLS_BY_MAIL', 'CASH');
CREATE TYPE toll_class AS ENUM ('PASSENGER', 'MOTORCYCLE', 'TWO_AXLE');
CREATE TYPE congestion_period AS ENUM ('PEAK', 'OVERNIGHT');
CREATE TYPE program_type AS ENUM ('USAGE_DAY', 'ALL_RENTAL_DAYS', 'UNLIMITED_DAILY');
CREATE TYPE fee_day_basis AS ENUM ('USAGE_DAYS', 'RENTAL_DAYS');
CREATE TYPE toll_rate_basis AS ENUM ('EZPASS_RATE', 'MAX_CASH_RATE');

CREATE TABLE toll_crossing (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code               TEXT NOT NULL UNIQUE,
    name               TEXT NOT NULL,
    agency             toll_agency NOT NULL,
    crossing_type      crossing_type NOT NULL,
    latitude           NUMERIC(9,6) NOT NULL,
    longitude          NUMERIC(9,6) NOT NULL,
    travel_bearing_deg SMALLINT NOT NULL CHECK (travel_bearing_deg BETWEEN 0 AND 359),
    tolled_directions  tolled_direction NOT NULL
);

CREATE TABLE vehicle_class (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    toll_class  toll_class NOT NULL,
    default_mpg NUMERIC(4,1) NOT NULL CHECK (default_mpg > 0)
);

CREATE TABLE rental_company (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL
);

CREATE TABLE toll_rate (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    crossing_id      BIGINT NOT NULL REFERENCES toll_crossing (id),
    payment_type     payment_type NOT NULL,
    toll_class       toll_class NOT NULL,
    day_of_week_mask SMALLINT NOT NULL CHECK (day_of_week_mask BETWEEN 0 AND 127),
    window_start     TIME NOT NULL,
    window_end       TIME NOT NULL,
    amount_cents     BIGINT NOT NULL CHECK (amount_cents >= 0),
    effective_date   DATE NOT NULL,
    CONSTRAINT toll_rate_window_ck CHECK (window_end > window_start),
    CONSTRAINT toll_rate_unique_row UNIQUE
        (crossing_id, payment_type, toll_class, day_of_week_mask, window_start, effective_date)
);

CREATE INDEX toll_rate_lookup_idx
    ON toll_rate (crossing_id, toll_class, payment_type, effective_date DESC);

CREATE TABLE congestion_schedule (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    period           congestion_period NOT NULL,
    toll_class       toll_class NOT NULL,
    payment_type     payment_type NOT NULL,
    day_of_week_mask SMALLINT NOT NULL CHECK (day_of_week_mask BETWEEN 0 AND 127),
    window_start     TIME NOT NULL,
    window_end       TIME NOT NULL,
    amount_cents     BIGINT NOT NULL CHECK (amount_cents >= 0),
    once_per_day     BOOLEAN NOT NULL DEFAULT TRUE,
    effective_date   DATE NOT NULL,
    CONSTRAINT congestion_schedule_window_ck CHECK (window_end > window_start)
);

CREATE INDEX congestion_schedule_lookup_idx
    ON congestion_schedule (toll_class, payment_type, effective_date DESC);

CREATE TABLE congestion_credit (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    crossing_id    BIGINT NOT NULL REFERENCES toll_crossing (id),
    toll_class     toll_class NOT NULL,
    payment_type   payment_type NOT NULL,
    applies_period congestion_period NOT NULL DEFAULT 'PEAK',
    credit_cents   BIGINT NOT NULL CHECK (credit_cents >= 0),
    effective_date DATE NOT NULL
);

CREATE INDEX congestion_credit_lookup_idx
    ON congestion_credit (crossing_id, toll_class, payment_type, effective_date DESC);

CREATE TABLE toll_program (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id         BIGINT NOT NULL REFERENCES rental_company (id),
    program_name       TEXT NOT NULL,
    program_type       program_type NOT NULL,
    daily_fee_cents    BIGINT NOT NULL CHECK (daily_fee_cents >= 0),
    fee_day_basis      fee_day_basis NOT NULL,
    fee_requires_usage BOOLEAN NOT NULL,
    cap_cents          BIGINT CHECK (cap_cents >= 0),
    tolls_included     BOOLEAN NOT NULL,
    toll_rate_basis    toll_rate_basis,
    covers_congestion  BOOLEAN NOT NULL,
    effective_date     DATE NOT NULL,
    CONSTRAINT toll_program_unlimited_ck CHECK (
        program_type <> 'UNLIMITED_DAILY'
        OR (tolls_included = TRUE AND toll_rate_basis IS NULL)
    ),
    CONSTRAINT toll_program_passthrough_ck CHECK (
        tolls_included = TRUE
        OR toll_rate_basis IS NOT NULL
    )
);

CREATE INDEX toll_program_lookup_idx
    ON toll_program (company_id, effective_date DESC);

CREATE TABLE quote_snapshot (
    snapshot_id          UUID PRIMARY KEY,
    route_key            TEXT NOT NULL,
    origin_lat           DOUBLE PRECISION NOT NULL,
    origin_lng           DOUBLE PRECISION NOT NULL,
    dest_lat             DOUBLE PRECISION NOT NULL,
    dest_lng             DOUBLE PRECISION NOT NULL,
    company              TEXT NOT NULL,
    car_class            TEXT NOT NULL,
    provider             TEXT NOT NULL,
    base_rate_cents      BIGINT NOT NULL,
    taxes_and_fees_cents BIGINT NOT NULL,
    total_rental_cents   BIGINT NOT NULL,
    pickup_at            TIMESTAMPTZ NOT NULL,
    return_at            TIMESTAMPTZ NOT NULL,
    rental_days          INTEGER NOT NULL,
    stale                BOOLEAN NOT NULL DEFAULT FALSE,
    fetched_at           TIMESTAMPTZ NOT NULL,
    ingested_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX quote_snapshot_route_idx   ON quote_snapshot (route_key);
CREATE INDEX quote_snapshot_company_idx ON quote_snapshot (company);
CREATE INDEX quote_snapshot_fetched_idx ON quote_snapshot (fetched_at);
```

Reference lookup query for the repository rate fetch, the query the Phase 1 acceptance test drives. Parameters are the crossing code, toll class, payment type, the trip local date, the ISO day of week one through seven, and the trip local time, all derived by converting the trip instant to America/New_York first.

```sql
SELECT tr.amount_cents
FROM toll_rate tr
JOIN toll_crossing tc ON tc.id = tr.crossing_id
WHERE tc.code = :crossingCode
  AND tr.toll_class = :tollClass
  AND tr.payment_type = :paymentType
  AND tr.effective_date <= :tripLocalDate
  AND (tr.day_of_week_mask & (1 << (:isoDayOfWeek - 1))) <> 0
  AND :tripLocalTime >= tr.window_start
  AND :tripLocalTime <  tr.window_end
ORDER BY tr.effective_date DESC
LIMIT 1;
```
