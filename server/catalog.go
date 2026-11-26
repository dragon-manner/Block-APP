package main

import (
	_ "embed"
	"encoding/json"
)

// catalog.json is a copy of shared/catalog.json (make sync copies it). The same file ships in
// the desktop app and the Android app so all three name an app the same way: "youtube" is the
// Android package, the website and the desktop client at once, and its limit is one budget.
//
//go:embed embed/catalog.json
var catalogRaw []byte

type Category struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Distracting bool   `json:"distracting"`
}

type Target struct {
	ID       string   `json:"id"`
	Name     string   `json:"name"`
	Category string   `json:"category"`
	Reels    bool     `json:"reels"`
	Android  []string `json:"android"`
	Domains  []string `json:"domains"`
	Desktop  []string `json:"desktop"`
}

type Catalog struct {
	Version    int        `json:"version"`
	Categories []Category `json:"categories"`
	Targets    []Target   `json:"targets"`
}

func loadCatalog() Catalog {
	var c Catalog
	if err := json.Unmarshal(catalogRaw, &c); err != nil {
		panic("catalog.json: " + err.Error())
	}
	return c
}

func (c Catalog) distracting() map[string]bool {
	m := map[string]bool{}
	for _, cat := range c.Categories {
		if cat.Distracting {
			m[cat.ID] = true
		}
	}
	return m
}

func (c Catalog) distractingList() []string {
	out := []string{}
	for _, cat := range c.Categories {
		if cat.Distracting {
			out = append(out, cat.ID)
		}
	}
	return out
}

func (c Catalog) known(target string) bool {
	for _, t := range c.Targets {
		if t.ID == target {
			return true
		}
	}
	return false
}
