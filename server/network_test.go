//go:build linux

package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestNetworkListenPolicy(t *testing.T) {
	for _, tc := range []struct {
		s      string
		public bool // rejected even with private listening enabled
		local  bool // accepted without private opt-in
	}{
		{"127.255.0.1:1", false, true}, {"[::1]:65535", false, true}, {"[::ffff:127.0.0.1]:9091", false, true},
		{"10.0.0.1:80", false, false}, {"172.31.255.254:80", false, false}, {"192.168.1.201:9091", false, false},
		{"[fc00::1]:80", false, false}, {"[fdff::1]:80", false, false}, {"100.127.255.254:80", false, false}, {"[::ffff:100.64.0.1]:80", false, false},
		{"0.0.0.0:80", true, false}, {"[::]:80", true, false}, {"[::ffff:0.0.0.0]:80", true, false},
		{"8.8.8.8:80", true, false}, {"[2001:4860::1]:80", true, false}, {"[::ffff:8.8.8.8]:80", true, false},
		{"[fe80::1]:80", true, false}, {"[fd00::1%eth0]:80", true, false}, {"169.254.1.1:80", true, false},
		{"224.0.0.1:80", true, false}, {"[ff02::1]:80", true, false}, {"localhost:80", true, false},
		{":9091", true, false}, {"127.0.0.1:0", true, false}, {"127.0.0.1:65536", true, false}, {"127.0.0.1:http", true, false},
	} {
		for _, private := range []bool{false, true} {
			ap, peers, err := networkPolicy(tc.s, private, "", false)
			want := tc.local || (private && !tc.public)
			if (err == nil) != want || (want && (ap.Addr().Is4In6() || (peers != nil) != private)) {
				t.Errorf("networkPolicy(%q, private=%t): addr=%v peers=%v err=%v; want accepted=%t", tc.s, private, ap, peers, err, want)
			}
		}
	}
}

func TestNetworkSourceRanges(t *testing.T) {
	for _, s := range []string{"", "10.0.0.1,", "0.0.0.0/0", "::/0", "127.0.0.1/7", "::1/127", "172.31.0.1/11", "192.168.0.1/15", "100.64.1.1/9", "fd00::1/6", "::ffff:10.0.0.1/95", "::ffff:10.0.0.1/103", "::ffff:8.8.8.8/128", "[::1]", "fd00::1%en0", "10.1.1.1,8.8.8.8"} {
		if _, _, err := networkPolicy("127.0.0.1:9091", false, s, true); err == nil {
			t.Errorf("accepted invalid allow-cidrs %q", s)
		}
	}
	_, ranges, err := networkPolicy("127.0.0.1:9091", false, "::ffff:192.168.1.55/120,100.64.0.1,fd00::/48", true)
	if err != nil {
		t.Fatal(err)
	}
	for peer, want := range map[string]bool{"192.168.1.33:22": true, "[::ffff:192.168.1.1]:80": true, "100.64.0.1:1234": true, "[fd00::1]:80": true, "192.168.2.1:80": false, "127.0.0.1:80": false, "[fd00::1%eth0]:80": false, "192.168.1.1": false, "": false} {
		if ranges.accepts(peer) != want {
			t.Errorf("peer %q: want accepted=%t", peer, want)
		}
	}
}

func TestNetworkRequestGate(t *testing.T) {
	for _, list := range []string{"", "10.0.0.2"} {
		_, peers, err := networkPolicy("10.0.0.3:9091", true, list, list != "")
		if err != nil {
			t.Fatal(err)
		}
		a := &app{peers: peers, web: true}
		for _, path := range []string{"/", "/api", "/api/v1/system"} {
			for _, remote := range []string{"10.0.0.2:1234", "127.0.0.1:1234", "[::ffff:8.8.8.8]:443", "invalid"} {
				r := httptest.NewRequest(http.MethodGet, path, nil)
				r.RemoteAddr = remote
				r.Header.Set("X-Forwarded-For", "10.0.0.2")
				r.Header.Set("CF-Connecting-IP", "10.0.0.2")
				w := httptest.NewRecorder()
				a.ServeHTTP(w, r)
				want := http.StatusForbidden
				if remote == "10.0.0.2:1234" || (remote == "127.0.0.1:1234" && list == "") {
					if path == "/" {
						want = http.StatusOK
					} else {
						want = http.StatusUnauthorized
					}
				}
				if w.Code != want {
					t.Errorf("list=%q %s %s: got %d, want %d", list, remote, path, w.Code, want)
				}
				if want == http.StatusForbidden {
					ct := w.Header().Get("Content-Type")
					if path == "/" && !strings.HasPrefix(ct, "text/plain") || path != "/" && (!strings.HasPrefix(ct, "application/json") || !json.Valid(w.Body.Bytes())) {
						t.Errorf("unexpected denial format for %s: %s %q", path, ct, w.Body.String())
					}
				}
			}
		}
	}
}
