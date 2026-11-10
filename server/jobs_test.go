//go:build linux

package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func jobApp(t *testing.T) *app {
	t.Helper()
	a := testApp()
	// Root without systemd may only run scripts as root; other users run as themselves.
	user := ""
	if os.Geteuid() == 0 {
		user = "root"
	}
	j, e := newJobRunner(filepath.Join(t.TempDir(), "jobs"), user)
	if e != nil {
		t.Fatal(e)
	}
	// Tests exercise the direct runner even as root; systemd-run is covered by the installer integration.
	j.systemd = false
	a.jobs = j
	return a
}
func decodeBody(t *testing.T, body []byte) map[string]any {
	t.Helper()
	var v map[string]any
	if e := json.Unmarshal(body, &v); e != nil {
		t.Fatal(string(body))
	}
	return v
}
func startJob(t *testing.T, a *app, script string) string {
	t.Helper()
	raw, _ := json.Marshal(map[string]any{"script": script, "timeout_seconds": 60})
	w := request(a, "POST", "/api/v1/jobs", string(raw), testToken)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	v := decodeBody(t, w.Body.Bytes())
	if v["ok"] != true {
		t.Fatal(v)
	}
	return v["job"].(map[string]any)["id"].(string)
}
func waitJob(t *testing.T, a *app, id string, done func(string) bool) map[string]any {
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		w := request(a, "GET", "/api/v1/jobs/"+id, "", testToken)
		if w.Code != 200 {
			t.Fatal(w.Code, w.Body.String())
		}
		v := decodeBody(t, w.Body.Bytes())
		if done(v["job"].(map[string]any)["state"].(string)) {
			return v
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("job did not settle")
	return nil
}

func TestJobsDisabledByDefault(t *testing.T) {
	a := testApp()
	a.execReason = "disabled"
	w := request(a, "GET", "/api/v1/jobs", "", testToken)
	if v := decodeBody(t, w.Body.Bytes()); w.Code != 200 || v["supported"] != false {
		t.Fatal(w.Body.String())
	}
	if w = request(a, "POST", "/api/v1/jobs", `{"script":"id"}`, testToken); w.Code != 403 {
		t.Fatal(w.Code)
	}
	w = request(a, "GET", "/api/v1/system", "", testToken)
	if caps := decodeBody(t, w.Body.Bytes())["capabilities"].(map[string]any); caps["exec"] != false {
		t.Fatal(caps)
	}
}

func TestJobRunsAndReportsExitCode(t *testing.T) {
	a := jobApp(t)
	id := startJob(t, a, "echo hello\necho 中文 >&2\nprintf 'job=%s\\n' \"$LITEHELM_JOB_ID\"\nexit 3\n")
	v := waitJob(t, a, id, func(s string) bool { return s != "running" })
	job := v["job"].(map[string]any)
	if job["state"] != "failed" || job["exit_code"].(float64) != 3 || job["finished_at"].(float64) == 0 {
		t.Fatal(job)
	}
	out := v["output"].(string)
	if !strings.Contains(out, "hello\n") || !strings.Contains(out, "中文\n") || !strings.Contains(out, "job="+id) || !strings.Contains(v["script"].(string), "exit 3") {
		t.Fatal(v)
	}
	// Incremental reads return nothing new at the end and never resend the script.
	w := request(a, "GET", fmt.Sprintf("/api/v1/jobs/%s?offset=%d", id, int(v["next_offset"].(float64))), "", testToken)
	next := decodeBody(t, w.Body.Bytes())
	if next["output"] != "" || next["script"] != nil {
		t.Fatal(next)
	}
	if w = request(a, "GET", "/api/v1/jobs/"+id+"?offset=-1", "", testToken); w.Code != 400 {
		t.Fatal(w.Code)
	}
	list := decodeBody(t, request(a, "GET", "/api/v1/jobs", "", testToken).Body.Bytes())
	if list["supported"] != true || list["runner"] != "direct" || len(list["jobs"].([]any)) != 1 {
		t.Fatal(list)
	}
}

func TestJobShebangSucceeds(t *testing.T) {
	a := jobApp(t)
	id := startJob(t, a, "#!/bin/sh\nset -eu\necho ok\n")
	v := waitJob(t, a, id, func(s string) bool { return s != "running" })
	if job := v["job"].(map[string]any); job["state"] != "succeeded" || job["exit_code"].(float64) != 0 || v["output"] != "ok\n" {
		t.Fatal(v)
	}
}

func TestJobCancelKillsProcessGroup(t *testing.T) {
	a := jobApp(t)
	id := startJob(t, a, "sleep 30 &\necho started\nwait\n")
	waitJob(t, a, id, func(string) bool {
		b, _ := os.ReadFile(a.jobs.path(id, "output"))
		return strings.Contains(string(b), "started")
	})
	if w := request(a, "POST", "/api/v1/jobs/"+id+"/delete", `{}`, testToken); w.Code != 409 {
		t.Fatal("deleted a running job", w.Code)
	}
	if w := request(a, "POST", "/api/v1/jobs/"+id+"/cancel", `{}`, testToken); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	v := waitJob(t, a, id, func(s string) bool { return s != "running" })
	if job := v["job"].(map[string]any); job["state"] != "cancelled" || job["signal"] != "TERM" {
		t.Fatal(job)
	}
	if w := request(a, "POST", "/api/v1/jobs/"+id+"/cancel", `{}`, testToken); w.Code != 409 {
		t.Fatal(w.Code)
	}
	if w := request(a, "POST", "/api/v1/jobs/"+id+"/delete", `{}`, testToken); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	if w := request(a, "GET", "/api/v1/jobs/"+id, "", testToken); w.Code != 404 {
		t.Fatal(w.Code)
	}
}

func TestJobTimeoutAndLost(t *testing.T) {
	a := jobApp(t)
	info, e := a.jobs.start(t.Context(), "slow", "sleep 30\n", 1)
	if e != nil {
		t.Fatal(e)
	}
	v := waitJob(t, a, info.ID, func(s string) bool { return s != "running" })
	if job := v["job"].(map[string]any); job["state"] != "timeout" || job["name"] != "slow" {
		t.Fatal(job)
	}
	// A job without a process or result (LiteHelm restarted in direct mode) is reported once as lost.
	id := "20260101-000000-abcdef"
	if e = os.Mkdir(a.jobs.path(id), 0700); e != nil {
		t.Fatal(e)
	}
	raw, _ := json.Marshal(jobMeta{ID: id, Name: "old", Started: 1, Timeout: 60, Runner: "direct"})
	_ = os.WriteFile(a.jobs.path(id, "meta.json"), raw, 0600)
	if v = waitJob(t, a, id, func(string) bool { return true }); v["job"].(map[string]any)["state"] != "lost" {
		t.Fatal(v)
	}
}

func TestJobValidation(t *testing.T) {
	a := jobApp(t)
	big, _ := json.Marshal(map[string]any{"script": strings.Repeat("x", jobScriptLimit+1)})
	for _, body := range []string{`{"script":"   "}`, `{"script":"id","timeout_seconds":5}`, `{"script":"id","timeout_seconds":90000}`, `{"script":"a\u0000b"}`, string(big), `{"script":"id","extra":1}`} {
		if w := request(a, "POST", "/api/v1/jobs", body, testToken); w.Code != 400 {
			t.Fatal(w.Code, body[:min(len(body), 60)])
		}
	}
	for _, path := range []string{"/api/v1/jobs/../../etc", "/api/v1/jobs/abc", "/api/v1/jobs/20260101-000000-ABCDEF"} {
		if w := request(a, "GET", path, "", testToken); w.Code != 400 {
			t.Fatal(path, w.Code)
		}
	}
	if w := request(a, "GET", "/api/v1/jobs/20260101-000000-abcdef", "", testToken); w.Code != 404 {
		t.Fatal(w.Code)
	}
	if _, e := newJobRunner("relative/jobs", ""); e == nil {
		t.Fatal("accepted relative jobs dir")
	}
	if _, e := newJobRunner("/tmp/with space", ""); e == nil {
		t.Fatal("accepted unsafe jobs dir")
	}
	if n := jobName("", "#!/bin/sh\n# comment\n\n  apt-get install -y nginx\n"); n != "apt-get install -y nginx" {
		t.Fatal(n)
	}
	if n := jobName(strings.Repeat("长", 70), ""); len([]rune(n)) != 61 {
		t.Fatal(n)
	}
}

func TestJobRunningLimitAndPrune(t *testing.T) {
	a := jobApp(t)
	for i := 0; i < jobKeep+3; i++ {
		id := fmt.Sprintf("20250101-%06d-abcdef", i)
		_ = os.Mkdir(a.jobs.path(id), 0700)
		raw, _ := json.Marshal(jobMeta{ID: id, Name: "old", Started: 1, Timeout: 60, Runner: "direct"})
		_ = os.WriteFile(a.jobs.path(id, "meta.json"), raw, 0600)
		_ = os.WriteFile(a.jobs.path(id, "result"), []byte("success exited 0\n"), 0600)
	}
	ids := []string{}
	for i := 0; i < jobRunningLimit; i++ {
		ids = append(ids, startJob(t, a, "sleep 30\n"))
	}
	raw, _ := json.Marshal(map[string]any{"script": "id"})
	if w := request(a, "POST", "/api/v1/jobs", string(raw), testToken); w.Code != 409 {
		t.Fatal(w.Code, w.Body.String())
	}
	if n := len(a.jobs.ids()); n != jobKeep {
		t.Fatal("kept", n)
	}
	for _, id := range ids {
		if _, e := os.Stat(a.jobs.path(id)); e != nil {
			t.Fatal("pruned running job", id)
		}
		_ = a.jobs.cancel(t.Context(), id)
	}
	for _, id := range ids {
		waitJob(t, a, id, func(s string) bool { return s != "running" })
	}
}

func TestReadChunkKeepsUTF8Boundaries(t *testing.T) {
	path := filepath.Join(t.TempDir(), "output")
	data := strings.Repeat("a", jobOutputChunk-1) + "中" + "tail"
	_ = os.WriteFile(path, []byte(data), 0600)
	text, start, next, size, e := readChunk(path, 0, false, false)
	if e != nil || start != 0 || next != jobOutputChunk-1 || size != int64(len(data)) || strings.ContainsRune(text, '�') {
		t.Fatal(start, next, size, e)
	}
	text, _, next, _, _ = readChunk(path, next, false, false)
	if text != "中tail" || next != size {
		t.Fatal(text, next)
	}
	// A growing file never returns half a character; the client re-reads it from next_offset.
	_ = os.WriteFile(path, []byte("ok\xe4\xb8"), 0600)
	text, _, next, _, _ = readChunk(path, 0, false, true)
	if text != "ok" || next != 2 {
		t.Fatal(text, next)
	}
	// Tail reads skip a leading partial character.
	_ = os.WriteFile(path, []byte("中"+strings.Repeat("b", jobOutputChunk-2)), 0600)
	text, start, _, _, _ = readChunk(path, 0, true, false)
	if start != 3 || strings.ContainsRune(text, '�') {
		t.Fatal(start, text[:4])
	}
	if text, _, _, size, e = readChunk(filepath.Join(t.TempDir(), "missing"), 0, true, true); e != nil || text != "" || size != 0 {
		t.Fatal(e)
	}
}

func TestParseResult(t *testing.T) {
	cases := []struct {
		raw       string
		cancelled bool
		state     string
		code      int
		signal    string
	}{
		{"success exited 0", false, "succeeded", 0, ""},
		{"exit-code exited 2", false, "failed", 2, ""},
		{"exit-code exited 143", true, "cancelled", 143, ""},
		{"success killed TERM", true, "cancelled", -1, "TERM"},
		{"signal killed KILL", false, "killed", -1, "KILL"},
		{"timeout killed TERM", false, "timeout", -1, "TERM"},
		{"timeout exited 1", false, "timeout", 1, ""},
		{"lost - -", false, "lost", -1, ""},
		{"  ", false, "unknown", -1, ""},
	}
	for _, c := range cases {
		info := jobInfo{ExitCode: -1}
		parseResult(&info, c.raw, c.cancelled)
		if info.State != c.state || info.ExitCode != c.code || info.Signal != c.signal {
			t.Fatal(c.raw, info)
		}
	}
}

func TestResolveExecUser(t *testing.T) {
	cases := []struct {
		root, unit      bool
		self, requested string
		want            string
	}{
		{true, true, "root", "", "litehelm"},      // default: dedicated account, never root
		{true, true, "root", "root", "root"},      // root only when asked for
		{true, true, "root", "deploy", "deploy"},  // any existing account
		{true, false, "root", "root", "root"},     // root without systemd stays a direct child
		{false, false, "alice", "", "alice"},      // non-root LiteHelm cannot switch users
		{false, false, "alice", "alice", "alice"}, // explicit self is fine
		{false, true, "litehelm", "", "litehelm"}, // unit mode flag is irrelevant without root
		{false, false, "litehelm", "litehelm", "litehelm"},
	}
	for _, c := range cases {
		if got, e := resolveExecUser(c.root, c.unit, c.self, c.requested); e != nil || got != c.want {
			t.Fatal(c, got, e)
		}
	}
	for _, c := range []struct {
		root, unit      bool
		self, requested string
	}{
		{true, false, "root", ""},         // dropping privileges needs a systemd unit
		{true, false, "root", "litehelm"}, // same, explicit
		{false, false, "alice", "root"},   // cannot escalate
		{false, false, "alice", "bob"},    // cannot switch
		{true, true, "root", "Bad User"},  // unsafe name
		{true, true, "root", "-rf"},       // option-like name
		{true, true, "root", "a;reboot"},  // shell metacharacters
	} {
		if got, e := resolveExecUser(c.root, c.unit, c.self, c.requested); e == nil {
			t.Fatal("accepted", c, got)
		}
	}
}

func TestScriptArgv(t *testing.T) {
	const p = "/run/x/script"
	cases := map[string][]string{
		"echo hi\n":                          {"/bin/sh", p},
		"#!/bin/sh\necho hi\n":               {"/bin/sh", p},
		"#!/bin/sh -eu\n":                    {"/bin/sh", "-eu", p},
		"#! /usr/bin/env  python3 \nprint()": {"/usr/bin/env", "python3", p},
		"#!/usr/bin/env -S bash -x\n":        {"/usr/bin/env", "-S bash -x", p}, // kernel passes one optional argument
		"#!\necho hi\n":                      {"/bin/sh", p},
		"#!/bin/bash":                        {"/bin/bash", p},
		" #!/bin/bash\n":                     {"/bin/sh", p},
	}
	for script, want := range cases {
		if got := scriptArgv(script, p); strings.Join(got, "|") != strings.Join(want, "|") {
			t.Fatalf("%q: %q", script, got)
		}
	}
}

func TestUnitArgs(t *testing.T) {
	id := "20260101-000000-abcdef"
	j := &jobRunner{dir: "/var/lib/litehelm/jobs", systemd: true, user: "litehelm", uid: "998", gid: "997"}
	args := strings.Join(j.unitArgs(id, "#!/bin/sh -c $HOME\n", 60), "\n")
	for _, want := range []string{
		"--property=User=litehelm",
		"--property=NoNewPrivileges=yes",
		"--property=RuntimeDirectory=litehelm-job-" + id,
		"--property=StandardOutput=append:/var/lib/litehelm/jobs/" + id + "/output",
		"--property=ExecStartPre=+/bin/sh /var/lib/litehelm/jobs/" + id + "/pre.sh",
		"--property=ExecStopPost=+/bin/sh /var/lib/litehelm/jobs/" + id + "/post.sh",
		"--\n/bin/sh\n-c $$HOME\n/run/litehelm-job-" + id + "/script",
	} {
		if !strings.Contains(args, want) {
			t.Fatalf("missing %q in\n%s", want, args)
		}
	}
	if strings.Contains(args, "User=root") {
		t.Fatal(args)
	}
	j.user, j.uid, j.gid = "root", "0", "0"
	args = strings.Join(j.unitArgs(id, "id\n", 60), "\n")
	if !strings.Contains(args, "--property=User=root") || strings.Contains(args, "NoNewPrivileges") {
		t.Fatal(args)
	}
}
