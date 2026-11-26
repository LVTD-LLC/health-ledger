WITH readings AS (
    SELECT r.occurred_at, r.value::numeric / a.divisor AS n
    FROM unnest($2::text[], $3::text[], $4::text[], $5::integer[]) a(title, unit, source, divisor)
    JOIN health_records r ON r.title = a.title AND r.unit = a.unit AND r.source = a.source
    WHERE r.user_id = $1 AND r.kind IN ('Measurement','Lab','Sleep','Nutrition')
      AND r.value ~ '^[+-]?[0-9]+([.][0-9]+)?$'
      AND ($7::timestamptz IS NULL OR r.occurred_at >= $7) AND r.occurred_at < $8
)
SELECT date_trunc($6, occurred_at AT TIME ZONE 'UTC')::date AS "day!",
       count(*) AS "count!", avg(n)::text AS "mean!",
       trim_scale(min(n))::text AS "low!", trim_scale(max(n))::text AS "high!"
FROM readings GROUP BY 1 ORDER BY 1
