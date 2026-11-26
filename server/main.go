// Command keel-server is the sync hub for Keel: one account, every device's screen time in one
// place, and one set of limits every device enforces. It is a single static binary with an
// embedded SQLite database and web UI, sized for a 1GB VPS (about 10–15MB resident).
package main

import (
	"crypto/tls"
	"embed"
	"flag"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path"
	"strings"
	"time"
	_ "time/tzdata" // the VPS may not ship zoneinfo; day boundaries depend on it

	"golang.org/x/crypto/acme/autocert"
)

//go:embed all:embed/web
var webFS embed.FS

func main() {
	addr := flag.String("addr", ":8080", "listen address when -domain is not set")
	dbPath := flag.String("db", "keel.db", "SQLite database path")
	domain := flag.String("domain", "", "serve HTTPS on :443 for this domain with Let's Encrypt (e.g. 1-2-3-4.sslip.io)")
	certDir := flag.String("certs", "certs", "where Let's Encrypt certificates are cached")
	open := flag.Bool("open-signup", false, "let anyone register (default: only the first account)")
	flag.Parse()

	db, err := openDB(*dbPath)
	if err != nil {
		log.Fatal(err)
	}
	s := &Server{db: db, hub: newHub(), catalog: loadCatalog(), openSignup: *open}
	go s.ticker()

	mux := http.NewServeMux()
	s.routes(mux)
	sub, _ := fs.Sub(webFS, "embed/web")
	mux.Handle("/", spa(sub))
	h := withCORS(logRequests(mux))

	if *domain == "" {
		log.Printf("keel-server on %s (db %s)", *addr, *dbPath)
		log.Fatal(http.ListenAndServe(*addr, h))
	}
	m := &autocert.Manager{
		Prompt:     autocert.AcceptTOS,
		HostPolicy: autocert.HostWhitelist(*domain),
		Cache:      autocert.DirCache(*certDir),
	}
	go func() {
		log.Fatal(http.ListenAndServe(":80", m.HTTPHandler(nil)))
	}()
	srv := &http.Server{
		Addr:              ":443",
		Handler:           h,
		TLSConfig:         &tls.Config{GetCertificate: m.GetCertificate, MinVersion: tls.VersionTLS12},
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("keel-server on https://%s (db %s)", *domain, *dbPath)
	log.Fatal(srv.ListenAndServeTLS("", ""))
}

// ticker makes consequences land on time even when no device is asking: a session ends, an
// overdue task bites, a day settles and tomorrow's rules take over, all without a client.
func (s *Server) ticker() {
	t := time.NewTicker(20 * time.Second)
	defer t.Stop()
	for range t.C {
		rows, err := s.db.Query(`SELECT id FROM users`)
		if err != nil {
			continue
		}
		var ids []int64
		for rows.Next() {
			var id int64
			_ = rows.Scan(&id)
			ids = append(ids, id)
		}
		rows.Close()
		for _, id := range ids {
			_, _ = s.freshUser(id)
		}
	}
}

func withCORS(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Bearer tokens, never cookies, so a wildcard origin can't be used to ride a session.
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Max-Age", "86400")
		if r.Method == http.MethodOptions {
			w.WriteHeader(204)
			return
		}
		h.ServeHTTP(w, r)
	})
}

func logRequests(h http.Handler) http.Handler {
	if os.Getenv("KEEL_LOG") == "" {
		return h
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		h.ServeHTTP(w, r)
		log.Printf("%s %s %s", r.Method, r.URL.Path, time.Since(start))
	})
}

func spa(root fs.FS) http.Handler {
	files := http.FileServer(http.FS(root))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := strings.TrimPrefix(path.Clean(r.URL.Path), "/")
		if p == "" {
			p = "index.html"
		}
		if _, err := fs.Stat(root, p); err != nil {
			r.URL.Path = "/"
		}
		if strings.HasPrefix(p, "assets/") {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		}
		files.ServeHTTP(w, r)
	})
}
