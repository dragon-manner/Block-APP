package main

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"maps"
	"math/big"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/bcrypt"
)

type Server struct {
	db         *sql.DB
	hub        *Hub
	catalog    Catalog
	openSignup bool
	// mu serialises everything that reads-then-writes the economy (shop, focus, tasks, ticker)
	// so two devices tapping "buy" at once can't both spend the same coins.
	mu sync.Mutex
}

type authed struct {
	UserID   int64
	DeviceID int64
}

type apiErr struct {
	code int
	msg  string
}

func (e apiErr) Error() string { return e.msg }

func bad(msg string) error      { return apiErr{400, msg} }
func forbidden(msg string) error { return apiErr{403, msg} }

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func readJSON(r *http.Request, v any) error {
	dec := json.NewDecoder(http.MaxBytesReader(nil, r.Body, 1<<20))
	if err := dec.Decode(v); err != nil {
		return bad("invalid JSON: " + err.Error())
	}
	return nil
}

func hashToken(t string) string {
	h := sha256.Sum256([]byte(t))
	return hex.EncodeToString(h[:])
}

func randToken() string {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

func randSeed() int64 {
	n, _ := rand.Int(rand.Reader, big.NewInt(1<<31))
	return n.Int64()
}

type handler func(w http.ResponseWriter, r *http.Request, a authed) (any, error)

func (s *Server) auth(h handler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if tok == "" {
			tok = r.URL.Query().Get("token")
		}
		var a authed
		if tok == "" || s.db.QueryRow(`SELECT user_id, device_id FROM tokens WHERE hash=?`, hashToken(tok)).Scan(&a.UserID, &a.DeviceID) != nil {
			writeJSON(w, 401, map[string]string{"error": "signed out"})
			return
		}
		_, _ = s.db.Exec(`UPDATE devices SET last_seen=? WHERE id=?`, nowMs(), a.DeviceID)
		v, err := h(w, r, a)
		if err != nil {
			var ae apiErr
			if errors.As(err, &ae) {
				writeJSON(w, ae.code, map[string]string{"error": ae.msg})
			} else {
				writeJSON(w, 500, map[string]string{"error": err.Error()})
			}
			return
		}
		if v != nil {
			writeJSON(w, 200, v)
		}
	}
}

func (s *Server) routes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/register", s.register)
	mux.HandleFunc("POST /api/login", s.login)
	mux.HandleFunc("GET /api/catalog", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(catalogRaw)
	})
	mux.HandleFunc("GET /api/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"ok": true, "now": nowMs()})
	})
	mux.HandleFunc("POST /api/logout", s.auth(s.logout))
	mux.HandleFunc("GET /api/ws", s.auth(func(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
		s.hub.serve(w, r, a.UserID)
		return nil, nil
	}))
	mux.HandleFunc("GET /api/state", s.auth(s.state))
	mux.HandleFunc("GET /api/enforce", s.auth(s.getEnforce))
	mux.HandleFunc("PUT /api/rules", s.auth(s.putRules))
	mux.HandleFunc("PUT /api/me", s.auth(s.putMe))
	mux.HandleFunc("POST /api/usage", s.auth(s.postUsage))
	mux.HandleFunc("POST /api/reels", s.auth(s.postReels))
	mux.HandleFunc("GET /api/history", s.auth(s.history))
	mux.HandleFunc("GET /api/day", s.auth(s.dayDetail))
	mux.HandleFunc("GET /api/grove", s.auth(s.grove))
	mux.HandleFunc("GET /api/ledger", s.auth(s.ledger))
	mux.HandleFunc("POST /api/focus", s.auth(s.focusStart))
	mux.HandleFunc("POST /api/focus/{id}/giveup", s.auth(s.focusGiveUp))
	mux.HandleFunc("POST /api/focus/{id}/violation", s.auth(s.focusViolation))
	mux.HandleFunc("GET /api/tasks", s.auth(s.listTasks))
	mux.HandleFunc("POST /api/tasks", s.auth(s.createTask))
	mux.HandleFunc("PATCH /api/tasks/{id}", s.auth(s.patchTask))
	mux.HandleFunc("DELETE /api/tasks/{id}", s.auth(s.deleteTask))
	mux.HandleFunc("POST /api/shop", s.auth(s.buy))
	mux.HandleFunc("GET /api/devices", s.auth(s.devices))
	mux.HandleFunc("PATCH /api/devices/{id}", s.auth(s.patchDevice))
	mux.HandleFunc("DELETE /api/devices/{id}", s.auth(s.deleteDevice))
	mux.HandleFunc("POST /api/device/status", s.auth(s.deviceStatus))
}

// ---- Auth ----------------------------------------------------------------------------------

type authReq struct {
	Email    string `json:"email"`
	Password string `json:"password"`
	TZ       string `json:"tz"`
	Device   struct {
		Name     string `json:"name"`
		Platform string `json:"platform"`
	} `json:"device"`
}

