CREATE TABLE IF NOT EXISTS coding_repository (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, name TEXT NOT NULL, path TEXT NOT NULL,
 project_id TEXT, validation_command TEXT, created_at INTEGER NOT NULL,
 UNIQUE(owner_id,path)
);
CREATE TABLE IF NOT EXISTS coding_run (
 id TEXT PRIMARY KEY, task_id INTEGER NOT NULL REFERENCES task(id), owner_id TEXT NOT NULL,
 repository_id TEXT NOT NULL REFERENCES coding_repository(id), prompt TEXT NOT NULL, model_id TEXT,
 mode TEXT NOT NULL, runner TEXT NOT NULL, max_rounds INTEGER NOT NULL,
 status TEXT NOT NULL, attempt INTEGER NOT NULL DEFAULT 1, round INTEGER NOT NULL DEFAULT 0,
 answer TEXT, error TEXT, validation_status TEXT NOT NULL DEFAULT 'NOT_RUN', validation_hash TEXT, artifact_hash TEXT,
 messages TEXT, checkpoint_attempt INTEGER NOT NULL DEFAULT 1, request_key TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_coding_run_owner ON coding_run(owner_id,updated_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_coding_run_request ON coding_run(owner_id,request_key);
CREATE TABLE IF NOT EXISTS coding_run_agent (
 run_id TEXT PRIMARY KEY REFERENCES coding_run(id), agent_id TEXT NOT NULL,
 requested_model_id TEXT, instructions TEXT
);
CREATE TABLE IF NOT EXISTS coding_event (
 id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL REFERENCES coding_run(id),
 type TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_coding_event_run ON coding_event(run_id,id);
CREATE TABLE IF NOT EXISTS coding_operation (
 id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL REFERENCES coding_run(id),
 attempt INTEGER NOT NULL, round INTEGER NOT NULL, call_id TEXT NOT NULL, tool TEXT NOT NULL, args TEXT NOT NULL, status TEXT NOT NULL,
 result TEXT, started_at INTEGER NOT NULL, finished_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_coding_operation_run ON coding_operation(run_id,id);
CREATE TABLE IF NOT EXISTS coding_input (
 id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL REFERENCES coding_run(id),
 content TEXT NOT NULL, created_at INTEGER NOT NULL, applied_at INTEGER
);
CREATE TABLE IF NOT EXISTS coding_workspace_project (
 repository_id TEXT PRIMARY KEY REFERENCES coding_repository(id), workspace_id TEXT NOT NULL,
 directory TEXT NOT NULL, root_path TEXT NOT NULL, target_fingerprint TEXT NOT NULL,
 UNIQUE(workspace_id,root_path,repository_id)
);
CREATE TABLE IF NOT EXISTS coding_session (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, repository_id TEXT NOT NULL REFERENCES coding_repository(id),
 title TEXT NOT NULL, last_run_id TEXT, messages TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS coding_session_run (
 run_id TEXT PRIMARY KEY REFERENCES coding_run(id), session_id TEXT NOT NULL REFERENCES coding_session(id)
);
CREATE INDEX IF NOT EXISTS idx_coding_session_owner ON coding_session(owner_id,updated_at);
CREATE TABLE IF NOT EXISTS coding_role_config (
 owner_id TEXT NOT NULL, role TEXT NOT NULL, model_id TEXT, instructions TEXT NOT NULL,
 version INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(owner_id,role)
);
CREATE TABLE IF NOT EXISTS coding_execution (
 id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES coding_run(id), task_id INTEGER NOT NULL REFERENCES task(id),
 role TEXT NOT NULL, title TEXT NOT NULL, prompt TEXT NOT NULL, model_id TEXT,
 instructions TEXT NOT NULL, config_version INTEGER NOT NULL, status TEXT NOT NULL,
 attempt INTEGER NOT NULL DEFAULT 1, round INTEGER NOT NULL DEFAULT 0, messages TEXT,
 checkpoint_attempt INTEGER NOT NULL DEFAULT 1, answer TEXT, error TEXT, base_hash TEXT, final_hash TEXT,
 reported_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_coding_execution_run ON coding_execution(run_id,created_at);
CREATE TABLE IF NOT EXISTS coding_child_operation (
 id INTEGER PRIMARY KEY AUTOINCREMENT, execution_id TEXT NOT NULL REFERENCES coding_execution(id),
 attempt INTEGER NOT NULL, round INTEGER NOT NULL, call_id TEXT NOT NULL, tool TEXT NOT NULL, args TEXT NOT NULL,
 status TEXT NOT NULL, result TEXT, started_at INTEGER NOT NULL, finished_at INTEGER
);
CREATE TABLE IF NOT EXISTS coding_delegation_request (
 request_key TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES coding_run(id),
 execution_id TEXT NOT NULL REFERENCES coding_execution(id), arguments TEXT NOT NULL
);
