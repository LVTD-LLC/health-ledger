-- Enumerate recorded source/name/unit combinations and load only the selected series.
CREATE INDEX health_chart_sources ON health_records
    (user_id, title, unit, source, occurred_at DESC, id DESC)
    WHERE kind IN ('Measurement','Lab','Sleep','Nutrition')
      AND value ~ '^[+-]?[0-9]+([.][0-9]+)?$';
