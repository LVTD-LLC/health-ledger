CREATE TABLE health_records (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  occurred_at timestamptz NOT NULL,
  kind text NOT NULL,
  title text NOT NULL,
  value text NOT NULL DEFAULT '',
  unit text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  source text NOT NULL,
  external_key text,
  UNIQUE (user_id, source, external_key)
);
CREATE INDEX health_records_timeline ON health_records(user_id, occurred_at DESC, id DESC);

-- Store the original and searchable text together, under the account deletion cascade.
CREATE TABLE health_files (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  name text NOT NULL,
  purpose text NOT NULL,
  source text NOT NULL,
  content bytea NOT NULL,
  status text NOT NULL DEFAULT 'Queued',
  extracted text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX health_files_owner ON health_files(user_id, created_at DESC);
CREATE INDEX health_files_queue ON health_files(created_at) WHERE status='Queued';

CREATE TABLE health_connections (
  user_id uuid PRIMARY KEY REFERENCES users ON DELETE CASCADE,
  credential bytea NOT NULL,
  backfill_before date,
  next_sync timestamptz NOT NULL DEFAULT now(),
  status text NOT NULL DEFAULT 'Queued'
);
CREATE TABLE health_reminders (
  user_id uuid PRIMARY KEY REFERENCES users ON DELETE CASCADE,
  hour_utc smallint NOT NULL CHECK(hour_utc BETWEEN 0 AND 23),
  next_send timestamptz NOT NULL
);
