package main

import (
	"encoding/json"
	"sort"
)

// Enforce is the device-relative contract every client applies. Usage numbers are split into
// "others" (every other device, from the server) so a client adds its own live, unsynced count
// on top: a phone that just watched reel 30 of 30 blocks reel 31 without waiting for a round
// trip, and the laptop blocks it the moment the phone's report lands.
type Enforce struct {
	Now        int64             `json:"now"`
	Date       string            `json:"date"`
	TZ         string            `json:"tz"`
	Reels      ReelState         `json:"reels"`
	Limits     []LimitState      `json:"limits"`
	Focus      *FocusState       `json:"focus"`
	Lockdown   LockdownState     `json:"lockdown"`
	Quiet      Quiet             `json:"quiet"`
	PassUntil  int64             `json:"passUntil"`
	Categories map[string]string `json:"categoryOverrides"`
	Armed      bool              `json:"armed"`
}

type ReelState struct {
	Limit  *int `json:"limit"` // effective: base + bought - penalties; nil = unlimited
	Base   *int `json:"base"`
	Bonus  int  `json:"bonus"`
	Debt   int  `json:"penalty"`
	Others int  `json:"others"`
	Mine   int  `json:"mine"`
}

type LimitState struct {
	Target  string `json:"target"`
	Minutes int    `json:"minutes"`
	Others  int    `json:"others"` // seconds
	Mine    int    `json:"mine"`
}

type Phase struct {
	Kind  string `json:"kind"` // work | break
	Start int64  `json:"start"`
	End   int64  `json:"end"`
}

type FocusState struct {
	ID         int64    `json:"id"`
	Mode       string   `json:"mode"`
	Label      string   `json:"label"`
	TaskID     int64    `json:"taskId"`
	Started    int64    `json:"started"`
	Ends       int64    `json:"ends"`
	WorkMin    int      `json:"workMin"`
	BreakMin   int      `json:"breakMin"`
	Cycles     int      `json:"cycles"`
	Strict     bool     `json:"strict"`
	Categories []string `json:"categories"`
	Phases     []Phase  `json:"phases"`
	Seed       int64    `json:"seed"`
	DeviceID   int64    `json:"deviceId"`
}

type LockdownState struct {
	Active     bool     `json:"active"`
	Tasks      []string `json:"tasks"`
	Categories []string `json:"categories"`
}

func phasesFor(f *FocusState) []Phase {
	var out []Phase
	t := f.Started
	for i := 0; i < f.Cycles; i++ {
		end := t + int64(f.WorkMin)*60000
		out = append(out, Phase{"work", t, end})
		t = end
		if i < f.Cycles-1 && f.BreakMin > 0 {
			end = t + int64(f.BreakMin)*60000
			out = append(out, Phase{"break", t, end})
			t = end
		}
	}
	return out
}

func (s *Server) activeFocus(userID int64) *FocusState {
	f := &FocusState{}
	var cats string
	var strict int
	err := s.db.QueryRow(`SELECT id, mode, label, task_id, started, ends, work_min, break_min, cycles, strict, categories, seed, device_id
		FROM sessions WHERE user_id=? AND status='active' ORDER BY id DESC LIMIT 1`, userID).
		Scan(&f.ID, &f.Mode, &f.Label, &f.TaskID, &f.Started, &f.Ends, &f.WorkMin, &f.BreakMin, &f.Cycles, &strict, &cats, &f.Seed, &f.DeviceID)
	if err != nil {
		return nil
	}
	f.Strict = strict == 1
	_ = json.Unmarshal([]byte(cats), &f.Categories)
	f.Phases = phasesFor(f)
	return f
}

func (s *Server) lockdown(u *User) LockdownState {
	ld := LockdownState{Tasks: []string{}, Categories: []string{}}
	if !u.Rules.Penalties.Lockdown {
		return ld
	}
	rows, err := s.db.Query(`SELECT title FROM tasks WHERE user_id=? AND status='open' AND penalized=1 AND debt_cleared=0 ORDER BY due`, u.ID)
	if err != nil {
		return ld
	}
	defer rows.Close()
	for rows.Next() {
		var t string
		_ = rows.Scan(&t)
		ld.Tasks = append(ld.Tasks, t)
	}
	if len(ld.Tasks) > 0 {
		ld.Active = true
		ld.Categories = s.catalog.distractingList()
	}
	return ld
}

