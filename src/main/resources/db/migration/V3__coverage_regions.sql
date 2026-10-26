-- Rectangles whose union is the area where every tolled facility a route could use is seeded.
-- Loaded from data/seeds/coverage_regions.csv by the seed loader, like the other reference tables.

create table coverage_region (
    id      bigint generated always as identity primary key,
    code    text not null unique,
    name    text not null,
    min_lat numeric(9,6) not null check (min_lat between -90 and 90),
    min_lng numeric(9,6) not null check (min_lng between -180 and 180),
    max_lat numeric(9,6) not null check (max_lat between -90 and 90),
    max_lng numeric(9,6) not null check (max_lng between -180 and 180),
    constraint coverage_region_bounds_ck check (max_lat > min_lat and max_lng > min_lng)
);
