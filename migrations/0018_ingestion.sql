CREATE TABLE health_ingest_tokens (
  user_id uuid PRIMARY KEY REFERENCES users ON DELETE CASCADE,
  token_hash bytea UNIQUE NOT NULL
);
ALTER TABLE health_files ADD COLUMN batch_hash bytea;
CREATE UNIQUE INDEX health_files_batch ON health_files(user_id,batch_hash);