func (s *Server) reelsByDevice(userID int64, date string) map[int64]int {
	out := map[int64]int{}
	rows, err := s.db.Query(`SELECT device_id, reels FROM hours WHERE user_id=? AND date=?`, userID, date)
	if err != nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var d int64
		var n int
		_ = rows.Scan(&d, &n)
		out[d] = n
	}
	return out
}

func (s *Server) reelState(u *User, date string, deviceID int64) ReelState {
	rs := ReelState{}
	for d, n := range s.reelsByDevice(u.ID, date) {
		if d == deviceID {
			rs.Mine = n
		} else {
			rs.Others += n
		}
	}
	day := s.day(u.ID, date)
	rs.Bonus, rs.Debt = day.Bonus, day.Penalty
	if u.Rules.ReelLimit != nil {
		base := *u.Rules.ReelLimit
		eff := max(0, base+day.Bonus-day.Penalty)
		rs.Base, rs.Limit = &base, &eff
	}
	return rs
}

func (s *Server) enforce(u *User, deviceID int64) Enforce {
	date := u.today()
	e := Enforce{
		Now: nowMs(), Date: date, TZ: u.TZ,
		Reels:      s.reelState(u, date, deviceID),
		Limits:     []LimitState{},
		Focus:      s.activeFocus(u.ID),
		Lockdown:   s.lockdown(u),
		Quiet:      u.Rules.Quiet,
		Categories: u.Rules.CategoryOverrides,
	}
	e.PassUntil = s.day(u.ID, date).PassUntil
	for _, l := range u.Rules.AppLimits {
		ls := LimitState{Target: l.Target, Minutes: l.Minutes}
		rows, err := s.db.Query(`SELECT device_id, SUM(seconds) FROM usage WHERE user_id=? AND date=? AND target=? GROUP BY device_id`, u.ID, date, l.Target)
		if err == nil {
			for rows.Next() {
				var d int64
				var sec int
				_ = rows.Scan(&d, &sec)
				if d == deviceID {
					ls.Mine = sec
				} else {
					ls.Others += sec
				}
			}
			rows.Close()
		}
		e.Limits = append(e.Limits, ls)
	}
	e.Armed = u.Rules.hasRestrictions() || e.Focus != nil || e.Lockdown.Active
	return e
}

// ---- Economy -------------------------------------------------------------------------------

type Wallet struct {
	Coins      int `json:"coins"`
	XP         int `json:"xp"`
	Level      int `json:"level"`
	LevelFloor int `json:"levelFloor"`
	LevelNext  int `json:"levelNext"`
	Streak     int `json:"streak"`
	Best       int `json:"bestStreak"`
	Freezes    int `json:"freezes"`
}

func levelFor(xp int) (lvl, floor, next int) {
	lvl = 1
	for xpForLevel(lvl+1) <= xp {
		lvl++
	}
	return lvl, xpForLevel(lvl), xpForLevel(lvl + 1)
}

func xpForLevel(l int) int { return 60 * (l - 1) * (l - 1) } // 0, 60, 240, 540, 960 ...

func (s *Server) wallet(u *User) Wallet {
	w := Wallet{Streak: u.Streak, Best: u.Best, Freezes: u.Freezes}
	_ = s.db.QueryRow(`SELECT COALESCE(SUM(coins),0), COALESCE(SUM(CASE WHEN xp>0 THEN xp ELSE 0 END),0) FROM ledger WHERE user_id=?`, u.ID).Scan(&w.Coins, &w.XP)
	w.Level, w.LevelFloor, w.LevelNext = levelFor(w.XP)
	return w
}

type ShopItem struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Desc     string `json:"desc"`
	Price    int    `json:"price"`
	Left     int    `json:"left"` // purchases still allowed today
	Disabled string `json:"disabled"`
}

