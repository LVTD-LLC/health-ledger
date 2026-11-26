-- One index seek per distinct triple, even for an account with years of samples.
WITH RECURSIVE sources AS (
    (SELECT title, unit, source FROM health_records
     WHERE user_id = $1 AND kind IN ('Measurement','Lab','Sleep','Nutrition')
       AND value ~ '^[+-]?[0-9]+([.][0-9]+)?$'
     ORDER BY title, unit, source LIMIT 1)
    UNION ALL
    SELECT next.* FROM sources prev
    CROSS JOIN LATERAL (
        SELECT title, unit, source FROM health_records
        WHERE user_id = $1 AND kind IN ('Measurement','Lab','Sleep','Nutrition')
          AND value ~ '^[+-]?[0-9]+([.][0-9]+)?$'
          AND (title, unit, source) > (prev.title, prev.unit, prev.source)
        ORDER BY title, unit, source LIMIT 1
    ) next
)
SELECT title AS "title!", unit AS "unit!", source AS "source!" FROM sources
