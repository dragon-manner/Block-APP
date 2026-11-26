package main

import (
	"encoding/json"
	"reflect"
	"sort"
	"strconv"
	"strings"
)

// Rules are the one synced policy every device enforces. They follow the rule the Android app
// always had: a change that tightens applies now, a change that loosens waits until tomorrow.
// Without that, "raise my limit" is an in-app off switch the moment a craving hits.
type AppLimit struct {
	Target  string `json:"target"`
	Minutes int    `json:"minutes"`
}

type Quiet struct {
	Enabled    bool     `json:"enabled"`
	From       string   `json:"from"` // "23:00"
	To         string   `json:"to"`   // "07:00"
	BlockReels bool     `json:"blockReels"`
	Categories []string `json:"categories"`
}

type Penalties struct {
	OverdueReels int  `json:"overdueReels"` // reels taken off today's allowance per overdue task
	OverdueCoins int  `json:"overdueCoins"`
	Lockdown     bool `json:"lockdown"` // overdue task locks distracting apps everywhere until paid off
	GiveUpCoins  int  `json:"giveUpCoins"`
	GiveUpReels  int  `json:"giveUpReels"`
}

type Taper struct {
	Enabled    bool   `json:"enabled"`
	Goal       int    `json:"goal"`       // floor the limit steps down to
	WeeklyDrop int    `json:"weeklyDrop"` // percent off each week
	Started    string `json:"started"`    // date the taper began; steps land every 7 days after
}

type Rules struct {
	ReelLimit         *int              `json:"reelLimit"`
	AppLimits         []AppLimit        `json:"appLimits"`
	Quiet             Quiet             `json:"quiet"`
	FocusCategories   []string          `json:"focusCategories"`
	Penalties         Penalties         `json:"penalties"`
	Taper             Taper             `json:"taper"`
	CategoryOverrides map[string]string `json:"categoryOverrides"`
	DailyFocusGoal    int               `json:"dailyFocusGoal"` // minutes; not a restriction
}

func defaultRules() Rules {
	return Rules{
		AppLimits:         []AppLimit{},
		Quiet:             Quiet{From: "23:30", To: "07:00", BlockReels: true, Categories: []string{"social", "video"}},
		FocusCategories:   []string{"social", "video", "games", "news", "shopping"},
		Penalties:         Penalties{OverdueReels: 5, OverdueCoins: 15, Lockdown: true, GiveUpCoins: 10, GiveUpReels: 3},
		CategoryOverrides: map[string]string{},
		DailyFocusGoal:    100,
	}
}

func parseRules(s string) Rules {
	r := defaultRules()
	if s != "" && s != "{}" {
		_ = json.Unmarshal([]byte(s), &r)
	}
	r.normalize()
	return r
}

func (r *Rules) normalize() {
	if r.AppLimits == nil {
		r.AppLimits = []AppLimit{}
	}
	if r.FocusCategories == nil {
		r.FocusCategories = []string{}
	}
	if r.Quiet.Categories == nil {
		r.Quiet.Categories = []string{}
	}
	if r.CategoryOverrides == nil {
		r.CategoryOverrides = map[string]string{}
	}
	if r.ReelLimit != nil && *r.ReelLimit < 0 {
		r.ReelLimit = nil
	}
	seen := map[string]bool{}
	out := r.AppLimits[:0]
	for _, l := range r.AppLimits {
		if l.Target == "" || l.Minutes < 0 || seen[l.Target] {
			continue
		}
		seen[l.Target] = true
		out = append(out, l)
	}
	r.AppLimits = out
	sort.Slice(r.AppLimits, func(i, j int) bool { return r.AppLimits[i].Target < r.AppLimits[j].Target })
	sort.Strings(r.FocusCategories)
	sort.Strings(r.Quiet.Categories)
	clamp := func(v *int, lo, hi int) {
		if *v < lo {
			*v = lo
		}
		if *v > hi {
			*v = hi
		}
	}
	clamp(&r.Penalties.OverdueReels, 0, 100)
	clamp(&r.Penalties.OverdueCoins, 0, 1000)
	clamp(&r.Penalties.GiveUpCoins, 0, 1000)
	clamp(&r.Penalties.GiveUpReels, 0, 100)
	clamp(&r.Taper.WeeklyDrop, 0, 90)
	clamp(&r.DailyFocusGoal, 0, 1440)
}

func (r Rules) json() string {
	b, _ := json.Marshal(r)
	return string(b)
}

func (r Rules) hasRestrictions() bool {
	return r.ReelLimit != nil || len(r.AppLimits) > 0 || r.Quiet.Enabled
}

