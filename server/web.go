//go:build linux

package main

import (
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"io/fs"
	"net/http"
	"net/url"
	"path"
	"strings"
)

// The web UI is plain static files (no build step) compiled into the binary, so
// the server stays a single file with no web runtime.
//
//go:embed web
var webEmbed embed.FS

type webAsset struct {
	data []byte
	kind string
	etag string
}

var webAssets = loadWebAssets()

var webTypes = map[string]string{
	".html":        "text/html; charset=utf-8",
	".js":          "text/javascript; charset=utf-8",
	".css":         "text/css; charset=utf-8",
	".svg":         "image/svg+xml",
	".png":         "image/png",
	".webmanifest": "application/manifest+json",
}

// No inline script or style, no third-party origins, no framing. The token lives
// only in this origin's storage; a strict CSP is the main XSS barrier.
const webCSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

func loadWebAssets() map[string]webAsset {
	root, err := fs.Sub(webEmbed, "web")
	if err != nil {
		panic(err)
	}
	out := map[string]webAsset{}
	_ = fs.WalkDir(root, ".", func(name string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		kind, ok := webTypes[path.Ext(name)]
		if !ok {
			return nil
		}
		data, err := fs.ReadFile(root, name)
		if err != nil {
			return err
		}
		sum := sha256.Sum256(data)
		out[name] = webAsset{data: data, kind: kind, etag: `"` + hex.EncodeToString(sum[:8]) + `"`}
		return nil
	})
	return out
}

func (a *app) serveWeb(w http.ResponseWriter, r *http.Request) {
	h := w.Header()
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Referrer-Policy", "no-referrer")
	h.Set("X-Frame-Options", "DENY")
	h.Set("Content-Security-Policy", webCSP)
	h.Set("Cross-Origin-Opener-Policy", "same-origin")
	h.Set("Cross-Origin-Resource-Policy", "same-origin")
	h.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), interest-cohort=()")
	if !a.web {
		h.Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(404)
		_, _ = w.Write([]byte("LiteHelm web UI is disabled on this server. The mobile app API is under /api/v1/.\n"))
		return
	}
	if r.Method != "GET" && r.Method != "HEAD" {
		h.Set("Allow", "GET, HEAD")
		h.Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(405)
		return
	}
	name := strings.TrimPrefix(path.Clean("/"+r.URL.Path), "/")
	if name == "" {
		name = "index.html"
	}
	asset, ok := webAssets[name]
	if !ok {
		h.Set("Content-Type", "text/plain; charset=utf-8")
		h.Set("Cache-Control", "no-store")
		w.WriteHeader(404)
		_, _ = w.Write([]byte("Not found\n"))
		return
	}
	// Unhashed file names: always revalidate, cheap 304 via ETag.
	h.Set("Content-Type", asset.kind)
	h.Set("Cache-Control", "no-cache")
	h.Set("ETag", asset.etag)
	if match := r.Header.Get("If-None-Match"); match != "" && strings.Contains(match, asset.etag) {
		w.WriteHeader(304)
		return
	}
	w.WriteHeader(200)
	if r.Method == "GET" {
		_, _ = w.Write(asset.data)
	}
}

// sameOrigin accepts browser requests issued by the page served from this host.
// Sec-Fetch-Site cannot be set by page scripts; Host matching covers older
// browsers when the reverse proxy keeps the original Host header.
func sameOrigin(r *http.Request, origin string) bool {
	if r.Header.Get("Sec-Fetch-Site") == "same-origin" {
		return true
	}
	u, err := url.Parse(origin)
	if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") {
		return false
	}
	return strings.EqualFold(u.Host, r.Host)
}
