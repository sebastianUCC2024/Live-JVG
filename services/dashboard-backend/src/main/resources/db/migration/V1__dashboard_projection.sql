CREATE TABLE matches (
 match_id TEXT PRIMARY KEY, home_team_id TEXT NOT NULL, away_team_id TEXT NOT NULL,
 home_name TEXT NOT NULL, away_name TEXT NOT NULL,
 home_score INTEGER NOT NULL DEFAULT 0 CHECK(home_score>=0), away_score INTEGER NOT NULL DEFAULT 0 CHECK(away_score>=0),
 var_status TEXT NOT NULL DEFAULT 'CLEAR', last_event_id TEXT, updated_at TEXT
);
CREATE TABLE inbox (
 seq BIGSERIAL PRIMARY KEY, match_id TEXT NOT NULL REFERENCES matches(match_id), event_id TEXT NOT NULL,
 payload TEXT NOT NULL, UNIQUE(match_id,event_id)
);
