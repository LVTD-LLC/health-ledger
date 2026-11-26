SELECT p.occurred_at AS "occurred_at!", (p.value::double precision / a.divisor) AS "value!"
FROM unnest($2::text[], $3::text[], $4::text[], $5::integer[]) a(title, unit, source, divisor)
CROSS JOIN LATERAL (
    SELECT occurred_at, id, value FROM health_records r
    WHERE user_id = $1 AND r.title = a.title AND r.unit = a.unit AND r.source = a.source
      AND kind IN ('Measurement','Lab','Sleep','Nutrition')
      AND value ~ '^[+-]?[0-9]+([.][0-9]+)?$'
    ORDER BY occurred_at DESC, id DESC LIMIT 90
) p
ORDER BY p.occurred_at DESC, p.id DESC LIMIT 90
