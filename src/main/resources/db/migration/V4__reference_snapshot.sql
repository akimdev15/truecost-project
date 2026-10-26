create table reference_snapshot (
    singleton     boolean primary key default true check (singleton),
    snapshot_date date not null
);
