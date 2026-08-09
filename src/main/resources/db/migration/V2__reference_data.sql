-- Phase 1 reference data schema, adapted from docs/design/reference-data-schema.md.
-- Tables here hold toll crossings, toll rates, congestion pricing, and rental
-- company toll programs, all versioned by effective_date rather than mutated in place.

create type toll_agency as enum ('PANYNJ', 'MTABT', 'NJTA', 'NYSTA');
create type crossing_type as enum ('BRIDGE', 'TUNNEL', 'ROADWAY_SEGMENT');
create type tolled_direction as enum ('FORWARD', 'BOTH');
create type payment_type as enum ('EZPASS', 'TOLLS_BY_MAIL', 'CASH');
create type toll_class as enum ('PASSENGER', 'MOTORCYCLE', 'TWO_AXLE');
create type congestion_period as enum ('PEAK', 'OVERNIGHT');
create type program_type as enum ('USAGE_DAY', 'ALL_RENTAL_DAYS', 'UNLIMITED_DAILY');
create type fee_day_basis as enum ('USAGE_DAYS', 'RENTAL_DAYS');
create type toll_rate_basis as enum ('EZPASS_RATE', 'MAX_CASH_RATE');

create table toll_crossing (
    id                 bigint generated always as identity primary key,
    code               text not null unique,
    name               text not null,
    agency             toll_agency not null,
    crossing_type      crossing_type not null,
    latitude           numeric(9,6) not null,
    longitude          numeric(9,6) not null,
    travel_bearing_deg smallint not null check (travel_bearing_deg between 0 and 359),
    tolled_directions  tolled_direction not null
);

create table vehicle_class (
    id          bigint generated always as identity primary key,
    code        text not null unique,
    name        text not null,
    toll_class  toll_class not null,
    default_mpg numeric(4,1) not null check (default_mpg > 0)
);

create table rental_company (
    id   bigint generated always as identity primary key,
    code text not null unique,
    name text not null
);

create table toll_rate (
    id               bigint generated always as identity primary key,
    crossing_id      bigint not null references toll_crossing (id),
    payment_type     payment_type not null,
    toll_class       toll_class not null,
    day_of_week_mask smallint not null check (day_of_week_mask between 0 and 127),
    window_start     time not null,
    window_end       time not null,
    amount_cents     bigint not null check (amount_cents >= 0),
    effective_date   date not null,
    constraint toll_rate_window_ck check (window_end > window_start),
    constraint toll_rate_unique_row unique
        (crossing_id, payment_type, toll_class, day_of_week_mask, window_start, effective_date)
);

create index toll_rate_lookup_idx
    on toll_rate (crossing_id, toll_class, payment_type, effective_date desc);

create table congestion_schedule (
    id               bigint generated always as identity primary key,
    period           congestion_period not null,
    toll_class       toll_class not null,
    payment_type     payment_type not null,
    day_of_week_mask smallint not null check (day_of_week_mask between 0 and 127),
    window_start     time not null,
    window_end       time not null,
    amount_cents     bigint not null check (amount_cents >= 0),
    once_per_day     boolean not null default true,
    effective_date   date not null,
    constraint congestion_schedule_window_ck check (window_end > window_start)
);

create index congestion_schedule_lookup_idx
    on congestion_schedule (toll_class, payment_type, effective_date desc);

create table congestion_credit (
    id             bigint generated always as identity primary key,
    crossing_id    bigint not null references toll_crossing (id),
    toll_class     toll_class not null,
    payment_type   payment_type not null,
    applies_period congestion_period not null default 'PEAK',
    credit_cents   bigint not null check (credit_cents >= 0),
    effective_date date not null
);

create index congestion_credit_lookup_idx
    on congestion_credit (crossing_id, toll_class, payment_type, effective_date desc);

create table toll_program (
    id                 bigint generated always as identity primary key,
    company_id         bigint not null references rental_company (id),
    program_name       text not null,
    program_type       program_type not null,
    daily_fee_cents    bigint not null check (daily_fee_cents >= 0),
    fee_day_basis      fee_day_basis not null,
    fee_requires_usage boolean not null,
    cap_cents          bigint check (cap_cents >= 0),
    tolls_included     boolean not null,
    toll_rate_basis    toll_rate_basis,
    covers_congestion  boolean not null,
    effective_date     date not null,
    constraint toll_program_unlimited_ck check (
        program_type <> 'UNLIMITED_DAILY'
        or (tolls_included = true and toll_rate_basis is null)
    ),
    constraint toll_program_passthrough_ck check (
        tolls_included = true
        or toll_rate_basis is not null
    )
);

create index toll_program_lookup_idx
    on toll_program (company_id, effective_date desc);

create table quote_snapshot (
    snapshot_id          uuid primary key,
    route_key            text not null,
    origin_lat           double precision not null,
    origin_lng           double precision not null,
    dest_lat             double precision not null,
    dest_lng             double precision not null,
    company              text not null,
    car_class            text not null,
    provider             text not null,
    base_rate_cents      bigint not null,
    taxes_and_fees_cents bigint not null,
    total_rental_cents   bigint not null,
    pickup_at            timestamptz not null,
    return_at            timestamptz not null,
    rental_days          integer not null,
    stale                boolean not null default false,
    fetched_at           timestamptz not null,
    ingested_at          timestamptz not null default now()
);

create index quote_snapshot_route_idx on quote_snapshot (route_key);
create index quote_snapshot_company_idx on quote_snapshot (company);
create index quote_snapshot_fetched_idx on quote_snapshot (fetched_at);
