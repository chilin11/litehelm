//go:build linux

package main

import (
	"net/http/httptest"
	"strings"
	"testing"
)

func webRequest(a *app, method, path string, headers map[string]string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, nil)
	r.Host = "panel.example.com"
	for k, v := range headers {
		r.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	a.ServeHTTP(w, r)
	return w
}

func TestWebAssetsServedWithoutToken(t *testing.T) {
	a := testApp()
	a.web = true
	for path, kind := range map[string]string{
		"/":                     "text/html",
		"/index.html":           "text/html",
		"/app.js":               "text/javascript",
		"/i18n.js":              "text/javascript",
		"/app.css":              "text/css",
		"/icon.svg":             "image/svg+xml",
		"/manifest.webmanifest": "application/manifest+json",
	} {
		w := webRequest(a, "GET", path, nil)
		if w.Code != 200 || !strings.HasPrefix(w.Header().Get("Content-Type"), kind) {
			t.Fatalf("%s: %d %q", path, w.Code, w.Header().Get("Content-Type"))
		}
		h := w.Header()
		if !strings.Contains(h.Get("Content-Security-Policy"), "script-src 'self'") || h.Get("X-Frame-Options") != "DENY" ||
			h.Get("X-Content-Type-Options") != "nosniff" || h.Get("Referrer-Policy") != "no-referrer" || h.Get("ETag") == "" {
			t.Fatalf("%s: missing security headers %v", path, h)
		}
		if w.Body.Len() == 0 {
			t.Fatalf("%s: empty body", path)
		}
	}
	index := webRequest(a, "GET", "/", nil)
	if !strings.Contains(index.Body.String(), `src="app.js"`) || strings.Contains(index.Body.String(), "<script>") {
		t.Fatal("index must load only external module scripts")
	}
	if w := webRequest(a, "GET", "/", map[string]string{"If-None-Match": index.Header().Get("ETag")}); w.Code != 304 || w.Body.Len() != 0 {
		t.Fatalf("etag revalidation: %d", w.Code)
	}
	if w := webRequest(a, "HEAD", "/app.js", nil); w.Code != 200 || w.Body.Len() != 0 {
		t.Fatalf("head: %d %d", w.Code, w.Body.Len())
	}
	for _, path := range []string{"/missing.js", "/../main.go", "/web/app.js", "/main.go", "/%2e%2e/main.go"} {
		if w := webRequest(a, "GET", path, nil); w.Code != 404 {
			t.Fatalf("%s: %d", path, w.Code)
		}
	}
	if w := webRequest(a, "POST", "/", nil); w.Code != 405 {
		t.Fatalf("post web: %d", w.Code)
	}
}

func TestWebDisabledKeepsAPI(t *testing.T) {
	a := testApp()
	a.web = false
	if w := webRequest(a, "GET", "/", nil); w.Code != 404 || !strings.Contains(w.Body.String(), "disabled") {
		t.Fatalf("disabled web: %d %s", w.Code, w.Body.String())
	}
	if w := request(a, "GET", "/api/v1/system", "", testToken); w.Code != 200 {
		t.Fatalf("api with web disabled: %d", w.Code)
	}
}

func TestAPIPathsStillRequireToken(t *testing.T) {
	a := testApp()
	a.web = true
	for _, path := range []string{"/api", "/api/", "/api/v1/system", "/api/v1/unknown"} {
		if w := webRequest(a, "GET", path, nil); w.Code != 401 || !strings.HasPrefix(w.Header().Get("Content-Type"), "application/json") {
			t.Fatalf("%s: %d", path, w.Code)
		}
	}
	// /apix is not the API namespace and is not a web asset either.
	if w := webRequest(a, "GET", "/apix", nil); w.Code != 404 || strings.HasPrefix(w.Header().Get("Content-Type"), "application/json") {
		t.Fatalf("/apix: %d", w.Code)
	}
}

func TestBrowserOriginRules(t *testing.T) {
	a := testApp()
	a.web = true
	auth := "Bearer " + testToken
	cases := []struct {
		name    string
		headers map[string]string
		want    int
	}{
		{"app without origin", map[string]string{"Authorization": auth}, 200},
		{"same host origin", map[string]string{"Authorization": auth, "Origin": "https://panel.example.com"}, 200},
		{"same host origin case", map[string]string{"Authorization": auth, "Origin": "https://PANEL.example.com"}, 200},
		{"fetch metadata same-origin", map[string]string{"Authorization": auth, "Origin": "https://proxy-rewrote-host.example", "Sec-Fetch-Site": "same-origin"}, 200},
		{"cross origin", map[string]string{"Authorization": auth, "Origin": "https://evil.example"}, 403},
		{"cross site metadata", map[string]string{"Authorization": auth, "Origin": "https://evil.example", "Sec-Fetch-Site": "cross-site"}, 403},
		{"same-site subdomain", map[string]string{"Authorization": auth, "Origin": "https://evil.panel.example.com", "Sec-Fetch-Site": "same-site"}, 403},
		{"null origin", map[string]string{"Authorization": auth, "Origin": "null"}, 403},
		{"port mismatch", map[string]string{"Authorization": auth, "Origin": "https://panel.example.com:8443"}, 403},
		{"same origin without token", map[string]string{"Origin": "https://panel.example.com"}, 401},
	}
	for _, c := range cases {
		if w := webRequest(a, "GET", "/api/v1/system", c.headers); w.Code != c.want {
			t.Fatalf("%s: got %d want %d (%s)", c.name, w.Code, c.want, w.Body.String())
		}
	}
}
