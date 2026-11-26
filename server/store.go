package main

import (
	"database/sql"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

// One SQLite file holds everything. The VPS this runs on has 1GB of RAM shared with other
// services, so the server keeps a single connection: writes are tiny and rare (a device reports
// every ~30s), and one connection means SQLITE_BUSY can never happen.
const schema = `
CREATE TABLE IF NOT EXISTS users(
  id INTEGER PRIMARY KEY,
  email TEXT UNIQUE NOT NULL,
  pw TEXT NOT NULL,
  tz TEXT NOT NULL DEFAULT 'UTC',
  created INTEGER NOT NULL,
  rules TEXT NOT NULL DEFAULT '{}',
  pending TEXT NOT NULL DEFAULT '',
  pending_from TEXT NOT NULL DEFAULT '',
  streak INTEGER NOT NULL DEFAULT 0,
  best_streak INTEGER NOT NULL DEFAULT 0,
  freezes INTEGER NOT NULL DEFAULT 0,
  settled TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS devices(
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  platform TEXT NOT NULL,
  created INTEGER NOT NULL,
  last_seen INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT '{}'
);
CREATE TABLE IF NOT EXISTS tokens(
  hash TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL,
  device_id INTEGER NOT NULL,
  created INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS usage(
  user_id INTEGER NOT NULL, date TEXT NOT NULL, device_id INTEGER NOT NULL, target TEXT NOT NULL,
  name TEXT NOT NULL DEFAULT '', seconds INTEGER NOT NULL DEFAULT 0,
  opens INTEGER NOT NULL DEFAULT 0, reels INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(user_id, date, device_id, target)
);
CREATE TABLE IF NOT EXISTS hours(
  user_id INTEGER NOT NULL, date TEXT NOT NULL, device_id INTEGER NOT NULL,
  data TEXT NOT NULL DEFAULT '[]', pickups INTEGER NOT NULL DEFAULT 0,
  reels INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(user_id, date, device_id)
);
CREATE TABLE IF NOT EXISTS days(
  user_id INTEGER NOT NULL, date TEXT NOT NULL,
  reel_limit INTEGER, bonus INTEGER NOT NULL DEFAULT 0, penalty INTEGER NOT NULL DEFAULT 0,
  pass_until INTEGER NOT NULL DEFAULT 0, result TEXT NOT NULL DEFAULT '',
  PRIMARY KEY(user_id, date)
);
CREATE TABLE IF NOT EXISTS sessions(
  id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, device_id INTEGER NOT NULL,
  mode TEXT NOT NULL, label TEXT NOT NULL DEFAULT '', task_id INTEGER NOT NULL DEFAULT 0,
  started INTEGER NOT NULL, ends INTEGER NOT NULL,
  work_min INTEGER NOT NULL, break_min INTEGER NOT NULL DEFAULT 0, cycles INTEGER NOT NULL DEFAULT 1,
  strict INTEGER NOT NULL DEFAULT 1, categories TEXT NOT NULL DEFAULT '[]',
  status TEXT NOT NULL DEFAULT 'active', ended INTEGER NOT NULL DEFAULT 0,
  seed INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id, started);
CREATE TABLE IF NOT EXISTS tasks(
  id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, title TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '',
  due INTEGER NOT NULL DEFAULT 0, est INTEGER NOT NULL DEFAULT 1, focus_min INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'open', created INTEGER NOT NULL, done_at INTEGER NOT NULL DEFAULT 0,
  penalized INTEGER NOT NULL DEFAULT 0, debt_cleared INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS ledger(
  id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, ts INTEGER NOT NULL,
  coins INTEGER NOT NULL DEFAULT 0, xp INTEGER NOT NULL DEFAULT 0,
  reason TEXT NOT NULL, ref TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS ledger_user ON ledger(user_id, ts);
`

func openDB(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(on)", path))
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		return nil, err
	}
	return db, nil
}

func nowMs() int64 { return time.Now().UnixMilli() }
