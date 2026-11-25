CREATE TABLE health_regimens (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
 name text NOT NULL,
 dose text NOT NULL,
 schedule text NOT NULL,
 starts date NOT NULL,
 ends date CHECK(ends >= starts)
);
CREATE INDEX health_regimens_owner ON health_regimens(user_id,starts DESC);
CREATE INDEX health_measurement_history ON health_records(user_id,title,unit,occurred_at DESC);
