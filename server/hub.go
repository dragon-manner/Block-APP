package main

import (
	"context"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// Hub pushes "something changed" to every live device of a user. It carries no data on
// purpose: a device that gets the ping refetches /api/enforce, so a dropped or out-of-order
// message can never leave a device enforcing a stale rule set. Pings are coalesced per user
// so a phone reporting reels one at a time doesn't fan out a refetch storm.
type Hub struct {
	mu      sync.Mutex
	conns   map[int64]map[*websocket.Conn]struct{}
	pending map[int64]bool
}

func newHub() *Hub {
	return &Hub{conns: map[int64]map[*websocket.Conn]struct{}{}, pending: map[int64]bool{}}
}

func (h *Hub) notify(userID int64) {
	h.mu.Lock()
	if h.pending[userID] {
		h.mu.Unlock()
		return
	}
	h.pending[userID] = true
	h.mu.Unlock()
	time.AfterFunc(coalesceWindow, func() {
		h.mu.Lock()
		delete(h.pending, userID)
		set := make([]*websocket.Conn, 0, len(h.conns[userID]))
		for c := range h.conns[userID] {
			set = append(set, c)
		}
		h.mu.Unlock()
		for _, c := range set {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			_ = c.Write(ctx, websocket.MessageText, []byte(`{"type":"changed"}`))
			cancel()
		}
	})
}

func (h *Hub) serve(w http.ResponseWriter, r *http.Request, userID int64) {
	// Origin is not checked: the desktop app, the Android WebView and the extension all connect
	// from origins that aren't this host. Auth is the bearer token in the query string.
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
	if err != nil {
		return
	}
	h.mu.Lock()
	if h.conns[userID] == nil {
		h.conns[userID] = map[*websocket.Conn]struct{}{}
	}
	h.conns[userID][c] = struct{}{}
	h.mu.Unlock()
	defer func() {
		h.mu.Lock()
		delete(h.conns[userID], c)
		h.mu.Unlock()
		c.CloseNow()
	}()
	_ = c.Write(r.Context(), websocket.MessageText, []byte(`{"type":"hello"}`))
	// Pings keep NAT and mobile carriers from silently dropping an idle socket, which otherwise
	// looks open on the phone while no change ever arrives.
	go func() {
		t := time.NewTicker(25 * time.Second)
		defer t.Stop()
		for range t.C {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			err := c.Ping(ctx)
			cancel()
			if err != nil {
				c.CloseNow()
				return
			}
		}
	}()
	for {
		if _, _, err := c.Read(r.Context()); err != nil {
			return
		}
	}
}

const coalesceWindow = 400 * time.Millisecond
