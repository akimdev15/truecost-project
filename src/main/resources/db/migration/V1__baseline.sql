-- Trivial baseline proving Flyway runs against Postgres on startup.
-- Domain tables arrive in Phase 1.
create table schema_baseline (
    id integer primary key,
    established_at timestamptz not null default now()
);

insert into schema_baseline (id) values (1);
