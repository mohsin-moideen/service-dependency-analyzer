CREATE TABLE IF NOT EXISTS services (
  id                TEXT PRIMARY KEY,
  team              TEXT,
  tier              TEXT,
  region            TEXT,
  last_heartbeat_ts INTEGER
);

CREATE TABLE IF NOT EXISTS edges (
  source                  TEXT NOT NULL,
  target                  TEXT NOT NULL,
  rolling_avg_latency_ms  REAL,
  sample_count            INTEGER NOT NULL DEFAULT 0,
  -- Event-time of the most recent observation applied. Used by LWW ordering checks
  -- after a restart so stale events delivered post-boot are still rejected.
  last_observed_ts        INTEGER,
  PRIMARY KEY (source, target)
);

CREATE INDEX IF NOT EXISTS idx_edges_target ON edges(target);

CREATE TABLE IF NOT EXISTS edge_samples (
  source     TEXT NOT NULL,
  target     TEXT NOT NULL,
  ts         INTEGER NOT NULL,
  latency_ms INTEGER,
  status     TEXT
);

CREATE INDEX IF NOT EXISTS idx_edge_samples_ts ON edge_samples(ts);

CREATE TABLE IF NOT EXISTS processed_events (
  event_id     TEXT PRIMARY KEY,
  processed_ts INTEGER NOT NULL
);
