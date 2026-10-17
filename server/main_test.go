//go:build linux

package main

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

const testToken = "litehelm-test-only-not-a-production-token"

func testApp() *app {
	return &app{token: sha256.Sum256([]byte(testToken)), slots: make(chan struct{}, 2), unit: "litehelm.service"}
}
func request(a *app, method, path, body, token string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.Header.Set("Content-Type", "application/json")
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	a.ServeHTTP(w, r)
	return w
}
func TestAuthenticationAndLimits(t *testing.T) {
	a := testApp()
	for _, token := range []string{"", "bad"} {
		if w := request(a, "GET", "/api/v1/system", "", token); w.Code != 401 {
			t.Fatal(w.Code)
		}
	}
	r := httptest.NewRequest("GET", "/api/v1/system", nil)
	r.Header.Set("Authorization", "Bearer "+testToken)
	r.Header.Set("Origin", "https://evil.example")
	w := httptest.NewRecorder()
	a.ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatal(w.Code)
	}
	a.slots <- struct{}{}
	a.slots <- struct{}{}
	if w = request(a, "GET", "/api/v1/system", "", testToken); w.Code != 429 {
		t.Fatal(w.Code)
	}
	<-a.slots
	<-a.slots
	a.mutation.Lock()
	w = request(a, "POST", "/api/v1/power", `{}`, testToken)
	a.mutation.Unlock()
	if w.Code != 409 {
		t.Fatal(w.Code)
	}
	if w = request(a, "POST", "/api/v1/power", `{"action":"reboot"}`, testToken); w.Code != 403 {
		t.Fatal(w.Code)
	}
}
func TestSystemAndProcesses(t *testing.T) {
	a := testApp()
	w := request(a, "GET", "/api/v1/system", "", testToken)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	var data map[string]any
	if e := json.Unmarshal(w.Body.Bytes(), &data); e != nil {
		t.Fatal(e)
	}
	if data["cpu_percent"].(float64) < 0 || data["cpu_percent"].(float64) > 100 {
		t.Fatal(data)
	}
	w = request(a, "GET", "/api/v1/processes?limit=2&sort=cpu", "", testToken)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	var p struct {
		Processes []process `json:"processes"`
	}
	_ = json.Unmarshal(w.Body.Bytes(), &p)
	if len(p.Processes) > 2 {
		t.Fatal(len(p.Processes))
	}
	if !systemd() {
		w = request(a, "GET", "/api/v1/services", "", testToken)
		if !strings.Contains(w.Body.String(), `"supported":false`) {
			t.Fatal(w.Body.String())
		}
	}
}
func TestStatAndInputValidation(t *testing.T) {
	f := make([]string, 22)
	for i := range f {
		f[i] = "0"
	}
	f[0] = "S"
	f[1] = "2"
	f[11] = "100"
	f[12] = "50"
	f[19] = "100"
	f[21] = "3"
	p, e := parseStat("123 (name ) with spaces) "+strings.Join(f, " "), 10)
	if e != nil || p.Name != "name ) with spaces" || p.Start != "100" || p.PPID != 2 || p.Memory != 3*uint64(os.Getpagesize()) {
		t.Fatal(p, e)
	}
	for _, s := range []string{"", "1 hi", "1 (bad) S"} {
		if _, e := parseStat(s, 10); e == nil {
			t.Fatal("accepted invalid stat")
		}
	}
	for _, s := range []string{"--help.service", "foo/evil.service", "bad.service;reboot", "x.socket"} {
		if unitPattern.MatchString(s) {
			t.Fatal(s)
		}
	}
	for _, s := range []string{"nginx.service", "foo@bar.service", "my-app.service"} {
		if !unitPattern.MatchString(s) {
			t.Fatal(s)
		}
	}
	a := testApp()
	for _, s := range []string{"litehelm.service", "sshd.service", "systemd-logind.service", "NetworkManager.service"} {
		if !a.protectedUnit(s) {
			t.Fatal(s)
		}
	}
	for _, body := range []string{`{"signal":"INT"}`, `{"signal":"TERM","extra":1}`, `{} {}`, strings.Repeat("x", 5000)} {
		w := request(a, "POST", "/api/v1/processes/123/signal", body, testToken)
		if w.Code != 400 {
			t.Fatal(w.Code, w.Body.String())
		}
	}
}
func TestPidfdSignalAndReuseProtection(t *testing.T) {
	a := testApp()
	cmd := exec.Command("/system/bin/sleep", "60")
	if _, e := os.Stat("/system/bin/sleep"); e != nil {
		cmd = exec.Command("sleep", "60")
	}
	if e := cmd.Start(); e != nil {
		t.Fatal(e)
	}
	defer cmd.Process.Kill()
	p, e := readProcess(cmd.Process.Pid)
	if e != nil {
		t.Fatal(e)
	}
	path := "/api/v1/processes/" + strconv.Itoa(p.PID) + "/signal"
	w := request(a, "POST", path, `{"signal":"TERM","start_time":"wrong"}`, testToken)
	if w.Code != 409 {
		t.Fatal(w.Code, w.Body.String())
	}
	if e = cmd.Process.Signal(syscall.Signal(0)); e != nil {
		t.Fatal("wrong start time signaled process", e)
	}
	self, _ := readProcess(os.Getpid())
	w = request(a, "POST", fmt.Sprintf("/api/v1/processes/%d/signal", self.PID), fmt.Sprintf(`{"signal":"TERM","start_time":%q}`, self.Start), testToken)
	if w.Code != 403 {
		t.Fatal(w.Code, w.Body.String())
	}
	w = request(a, "POST", path, fmt.Sprintf(`{"signal":"TERM","start_time":%q}`, p.Start), testToken)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case e := <-done:
		if e == nil {
			t.Fatal("expected signal exit")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("signal did not terminate child")
	}
}
func BenchmarkSystem(b *testing.B) {
	a := testApp()
	for i := 0; i < b.N; i++ {
		w := request(a, "GET", "/api/v1/system", "", testToken)
		if w.Code != 200 {
			b.Fatal(w.Body.String())
		}
	}
}
func BenchmarkProcesses(b *testing.B) {
	a := testApp()
	for i := 0; i < b.N; i++ {
		w := request(a, "GET", "/api/v1/processes?limit=100", "", testToken)
		if w.Code != 200 {
			b.Fatal(w.Body.String())
		}
	}
}
