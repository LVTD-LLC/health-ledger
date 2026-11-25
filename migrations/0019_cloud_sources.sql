CREATE TABLE health_oauth_attempts (
 user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
 provider text NOT NULL, state_hash bytea NOT NULL, expires timestamptz NOT NULL,
 PRIMARY KEY (user_id,provider)
);
CREATE TABLE health_cloud_sources (
 user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
 provider text NOT NULL, credential bytea NOT NULL,
 cursor_json text NOT NULL DEFAULT '{}', next_sync timestamptz NOT NULL DEFAULT now(),
 status text NOT NULL DEFAULT 'Waiting for first import',
 PRIMARY KEY(user_id,provider)
);