var shopCatalog = []ShopItem{
	{ID: "reels5", Name: "+5 reels", Desc: "Five more reels today, on every device.", Price: 40, Left: 3},
	{ID: "pass15", Name: "15-min breather", Desc: "App time limits pause for 15 minutes. Focus, lockdown and reels still apply.", Price: 60, Left: 2},
	{ID: "forgive", Name: "Forgiveness", Desc: "Clears the oldest overdue-task penalty and gives its reels back.", Price: 120, Left: 1},
	{ID: "freeze", Name: "Streak freeze", Desc: "Protects your streak through one bad day. Hold up to 2.", Price: 100, Left: 2},
}

func (s *Server) shop(u *User, e Enforce) []ShopItem {
	date := u.today()
	out := make([]ShopItem, 0, len(shopCatalog))
	for _, it := range shopCatalog {
		it.Left -= s.ledgerCount(u.ID, "shop:"+it.ID, date)
		switch it.ID {
		case "reels5":
			if e.Reels.Limit == nil {
				it.Disabled = "No reel limit set"
			} else if e.Lockdown.Active {
				it.Disabled = "Not during a lockdown"
			}
		case "pass15":
			if len(u.Rules.AppLimits) == 0 {
				it.Disabled = "No app limits set"
			} else if e.PassUntil > e.Now {
				it.Disabled = "A breather is running"
			}
		case "forgive":
			if len(s.lockdownDebtIDs(u.ID)) == 0 {
				it.Disabled = "Nothing to forgive"
			}
		case "freeze":
			it.Left = min(it.Left, 2-u.Freezes)
		}
		if it.Left <= 0 && it.Disabled == "" {
			it.Disabled = "Sold out today"
		}
		out = append(out, it)
	}
	return out
}

func (s *Server) lockdownDebtIDs(userID int64) []int64 {
	var ids []int64
	rows, err := s.db.Query(`SELECT id FROM tasks WHERE user_id=? AND penalized=1 AND debt_cleared=0 AND status='open' ORDER BY due`, userID)
	if err != nil {
		return ids
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		_ = rows.Scan(&id)
		ids = append(ids, id)
	}
	return ids
}

// completeDueSessions closes focus sessions whose last phase has ended and pays them out.
// It runs from the ticker and on every state read, so a session finishes on time even if
// every device that could have said "done" is asleep.
func (s *Server) completeDueSessions(u *User) bool {
	f := s.activeFocus(u.ID)
	if f == nil || nowMs() < f.Ends {
		return false
	}
	work := f.WorkMin * f.Cycles
	_, _ = s.db.Exec(`UPDATE sessions SET status='completed', ended=? WHERE id=?`, f.Ends, f.ID)
	coins, xp := work, work*2
	if f.Mode == "pomodoro" && f.Cycles >= 4 {
		coins += 15 // a full set is the hard part
	}
	s.addLedger(u.ID, coins, xp, "focus", mustJSON(map[string]any{"session": f.ID, "minutes": work}))
	if f.TaskID != 0 {
		_, _ = s.db.Exec(`UPDATE tasks SET focus_min=focus_min+? WHERE id=? AND user_id=?`, work, f.TaskID, u.ID)
	}
	// Working it off: 25 focused minutes lift the oldest lockdown, task still open.
	if work >= 25 {
		if ids := s.lockdownDebtIDs(u.ID); len(ids) > 0 {
			_, _ = s.db.Exec(`UPDATE tasks SET debt_cleared=1 WHERE id=?`, ids[0])
		}
	}
	date := u.dateOf(f.Started)
	if u.Rules.DailyFocusGoal > 0 && s.ledgerCount(u.ID, "focus-goal", date) == 0 && s.focusMinutes(u, date) >= u.Rules.DailyFocusGoal {
		s.addLedger(u.ID, 25, 50, "focus-goal", date)
	}
	return true
}

func (s *Server) focusMinutes(u *User, date string) int {
	from := u.dayStart(date)
	to := u.dayStart(addDays(date, 1))
	var n int
	_ = s.db.QueryRow(`SELECT COALESCE(SUM(work_min*cycles),0) FROM sessions WHERE user_id=? AND status='completed' AND started>=? AND started<?`, u.ID, from, to).Scan(&n)
	return n
}