func (s *Server) issue(userID int64, req authReq) (map[string]any, error) {
	name := strings.TrimSpace(req.Device.Name)
	if name == "" {
		name = "Device"
	}
	plat := req.Device.Platform
	switch plat {
	case "android", "linux", "windows", "macos", "web":
	default:
		plat = "web"
	}
	// One row per physical device: signing in again from the same named device reuses it, so
	// its history doesn't split across a pile of ghost devices.
	var devID int64
	err := s.db.QueryRow(`SELECT id FROM devices WHERE user_id=? AND name=? AND platform=?`, userID, name, plat).Scan(&devID)
	if err != nil {
		res, err := s.db.Exec(`INSERT INTO devices(user_id, name, platform, created, last_seen) VALUES(?,?,?,?,?)`, userID, name, plat, nowMs(), nowMs())
		if err != nil {
			return nil, err
		}
		devID, _ = res.LastInsertId()
	}
	tok := randToken()
	if _, err := s.db.Exec(`INSERT INTO tokens(hash, user_id, device_id, created) VALUES(?,?,?,?)`, hashToken(tok), userID, devID, nowMs()); err != nil {
		return nil, err
	}
	s.hub.notify(userID)
	return map[string]any{"token": tok, "deviceId": devID, "userId": userID}, nil
}

