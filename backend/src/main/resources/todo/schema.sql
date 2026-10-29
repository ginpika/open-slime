-- Personal work belongs to a person; never reuse Agent task/todo tables.
CREATE TABLE IF NOT EXISTS personal_todo_owner (
  owner_id TEXT PRIMARY KEY,
  revision INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS personal_todo (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  title TEXT NOT NULL,
  note TEXT NOT NULL DEFAULT '',
  project_id TEXT,
  project_name TEXT,
  state TEXT NOT NULL CHECK(state IN ('idea','ready','doing','done','dropped')),
  planned_on TEXT,
  due_at INTEGER,
  completed_at INTEGER,
  closed_at INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_personal_todo_owner ON personal_todo(owner_id,state,planned_on);
CREATE INDEX IF NOT EXISTS idx_personal_todo_history ON personal_todo(owner_id,closed_at);
-- Project IDs deliberately have no cascade: deleting a tag must not erase a person's history.
CREATE TABLE IF NOT EXISTS personal_focus (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  todo_id TEXT NOT NULL REFERENCES personal_todo(id),
  started_at INTEGER NOT NULL,
  ended_at INTEGER,
  source TEXT NOT NULL CHECK(source IN ('timer','manual')),
  note TEXT NOT NULL DEFAULT '',
  version INTEGER NOT NULL DEFAULT 0,
  CHECK(ended_at IS NULL OR ended_at >= started_at)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_personal_focus_running ON personal_focus(owner_id) WHERE ended_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_personal_focus_range ON personal_focus(owner_id,started_at,ended_at);
CREATE INDEX IF NOT EXISTS idx_personal_focus_todo ON personal_focus(todo_id,started_at);
CREATE TABLE IF NOT EXISTS personal_todo_event (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  owner_id TEXT NOT NULL,
  todo_id TEXT NOT NULL REFERENCES personal_todo(id),
  kind TEXT NOT NULL,
  detail TEXT NOT NULL,
  occurred_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_personal_todo_event ON personal_todo_event(owner_id,todo_id,occurred_at);
CREATE TABLE IF NOT EXISTS personal_day_note (
  owner_id TEXT NOT NULL,
  day TEXT NOT NULL,
  body TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY(owner_id,day)
);
