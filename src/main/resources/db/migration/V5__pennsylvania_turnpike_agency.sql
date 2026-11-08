-- The Philadelphia route crosses the PA Turnpike Delaware River Bridge, so the commission joins the
-- agencies whose crossings can be seeded. A new enum value cannot be used in the transaction that
-- adds it, which is why the seed rows load later from the CSVs rather than here.
alter type toll_agency add value if not exists 'PTC';
