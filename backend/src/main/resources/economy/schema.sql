-- Observations and evaluations are append-only. A new capture never changes an old report's evidence.
CREATE TABLE IF NOT EXISTS economy_instrument (
    id TEXT PRIMARY KEY, owner TEXT NOT NULL, kind TEXT NOT NULL,
    symbol TEXT NOT NULL, symbol_key TEXT NOT NULL, created_at INTEGER NOT NULL,
    UNIQUE(owner, kind, symbol_key)
);
CREATE TABLE IF NOT EXISTS economy_study (
    id TEXT PRIMARY KEY, owner TEXT NOT NULL,
    instrument_id TEXT NOT NULL REFERENCES economy_instrument(id),
    trade_date TEXT NOT NULL, created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_economy_study_history ON economy_study(owner,trade_date DESC,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_economy_study_instrument ON economy_study(instrument_id,trade_date DESC);
CREATE TABLE IF NOT EXISTS economy_observation (
    id TEXT PRIMARY KEY, study_id TEXT NOT NULL REFERENCES economy_study(id), owner TEXT NOT NULL,
    timeframe TEXT NOT NULL CHECK(timeframe IN ('weekly','daily','intraday')),
    captured_at INTEGER NOT NULL, created_at INTEGER NOT NULL, source_title TEXT NOT NULL,
    source_url TEXT NOT NULL, mime_type TEXT NOT NULL, image BLOB NOT NULL, image_hash TEXT NOT NULL,
    agent_id TEXT NOT NULL, agent_name TEXT NOT NULL, model TEXT NOT NULL, notes TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('ANALYZING','COMPLETE','FAILED')),
    analysis TEXT, error TEXT
);
CREATE INDEX IF NOT EXISTS idx_economy_observation_study ON economy_observation(study_id,created_at DESC);
CREATE TABLE IF NOT EXISTS economy_evaluation (
    id TEXT PRIMARY KEY, owner TEXT NOT NULL, study_id TEXT NOT NULL REFERENCES economy_study(id),
    instrument_id TEXT NOT NULL REFERENCES economy_instrument(id), trade_date TEXT NOT NULL,
    created_at INTEGER NOT NULL, body TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_economy_evaluation_history ON economy_evaluation(owner,instrument_id,trade_date DESC,created_at DESC);
CREATE TABLE IF NOT EXISTS economy_evidence (
    evaluation_id TEXT NOT NULL REFERENCES economy_evaluation(id),
    observation_id TEXT NOT NULL REFERENCES economy_observation(id),
    PRIMARY KEY(evaluation_id,observation_id)
);