func (s *Server) register(w http.ResponseWriter, r *http.Request) {
	var req authReq
	if err := readJSON(r, &req); err != nil {
		writeJSON(w, 400, map[string]string{"error": err.Error()})
		return
	}
	req.Email = strings.ToLower(strings.TrimSpace(req.Email))
	if !strings.Contains(req.Email, "@") || len(req.Password) < 8 {
		writeJSON(w, 400, map[string]string{"error": "Use a real email and a password of 8+ characters."})
		return
	}
	var n int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM users`).Scan(&n)
	if n > 0 && !s.openSignup {
		writeJSON(w, 403, map[string]string{"error": "This server is private. Sign in with the existing account."})
		return
	}
	if _, err := time.LoadLocation(req.TZ); err != nil || req.TZ == "" {
		req.TZ = "UTC"
	}
	pw, _ := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	res, err := s.db.Exec(`INSERT INTO users(email, pw, tz, created, rules) VALUES(?,?,?,?,?)`, req.Email, string(pw), req.TZ, nowMs(), defaultRules().json())
	if err != nil {
		writeJSON(w, 409, map[string]string{"error": "That email already has an account. Sign in instead."})
		return
	}
	id, _ := res.LastInsertId()
	s.addLedger(id, 50, 0, "welcome", "")
	out, err := s.issue(id, req)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, out)
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	var req authReq
	if err := readJSON(r, &req); err != nil {
		writeJSON(w, 400, map[string]string{"error": err.Error()})
		return
	}
	var id int64
	var pw string
	err := s.db.QueryRow(`SELECT id, pw FROM users WHERE email=?`, strings.ToLower(strings.TrimSpace(req.Email))).Scan(&id, &pw)
	if err != nil || bcrypt.CompareHashAndPassword([]byte(pw), []byte(req.Password)) != nil {
		time.Sleep(700 * time.Millisecond) // slows password guessing to a crawl
		writeJSON(w, 401, map[string]string{"error": "Wrong email or password."})
		return
	}
	out, err := s.issue(id, req)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, out)
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	_, _ = s.db.Exec(`DELETE FROM tokens WHERE hash=?`, hashToken(tok))
	return map[string]bool{"ok": true}, nil
}

// ---- State ---------------------------------------------------------------------------------

func (s *Server) freshUser(id int64) (*User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	u, err := s.loadUser(id)
	if err != nil {
		return nil, err
	}
	a := s.completeDueSessions(u)
	b := s.penalizeOverdue(u)
	c := s.rollover(u)
	if a || b || c {
		s.hub.notify(id)
	}
	return u, nil
}

func (s *Server) getEnforce(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	u, err := s.freshUser(a.UserID)
	if err != nil {
		return nil, err
	}
	return s.enforce(u, a.DeviceID), nil
}

type UsageRow struct {
	Target   string         `json:"target"`
	Name     string         `json:"name"`
	Seconds  int            `json:"seconds"`
	Opens    int            `json:"opens"`
	Reels    int            `json:"reels"`
	ByDevice map[string]int `json:"byDevice"`
}

type DaySummary struct {
	Date      string           `json:"date"`
	Seconds   int              `json:"seconds"`
	ByDevice  map[string]int   `json:"byDevice"`
	Reels     int              `json:"reels"`
	Pickups   int              `json:"pickups"`
	FocusMin  int              `json:"focusMin"`
	Apps      []UsageRow       `json:"apps"`
	Hours     map[string][]int `json:"hours"` // device -> 24 buckets of seconds
	ReelLimit *int             `json:"reelLimit"`
	Result    string           `json:"result"`
}

func (s *Server) daySummary(u *User, date string, withApps bool) DaySummary {
	d := DaySummary{Date: date, ByDevice: map[string]int{}, Apps: []UsageRow{}, Hours: map[string][]int{}}
	rows, err := s.db.Query(`SELECT device_id, data, pickups, reels FROM hours WHERE user_id=? AND date=?`, u.ID, date)
	if err == nil {
		for rows.Next() {
			var dev int64
			var data string
			var pk, rl int
			_ = rows.Scan(&dev, &data, &pk, &rl)
			var hrs []int
			_ = json.Unmarshal([]byte(data), &hrs)
			if len(hrs) == 24 {
				d.Hours[strconv.FormatInt(dev, 10)] = hrs
			}
			d.Pickups += pk
			d.Reels += rl
		}
		rows.Close()
	}
	byTarget := map[string]*UsageRow{}
	var order []string
	rows, err = s.db.Query(`SELECT device_id, target, name, seconds, opens, reels FROM usage WHERE user_id=? AND date=? ORDER BY seconds DESC`, u.ID, date)
	if err == nil {
		for rows.Next() {
			var dev int64
			var t, name string
			var sec, op, rl int
			_ = rows.Scan(&dev, &t, &name, &sec, &op, &rl)
			ds := strconv.FormatInt(dev, 10)
			d.Seconds += sec
			d.ByDevice[ds] += sec
			row := byTarget[t]
			if row == nil {
				row = &UsageRow{Target: t, Name: name, ByDevice: map[string]int{}}
				byTarget[t] = row
				order = append(order, t)
			}
			row.Seconds += sec
			row.Opens += op
			row.Reels += rl
			row.ByDevice[ds] += sec
		}
		rows.Close()
	}
	if withApps {
		for _, t := range order {
			d.Apps = append(d.Apps, *byTarget[t])
		}
		sortRows(d.Apps)
	}
	d.FocusMin = s.focusMinutes(u, date)
	day := s.day(u.ID, date)
	d.Result = day.Result
	if day.ReelLimit.Valid {
		v := int(day.ReelLimit.Int64)
		d.ReelLimit = &v
	}
	return d
}

func sortRows(rows []UsageRow) {
	for i := 1; i < len(rows); i++ {
		for j := i; j > 0 && rows[j].Seconds > rows[j-1].Seconds; j-- {
			rows[j], rows[j-1] = rows[j-1], rows[j]
		}
	}
}

type Task struct {
	ID          int64  `json:"id"`
	Title       string `json:"title"`
	Notes       string `json:"notes"`
	Due         int64  `json:"due"`
	Est         int    `json:"est"`
	FocusMin    int    `json:"focusMin"`
	Status      string `json:"status"`
	Created     int64  `json:"created"`
	DoneAt      int64  `json:"doneAt"`
	Penalized   bool   `json:"penalized"`
	DebtCleared bool   `json:"debtCleared"`
}

func (s *Server) tasks(userID int64, all bool) []Task {
	q := `SELECT id, title, notes, due, est, focus_min, status, created, done_at, penalized, debt_cleared FROM tasks WHERE user_id=?`
	if !all {
		q += ` AND (status='open' OR done_at>?)`
	} else {
		q += ` AND ?=?`
	}
	q += ` ORDER BY status='done', CASE WHEN due=0 THEN 1 ELSE 0 END, due, id`
	var rows *sql.Rows
	var err error
	if all {
		rows, err = s.db.Query(q, userID, 1, 1)
	} else {
		rows, err = s.db.Query(q, userID, nowMs()-36*3600*1000)
	}
	out := []Task{}
	if err != nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var t Task
		var p, dc int
		_ = rows.Scan(&t.ID, &t.Title, &t.Notes, &t.Due, &t.Est, &t.FocusMin, &t.Status, &t.Created, &t.DoneAt, &p, &dc)
		t.Penalized, t.DebtCleared = p == 1, dc == 1
		out = append(out, t)
	}
	return out
}

type Device struct {
	ID       int64           `json:"id"`
	Name     string          `json:"name"`
	Platform string          `json:"platform"`
	LastSeen int64           `json:"lastSeen"`
	Status   json.RawMessage `json:"status"`
	Online   bool            `json:"online"`
}

func (s *Server) deviceList(userID int64) []Device {
	out := []Device{}
	rows, err := s.db.Query(`SELECT id, name, platform, last_seen, status FROM devices WHERE user_id=? ORDER BY last_seen DESC`, userID)
	if err != nil {
		return out
	}
	defer rows.Close()
	s.hub.mu.Lock()
	online := len(s.hub.conns[userID]) > 0
	s.hub.mu.Unlock()
	_ = online
	for rows.Next() {
		var d Device
		var st string
		_ = rows.Scan(&d.ID, &d.Name, &d.Platform, &d.LastSeen, &st)
		d.Status = json.RawMessage(st)
		d.Online = nowMs()-d.LastSeen < 3*60*1000
		out = append(out, d)
	}
	return out
}

func (s *Server) state(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	u, err := s.freshUser(a.UserID)
	if err != nil {
		return nil, err
	}
	e := s.enforce(u, a.DeviceID)
	var sessions, minutes, tasksDone int
	_ = s.db.QueryRow(`SELECT COUNT(*), COALESCE(SUM(work_min*cycles),0) FROM sessions WHERE user_id=? AND status='completed'`, u.ID).Scan(&sessions, &minutes)
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM tasks WHERE user_id=? AND status='done'`, u.ID).Scan(&tasksDone)
	var withered int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM sessions WHERE user_id=? AND status='abandoned'`, u.ID).Scan(&withered)
	return map[string]any{
		"user":        map[string]any{"id": u.ID, "email": u.Email, "tz": u.TZ},
		"deviceId":    a.DeviceID,
		"rules":       u.Rules,
		"pending":     u.Pending,
		"pendingFrom": u.PendingFrom,
		"enforce":     e,
		"today":       s.daySummary(u, e.Date, true),
		"tasks":       s.tasks(u.ID, false),
		"wallet":      s.wallet(u),
		"shop":        s.shop(u, e),
		"devices":     s.deviceList(u.ID),
		"stats": map[string]any{
			"sessions": sessions, "focusMinutes": minutes, "tasksDone": tasksDone, "withered": withered,
			"focusGoal": u.Rules.DailyFocusGoal,
		},
	}, nil
}

// ---- Rules ---------------------------------------------------------------------------------

func (s *Server) putRules(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	u, err := s.loadUser(a.UserID)
	if err != nil {
		return nil, err
	}
	// Decoded over what the user would have tomorrow, so a client sending only the field it
	// changed doesn't reset the rest to zero (a zeroed penalty would read as a loosening).
	req := u.Rules
	if u.Pending != nil {
		req = *u.Pending
	}
	req.CategoryOverrides = maps.Clone(req.CategoryOverrides)
	if err := readJSON(r, &req); err != nil {
		return nil, err
	}
	req.normalize()
	// A taper starts counting its weeks from when it is switched on, not from whatever was sent.
	if req.Taper.Enabled && !u.Rules.Taper.Enabled {
		req.Taper.Started = u.today()
	} else if req.Taper.Enabled {
		req.Taper.Started = u.Rules.Taper.Started
	}
	eff, pending := tighten(u.Rules, req, s.catalog.distracting())
	u.Rules = eff
	if pending {
		u.Pending = &req
		u.PendingFrom = addDays(u.today(), 1)
	} else {
		u.Pending, u.PendingFrom = nil, ""
	}
	if err := s.saveUserState(u); err != nil {
		return nil, err
	}
	s.hub.notify(u.ID)
	return map[string]any{"rules": u.Rules, "pending": u.Pending, "pendingFrom": u.PendingFrom}, nil
}

func (s *Server) putMe(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	var req struct {
		TZ string `json:"tz"`
	}
	if err := readJSON(r, &req); err != nil {
		return nil, err
	}
	if _, err := time.LoadLocation(req.TZ); err != nil || req.TZ == "" {
		return nil, bad("unknown time zone")
	}
	_, err := s.db.Exec(`UPDATE users SET tz=? WHERE id=?`, req.TZ, a.UserID)
	return map[string]bool{"ok": true}, err
}

// ---- Usage ---------------------------------------------------------------------------------

type usageReq struct {
	Date string `json:"date"`
	Apps []struct {
		Target  string `json:"target"`
		Name    string `json:"name"`
		Seconds int    `json:"seconds"`
		Opens   int    `json:"opens"`
		Reels   int    `json:"reels"`
	} `json:"apps"`
	Hours   []int `json:"hours"`
	Pickups int   `json:"pickups"`
	Reels   int   `json:"reels"`
}

// postUsage takes a device's absolute totals for one day, not deltas: a retried or duplicated
// upload rewrites the same numbers instead of double-counting them.
func (s *Server) postUsage(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	var req usageReq
	if err := readJSON(r, &req); err != nil {
		return nil, err
	}
	u, err := s.loadUser(a.UserID)
	if err != nil {
		return nil, err
	}
	today := u.today()
	if _, err := time.Parse("2006-01-02", req.Date); err != nil || req.Date > addDays(today, 1) || req.Date < addDays(today, -31) {
		return nil, bad("date out of range")
	}
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	for _, app := range req.Apps {
		if app.Target == "" || len(app.Target) > 200 || app.Seconds < 0 || app.Seconds > 86400 {
			continue
		}
		_, err = tx.Exec(`INSERT INTO usage(user_id, date, device_id, target, name, seconds, opens, reels) VALUES(?,?,?,?,?,?,?,?)
			ON CONFLICT(user_id, date, device_id, target) DO UPDATE SET name=excluded.name,
			seconds=MAX(seconds, excluded.seconds), opens=MAX(opens, excluded.opens), reels=MAX(reels, excluded.reels)`,
			a.UserID, req.Date, a.DeviceID, app.Target, truncate(app.Name, 80), app.Seconds, app.Opens, app.Reels)
		if err != nil {
			return nil, err
		}
	}
	hrs := req.Hours
	if len(hrs) != 24 {
		hrs = make([]int, 24)
	}
	_, err = tx.Exec(`INSERT INTO hours(user_id, date, device_id, data, pickups, reels, updated) VALUES(?,?,?,?,?,?,?)
		ON CONFLICT(user_id, date, device_id) DO UPDATE SET data=excluded.data, pickups=MAX(pickups, excluded.pickups),
		reels=MAX(reels, excluded.reels), updated=excluded.updated`,
		a.UserID, req.Date, a.DeviceID, mustJSON(hrs), req.Pickups, req.Reels, nowMs())
	if err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	s.hub.notify(a.UserID)
	return s.getEnforce(w, r, a)
}

// postReels is the fast path a device hits the moment its reel count goes up, so the other
// devices hear about reel 30 of 30 within a second rather than at the next usage report.
func (s *Server) postReels(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	var req struct {
		Date  string `json:"date"`
		Count int    `json:"count"`
	}
	if err := readJSON(r, &req); err != nil {
		return nil, err
	}
	if _, err := time.Parse("2006-01-02", req.Date); err != nil || req.Count < 0 || req.Count > 100000 {
		return nil, bad("bad reel report")
	}
	_, err := s.db.Exec(`INSERT INTO hours(user_id, date, device_id, reels, updated) VALUES(?,?,?,?,?)
		ON CONFLICT(user_id, date, device_id) DO UPDATE SET reels=MAX(reels, excluded.reels), updated=excluded.updated`,
		a.UserID, req.Date, a.DeviceID, req.Count, nowMs())
	if err != nil {
		return nil, err
	}
	s.hub.notify(a.UserID)
	return s.getEnforce(w, r, a)
}

func truncate(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}

func (s *Server) history(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	u, err := s.loadUser(a.UserID)
	if err != nil {
		return nil, err
	}
	days, _ := strconv.Atoi(r.URL.Query().Get("days"))
	if days <= 0 || days > 366 {
		days = 30
	}
	today := u.today()
	from := addDays(today, -(days - 1))
	out := []DaySummary{}
	for d := from; d <= today; d = addDays(d, 1) {
		out = append(out, s.daySummary(u, d, false))
	}
	// Totals per app over the whole range, for the period breakdown.
	apps := []UsageRow{}
	rows, err := s.db.Query(`SELECT target, MAX(name), SUM(seconds), SUM(opens), SUM(reels) FROM usage WHERE user_id=? AND date>=? AND date<=?
		GROUP BY target ORDER BY SUM(seconds) DESC LIMIT 80`, u.ID, from, today)
	if err == nil {
		for rows.Next() {
			var row UsageRow
			_ = rows.Scan(&row.Target, &row.Name, &row.Seconds, &row.Opens, &row.Reels)
			row.ByDevice = map[string]int{}
			apps = append(apps, row)
		}
		rows.Close()
	}
	byDev, err := s.db.Query(`SELECT target, device_id, SUM(seconds) FROM usage WHERE user_id=? AND date>=? AND date<=? GROUP BY target, device_id`, u.ID, from, today)
	if err == nil {
		idx := map[string]int{}
		for i, a := range apps {
			idx[a.Target] = i
		}
		for byDev.Next() {
			var t string
			var dev int64
			var sec int
			_ = byDev.Scan(&t, &dev, &sec)
			if i, ok := idx[t]; ok {
				apps[i].ByDevice[strconv.FormatInt(dev, 10)] = sec
			}
		}
		byDev.Close()
	}
	return map[string]any{"days": out, "apps": apps, "devices": s.deviceList(u.ID)}, nil
}

func (s *Server) dayDetail(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	u, err := s.loadUser(a.UserID)
	if err != nil {
		return nil, err
	}
	date := r.URL.Query().Get("date")
	if _, err := time.Parse("2006-01-02", date); err != nil {
		date = u.today()
	}
	return map[string]any{"day": s.daySummary(u, date, true), "sessions": s.sessionsBetween(u.ID, u.dayStart(date), u.dayStart(addDays(date, 1)))}, nil
}

type SessionRow struct {
	ID      int64  `json:"id"`
	Mode    string `json:"mode"`
	Label   string `json:"label"`
	TaskID  int64  `json:"taskId"`
	Started int64  `json:"started"`
	Ended   int64  `json:"ended"`
	Minutes int    `json:"minutes"`
	Status  string `json:"status"`
	Seed    int64  `json:"seed"`
	Reason  string `json:"reason"`
}

func (s *Server) sessionsBetween(userID, from, to int64) []SessionRow {
	out := []SessionRow{}
	rows, err := s.db.Query(`SELECT id, mode, label, task_id, started, ended, work_min*cycles, status, seed, reason FROM sessions
		WHERE user_id=? AND started>=? AND started<? AND status IN ('completed','abandoned') ORDER BY started`, userID, from, to)
	if err != nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var x SessionRow
		_ = rows.Scan(&x.ID, &x.Mode, &x.Label, &x.TaskID, &x.Started, &x.Ended, &x.Minutes, &x.Status, &x.Seed, &x.Reason)
		out = append(out, x)
	}
	return out
}

func (s *Server) grove(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	u, err := s.loadUser(a.UserID)
	if err != nil {
		return nil, err
	}
	days, _ := strconv.Atoi(r.URL.Query().Get("days"))
	if days <= 0 || days > 3650 {
		days = 90
	}
	from := u.dayStart(addDays(u.today(), -(days - 1)))
	return map[string]any{"sessions": s.sessionsBetween(u.ID, from, nowMs()+1)}, nil
}

func (s *Server) ledger(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	out := []map[string]any{}
	rows, err := s.db.Query(`SELECT ts, coins, xp, reason, ref FROM ledger WHERE user_id=? ORDER BY id DESC LIMIT 100`, a.UserID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var ts int64
		var c, x int
		var reason, ref string
		_ = rows.Scan(&ts, &c, &x, &reason, &ref)
		out = append(out, map[string]any{"ts": ts, "coins": c, "xp": x, "reason": reason, "ref": ref})
	}
	return map[string]any{"entries": out}, nil
}

// ---- Focus ---------------------------------------------------------------------------------

type focusReq struct {
	Mode       string   `json:"mode"` // focus | pomodoro
	Minutes    int      `json:"minutes"`
	Work       int      `json:"work"`
	Break      int      `json:"break"`
	Cycles     int      `json:"cycles"`
	Strict     *bool    `json:"strict"`
	TaskID     int64    `json:"taskId"`
	Label      string   `json:"label"`
	Categories []string `json:"categories"`
}

func (s *Server) focusStart(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	var req focusReq
	if err := readJSON(r, &req); err != nil {
		return nil, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	u, err := s.loadUser(a.UserID)
	if err != nil {
		return nil, err
	}
	s.completeDueSessions(u)
	if s.activeFocus(u.ID) != nil {
		return nil, apiErr{409, "A focus session is already running."}
	}
	f := FocusState{Mode: req.Mode, Cycles: 1, Strict: true}
	if req.Strict != nil {
		f.Strict = *req.Strict
	}
	switch req.Mode {
	case "pomodoro":
		f.WorkMin, f.BreakMin, f.Cycles = clampInt(req.Work, 10, 90), clampInt(req.Break, 1, 30), clampInt(req.Cycles, 1, 8)
	default:
		f.Mode = "focus"
		f.WorkMin = clampInt(req.Minutes, 5, 240)
	}
	cats := req.Categories
	if len(cats) == 0 {
		cats = u.Rules.FocusCategories
	}
	f.Started = nowMs()
	f.Phases = phasesFor(&f)
	f.Ends = f.Phases[len(f.Phases)-1].End
	label := truncate(strings.TrimSpace(req.Label), 80)
	if req.TaskID != 0 {
		var title string
		if s.db.QueryRow(`SELECT title FROM tasks WHERE id=? AND user_id=?`, req.TaskID, u.ID).Scan(&title) != nil {
			req.TaskID = 0
		} else if label == "" {
			label = title
		}
	}
	strict := 0
	if f.Strict {
		strict = 1
	}
	_, err = s.db.Exec(`INSERT INTO sessions(user_id, device_id, mode, label, task_id, started, ends, work_min, break_min, cycles, strict, categories, seed)
		VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)`, u.ID, a.DeviceID, f.Mode, label, req.TaskID, f.Started, f.Ends, f.WorkMin, f.BreakMin, f.Cycles, strict, mustJSON(cats), randSeed())
	if err != nil {
		return nil, err
	}
	s.hub.notify(u.ID)
	return s.activeFocus(u.ID), nil
}

func (s *Server) endFocus(a authed, id int64, reason string) (any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	u, err := s.loadUser(a.UserID)
	if err != nil {
		return nil, err
	}
	f := s.activeFocus(u.ID)
	if f == nil || f.ID != id {
		return nil, apiErr{404, "That session isn't running."}
	}
	if nowMs() >= f.Ends {
		s.completeDueSessions(u)
		s.hub.notify(u.ID)
		return map[string]string{"status": "completed"}, nil
	}
	// The first few seconds are a free change of mind, the way a mis-tap should be.
	if nowMs()-f.Started < cancelGraceMs {
		_, _ = s.db.Exec(`DELETE FROM sessions WHERE id=?`, f.ID)
		s.hub.notify(u.ID)
		return map[string]string{"status": "cancelled"}, nil
	}
	_, _ = s.db.Exec(`UPDATE sessions SET status='abandoned', ended=?, reason=? WHERE id=?`, nowMs(), truncate(reason, 120), f.ID)
	p := u.Rules.Penalties
	date := u.today()
	s.ensureDay(u.ID, date)
	_, _ = s.db.Exec(`UPDATE days SET penalty=penalty+? WHERE user_id=? AND date=?`, p.GiveUpReels, u.ID, date)
	s.addLedger(u.ID, -p.GiveUpCoins, 0, "gave-up", mustJSON(map[string]any{"session": f.ID, "reason": reason, "reels": p.GiveUpReels}))
	s.hub.notify(u.ID)
	return map[string]any{"status": "abandoned", "coins": -p.GiveUpCoins, "reels": p.GiveUpReels}, nil
}

func (s *Server) focusGiveUp(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	return s.endFocus(a, id, "gave up")
}

// focusViolation is how a soft (non-blocking) session withers: a device saw a distracting app
// held open past its warning during a work phase.
func (s *Server) focusViolation(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var req struct {
		Target string `json:"target"`
	}
	_ = readJSON(r, &req)
	return s.endFocus(a, id, "opened "+truncate(req.Target, 60))
}

func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

// ---- Tasks ---------------------------------------------------------------------------------

func (s *Server) listTasks(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	return map[string]any{"tasks": s.tasks(a.UserID, r.URL.Query().Get("all") == "1")}, nil
}

func (s *Server) createTask(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	var req struct {
		Title string `json:"title"`
		Notes string `json:"notes"`
		Due   int64  `json:"due"`
		Est   int    `json:"est"`
	}
	if err := readJSON(r, &req); err != nil {
		return nil, err
	}
	req.Title = strings.TrimSpace(req.Title)
	if req.Title == "" {
		return nil, bad("A task needs a title.")
	}
	if req.Due != 0 && req.Due < nowMs() {
		return nil, bad("The due time is already past.")
	}
	res, err := s.db.Exec(`INSERT INTO tasks(user_id, title, notes, due, est, created) VALUES(?,?,?,?,?,?)`,
		a.UserID, truncate(req.Title, 200), truncate(req.Notes, 2000), req.Due, clampInt(req.Est, 1, 20), nowMs())
	if err != nil {
		return nil, err
	}
	id, _ := res.LastInsertId()
	s.hub.notify(a.UserID)
	return map[string]any{"id": id}, nil
}

func (s *Server) taskByID(userID, id int64) (Task, error) {
	for _, t := range s.tasks(userID, true) {
		if t.ID == id {
			return t, nil
		}
	}
	return Task{}, apiErr{404, "No such task."}
}

func (s *Server) patchTask(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var req struct {
		Title  *string `json:"title"`
		Notes  *string `json:"notes"`
		Due    *int64  `json:"due"`
		Est    *int    `json:"est"`
		Status *string `json:"status"`
	}
	if err := readJSON(r, &req); err != nil {
		return nil, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	t, err := s.taskByID(a.UserID, id)
	if err != nil {
		return nil, err
	}
	resp := map[string]any{"ok": true}
	if req.Title != nil && strings.TrimSpace(*req.Title) != "" {
		_, _ = s.db.Exec(`UPDATE tasks SET title=? WHERE id=?`, truncate(strings.TrimSpace(*req.Title), 200), id)
	}
	if req.Notes != nil {
		_, _ = s.db.Exec(`UPDATE tasks SET notes=? WHERE id=?`, truncate(*req.Notes, 2000), id)
	}
	if req.Est != nil {
		_, _ = s.db.Exec(`UPDATE tasks SET est=? WHERE id=?`, clampInt(*req.Est, 1, 20), id)
	}
	if req.Due != nil && *req.Due != t.Due && t.Status == "open" {
		// Pushing a deadline back is the obvious dodge, so it costs. An overdue task's deadline
		// is history: moving it wouldn't undo the penalty, and would hide the debt.
		if t.Penalized {
			return nil, forbidden("This task is already overdue. Finish it, or work the debt off with a 25-minute focus.")
		}
		later := t.Due != 0 && (*req.Due == 0 || *req.Due > t.Due)
		if later && nowMs()-t.Created > editGraceMs {
			s.addLedger(a.UserID, -snoozeCost, 0, "snooze", mustJSON(map[string]any{"task": id, "title": t.Title}))
			resp["coins"] = -snoozeCost
		}
		if *req.Due != 0 && *req.Due < nowMs() {
			return nil, bad("The due time is already past.")
		}
		_, _ = s.db.Exec(`UPDATE tasks SET due=? WHERE id=?`, *req.Due, id)
	}
	if req.Status != nil && *req.Status != t.Status {
		switch *req.Status {
		case "done":
			_, _ = s.db.Exec(`UPDATE tasks SET status='done', done_at=?, debt_cleared=1 WHERE id=?`, nowMs(), id)
			onTime := t.Due == 0 || nowMs() <= t.Due
			coins, xp := 5, 10
			if onTime {
				coins, xp = 20+5*t.Est, 30+10*t.Est
			}
			s.addLedger(a.UserID, coins, xp, "task", mustJSON(map[string]any{"task": id, "title": t.Title, "onTime": onTime}))
			resp["coins"], resp["xp"] = coins, xp
		case "open":
			// Undo: take back exactly what completing paid, so tick-untick can't farm coins.
			var c, x int
			_ = s.db.QueryRow(`SELECT COALESCE(SUM(coins),0), COALESCE(SUM(xp),0) FROM ledger WHERE user_id=? AND reason IN ('task','task-undo') AND json_extract(ref,'$.task')=?`, a.UserID, id).Scan(&c, &x)
			if c != 0 || x != 0 {
				s.addLedger(a.UserID, -c, -x, "task-undo", mustJSON(map[string]any{"task": id, "title": t.Title}))
			}
			// An overdue task's debt comes back with it; completing was what had cleared it.
			_, _ = s.db.Exec(`UPDATE tasks SET status='open', done_at=0, debt_cleared=0 WHERE id=?`, id)
		default:
			return nil, bad("status must be open or done")
		}
	}
	s.hub.notify(a.UserID)
	return resp, nil
}

func (s *Server) deleteTask(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	t, err := s.taskByID(a.UserID, id)
	if err != nil {
		return nil, err
	}
	if t.Penalized && !t.DebtCleared && t.Status == "open" {
		return nil, forbidden("Deleting an overdue task would dodge its lockdown. Finish it or work it off first.")
	}
	_, _ = s.db.Exec(`DELETE FROM tasks WHERE id=? AND user_id=?`, id, a.UserID)
	s.hub.notify(a.UserID)
	return map[string]bool{"ok": true}, nil
}

// ---- Shop ----------------------------------------------------------------------------------

func (s *Server) buy(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	var req struct {
		Item string `json:"item"`
	}
	if err := readJSON(r, &req); err != nil {
		return nil, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	u, err := s.loadUser(a.UserID)
	if err != nil {
		return nil, err
	}
	e := s.enforce(u, a.DeviceID)
	var item *ShopItem
	for _, it := range s.shop(u, e) {
		if it.ID == req.Item {
			it := it
			item = &it
		}
	}
	if item == nil {
		return nil, bad("No such item.")
	}
	if item.Disabled != "" {
		return nil, forbidden(item.Disabled)
	}
	if s.wallet(u).Coins < item.Price {
		return nil, forbidden("Not enough coins.")
	}
	date := u.today()
	s.ensureDay(u.ID, date)
	switch item.ID {
	case "reels5":
		_, _ = s.db.Exec(`UPDATE days SET bonus=bonus+5 WHERE user_id=? AND date=?`, u.ID, date)
	case "pass15":
		_, _ = s.db.Exec(`UPDATE days SET pass_until=? WHERE user_id=? AND date=?`, nowMs()+15*60*1000, u.ID, date)
	case "forgive":
		ids := s.lockdownDebtIDs(u.ID)
		_, _ = s.db.Exec(`UPDATE tasks SET debt_cleared=1 WHERE id=?`, ids[0])
		_, _ = s.db.Exec(`UPDATE days SET penalty=MAX(0, penalty-?) WHERE user_id=? AND date=?`, u.Rules.Penalties.OverdueReels, u.ID, date)
	case "freeze":
		u.Freezes++
		_ = s.saveUserState(u)
	}
	s.addLedger(u.ID, -item.Price, 0, "shop:"+item.ID, date)
	s.hub.notify(u.ID)
	return map[string]any{"ok": true}, nil
}

// ---- Devices -------------------------------------------------------------------------------

func (s *Server) devices(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	return map[string]any{"devices": s.deviceList(a.UserID)}, nil
}

func (s *Server) patchDevice(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var req struct {
		Name string `json:"name"`
	}
	if err := readJSON(r, &req); err != nil {
		return nil, err
	}
	if strings.TrimSpace(req.Name) == "" {
		return nil, bad("name required")
	}
	_, err := s.db.Exec(`UPDATE devices SET name=? WHERE id=? AND user_id=?`, truncate(strings.TrimSpace(req.Name), 60), id, a.UserID)
	s.hub.notify(a.UserID)
	return map[string]bool{"ok": true}, err
}

func (s *Server) deleteDevice(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	_, _ = s.db.Exec(`DELETE FROM tokens WHERE device_id=? AND user_id=?`, id, a.UserID)
	s.hub.notify(a.UserID)
	return map[string]bool{"ok": true}, nil
}

func (s *Server) deviceStatus(w http.ResponseWriter, r *http.Request, a authed) (any, error) {
	var raw json.RawMessage
	if err := readJSON(r, &raw); err != nil {
		return nil, err
	}
	if len(raw) > 8192 {
		return nil, bad("status too large")
	}
	_, err := s.db.Exec(`UPDATE devices SET status=? WHERE id=?`, string(raw), a.DeviceID)
	return map[string]bool{"ok": true}, err
}

const (
	cancelGraceMs = 10_000
	editGraceMs   = 10 * 60 * 1000 // a fresh task's deadline can be fixed for free
	snoozeCost    = 10
)
