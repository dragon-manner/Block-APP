package main

import (
	"database/sql"
	"encoding/json"
	"time"
)

type User struct {
	ID          int64
	Email       string
	TZ          string
	Rules       Rules
	Pending     *Rules
	PendingFrom string
	Streak      int
	Best        int
	Freezes     int
	Settled     string
	loc         *time.Location
}

func (s *Server) loadUser(id int64) (*User, error) {
	u := &User{ID: id}
	var rules, pending string
	err := s.db.QueryRow(`SELECT email, tz, rules, pending, pending_from, streak, best_streak, freezes, settled FROM users WHERE id=?`, id).
		Scan(&u.Email, &u.TZ, &rules, &pending, &u.PendingFrom, &u.Streak, &u.Best, &u.Freezes, &u.Settled)
	if err != nil {
		return nil, err
	}
	u.Rules = parseRules(rules)
	if pending != "" {
		p := parseRules(pending)
		u.Pending = &p
	}
	u.loc, err = time.LoadLocation(u.TZ)
	if err != nil {
		u.loc = time.UTC
	}
	return u, nil
}

func (s *Server) saveUserState(u *User) error {
	pending := ""
	if u.Pending != nil {
		pending = u.Pending.json()
	}
	_, err := s.db.Exec(`UPDATE users SET rules=?, pending=?, pending_from=?, streak=?, best_streak=?, freezes=?, settled=? WHERE id=?`,
		u.Rules.json(), pending, u.PendingFrom, u.Streak, u.Best, u.Freezes, u.Settled, u.ID)
	return err
}

func (u *User) now() time.Time         { return time.Now().In(u.loc) }
func (u *User) today() string          { return u.now().Format("2006-01-02") }
func (u *User) dateOf(ms int64) string { return time.UnixMilli(ms).In(u.loc).Format("2006-01-02") }

func (u *User) dayStart(date string) int64 {
	t, err := time.ParseInLocation("2006-01-02", date, u.loc)
	if err != nil {
		return 0
	}
	return t.UnixMilli()
}

func addDays(date string, n int) string {
	t, err := time.Parse("2006-01-02", date)
	if err != nil {
		return date
	}
	return t.AddDate(0, 0, n).Format("2006-01-02")
}

func daysBetween(a, b string) int {
	ta, e1 := time.Parse("2006-01-02", a)
	tb, e2 := time.Parse("2006-01-02", b)
	if e1 != nil || e2 != nil {
		return 0
	}
	return int(tb.Sub(ta).Hours() / 24)
}

type Day struct {
	ReelLimit sql.NullInt64
	Bonus     int
	Penalty   int
	PassUntil int64
	Result    string
}

func (s *Server) day(userID int64, date string) Day {
	var d Day
	err := s.db.QueryRow(`SELECT reel_limit, bonus, penalty, pass_until, result FROM days WHERE user_id=? AND date=?`, userID, date).
		Scan(&d.ReelLimit, &d.Bonus, &d.Penalty, &d.PassUntil, &d.Result)
	if err != nil {
		return Day{}
	}
	return d
}

func (s *Server) ensureDay(userID int64, date string) {
	_, _ = s.db.Exec(`INSERT OR IGNORE INTO days(user_id, date) VALUES(?, ?)`, userID, date)
}

func (s *Server) addLedger(userID int64, coins, xp int, reason, ref string) {
	_, _ = s.db.Exec(`INSERT INTO ledger(user_id, ts, coins, xp, reason, ref) VALUES(?,?,?,?,?,?)`, userID, nowMs(), coins, xp, reason, ref)
}

func (s *Server) ledgerCount(userID int64, reason, ref string) int {
	var n int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM ledger WHERE user_id=? AND reason=? AND ref=?`, userID, reason, ref).Scan(&n)
	return n
}

func mustJSON(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}