// penalizeOverdue fires each overdue task's consequence exactly once.
func (s *Server) penalizeOverdue(u *User) bool {
	rows, err := s.db.Query(`SELECT id, title FROM tasks WHERE user_id=? AND status='open' AND penalized=0 AND due>0 AND due<?`, u.ID, nowMs())
	if err != nil {
		return false
	}
	type t struct {
		id    int64
		title string
	}
	var due []t
	for rows.Next() {
		var x t
		_ = rows.Scan(&x.id, &x.title)
		due = append(due, x)
	}
	rows.Close()
	if len(due) == 0 {
		return false
	}
	date := u.today()
	s.ensureDay(u.ID, date)
	p := u.Rules.Penalties
	for _, x := range due {
		_, _ = s.db.Exec(`UPDATE tasks SET penalized=1 WHERE id=?`, x.id)
		_, _ = s.db.Exec(`UPDATE days SET penalty=penalty+? WHERE user_id=? AND date=?`, p.OverdueReels, u.ID, date)
		s.addLedger(u.ID, -p.OverdueCoins, 0, "overdue", mustJSON(map[string]any{"task": x.id, "title": x.title, "reels": p.OverdueReels}))
	}
	return true
}

// rollover settles every finished day since the last one and applies tomorrow's rules.
func (s *Server) rollover(u *User) bool {
	today := u.today()
	yesterday := addDays(today, -1)
	changed := false
	if u.Settled == "" {
		u.Settled = yesterday
		changed = true
	}
	for i := 0; u.Settled < yesterday && i < 14; i++ {
		d := addDays(u.Settled, 1)
		s.settle(u, d)
		u.Settled = d
		changed = true
	}
	if u.Settled < yesterday { // offline for weeks: skip the gap rather than settle it all
		u.Settled = yesterday
	}
	if u.Pending != nil && u.PendingFrom != "" && today >= u.PendingFrom {
		u.Rules = *u.Pending
		u.Pending, u.PendingFrom = nil, ""
		changed = true
	}
	t := &u.Rules.Taper
	if t.Enabled && u.Rules.ReelLimit != nil {
		if t.Started == "" {
			t.Started = today
			changed = true
		}
		for daysBetween(t.Started, today) >= 7 {
			cur := *u.Rules.ReelLimit
			next := max(t.Goal, cur*(100-t.WeeklyDrop)/100)
			u.Rules.ReelLimit = &next
			t.Started = addDays(t.Started, 7)
			changed = true
		}
	}
	if changed {
		_ = s.saveUserState(u)
	}
	return changed
}

func (s *Server) settle(u *User, date string) {
	s.ensureDay(u.ID, date)
	var reported int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM hours WHERE user_id=? AND date=?`, u.ID, date).Scan(&reported)
	result := ""
	switch {
	case reported == 0:
		result = "nodata" // no device ran: neither extends nor breaks a streak
	case !u.Rules.hasRestrictions():
		result = "free"
	default:
		clean := true
		rs := s.reelState(u, date, -1)
		if rs.Limit != nil && rs.Others > *rs.Limit {
			clean = false
		}
		for _, l := range u.Rules.AppLimits {
			var sec int
			_ = s.db.QueryRow(`SELECT COALESCE(SUM(seconds),0) FROM usage WHERE user_id=? AND date=? AND target=?`, u.ID, date, l.Target).Scan(&sec)
			if sec > l.Minutes*60+limitToleranceSec {
				clean = false
			}
		}
		if clean {
			result = "clean"
			u.Streak++
			u.Best = max(u.Best, u.Streak)
			s.addLedger(u.ID, 20, 40, "clean-day", date)
			for _, m := range []int{3, 7, 14, 30, 60, 100, 365} {
				if u.Streak == m {
					s.addLedger(u.ID, m*5, m*10, "streak", mustJSON(map[string]any{"days": m, "date": date}))
				}
			}
		} else if u.Freezes > 0 {
			u.Freezes--
			result = "frozen"
		} else {
			result = "broken"
			u.Streak = 0
		}
	}
	var limit any
	if u.Rules.ReelLimit != nil {
		limit = *u.Rules.ReelLimit
	}
	_, _ = s.db.Exec(`UPDATE days SET result=?, reel_limit=? WHERE user_id=? AND date=?`, result, limit, u.ID, date)
}

func sortedKeys[V any](m map[string]V) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// A device reporting every 30s can overshoot a limit by up to a report before the others hear.
const limitToleranceSec = 120