// tighten returns what applies right now when the user asks for req while cur is in force.
// Every field takes the stricter of the two. Whatever of req is looser is returned through
// pending=true and becomes the whole rule set at the next day boundary.
func tighten(cur, req Rules, distracting map[string]bool) (eff Rules, pending bool) {
	eff = req

	// Reel limit: nil means unlimited, 0 is a real "no reels".
	switch {
	case cur.ReelLimit != nil && req.ReelLimit == nil:
		v := *cur.ReelLimit
		eff.ReelLimit = &v
	case cur.ReelLimit != nil && req.ReelLimit != nil && *cur.ReelLimit < *req.ReelLimit:
		v := *cur.ReelLimit
		eff.ReelLimit = &v
	}

	// App limits: removing one or raising it waits.
	curMap := map[string]int{}
	for _, l := range cur.AppLimits {
		curMap[l.Target] = l.Minutes
	}
	reqMap := map[string]int{}
	for _, l := range req.AppLimits {
		reqMap[l.Target] = l.Minutes
	}
	eff.AppLimits = []AppLimit{}
	for t, m := range reqMap {
		if c, ok := curMap[t]; ok && c < m {
			m = c
		}
		eff.AppLimits = append(eff.AppLimits, AppLimit{t, m})
	}
	for t, c := range curMap {
		if _, ok := reqMap[t]; !ok {
			eff.AppLimits = append(eff.AppLimits, AppLimit{t, c})
		}
	}

	// Quiet hours: only a superset (more minutes, more categories, reels at least as blocked)
	// applies now. Anything else keeps the current window until tomorrow.
	if cur.Quiet.Enabled {
		if !req.Quiet.Enabled || !quietCovers(req.Quiet, cur.Quiet) {
			eff.Quiet = cur.Quiet
		}
	}

	eff.FocusCategories = union(cur.FocusCategories, req.FocusCategories)

	p := &eff.Penalties
	p.OverdueReels = max(cur.Penalties.OverdueReels, req.Penalties.OverdueReels)
	p.OverdueCoins = max(cur.Penalties.OverdueCoins, req.Penalties.OverdueCoins)
	p.GiveUpCoins = max(cur.Penalties.GiveUpCoins, req.Penalties.GiveUpCoins)
	p.GiveUpReels = max(cur.Penalties.GiveUpReels, req.Penalties.GiveUpReels)
	p.Lockdown = cur.Penalties.Lockdown || req.Penalties.Lockdown

	if cur.Taper.Enabled {
		if !req.Taper.Enabled || req.Taper.Goal > cur.Taper.Goal || req.Taper.WeeklyDrop < cur.Taper.WeeklyDrop {
			eff.Taper = cur.Taper
		}
	}

	// Re-categorising an app out of a distracting category would lift every block on it.
	eff.CategoryOverrides = map[string]string{}
	for t, c := range cur.CategoryOverrides {
		eff.CategoryOverrides[t] = c
	}
	for t, c := range req.CategoryOverrides {
		if distracting[c] {
			eff.CategoryOverrides[t] = c
		}
	}

	eff.normalize()
	r2 := req
	r2.normalize()
	return eff, !reflect.DeepEqual(eff, r2)
}

func quietCovers(a, b Quiet) bool { // does a cover b?
	am, bm := quietMinutes(a), quietMinutes(b)
	for i := range bm {
		if bm[i] && !am[i] {
			return false
		}
	}
	if b.BlockReels && !a.BlockReels {
		return false
	}
	set := map[string]bool{}
	for _, c := range a.Categories {
		set[c] = true
	}
	for _, c := range b.Categories {
		if !set[c] {
			return false
		}
	}
	return true
}

func quietMinutes(q Quiet) [1440]bool {
	var m [1440]bool
	f, t := hhmm(q.From), hhmm(q.To)
	if f < 0 || t < 0 || f == t {
		return m
	}
	for i := f; i != t; i = (i + 1) % 1440 {
		m[i] = true
	}
	return m
}

func hhmm(s string) int {
	parts := strings.Split(s, ":")
	if len(parts) != 2 {
		return -1
	}
	h, e1 := strconv.Atoi(parts[0])
	m, e2 := strconv.Atoi(parts[1])
	if e1 != nil || e2 != nil || h < 0 || h > 23 || m < 0 || m > 59 {
		return -1
	}
	return h*60 + m
}

func union(a, b []string) []string {
	set := map[string]bool{}
	for _, x := range a {
		set[x] = true
	}
	for _, x := range b {
		set[x] = true
	}
	out := make([]string, 0, len(set))
	for x := range set {
		out = append(out, x)
	}
	sort.Strings(out)
	return out
}
