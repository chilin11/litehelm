//go:build linux

package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unicode/utf8"
)

// Script jobs run arbitrary commands as the LiteHelm user once -allow-exec is set.
// Root + systemd jobs run in transient units outside the management sandbox and
// survive LiteHelm restarts; other setups run a direct child process group.
const (
	jobScriptLimit  = 64 << 10
	jobOutputChunk  = 64 << 10
	jobKeep         = 30
	jobRunningLimit = 4
	jobDefaultTime  = 30 * 60
	jobMinTime      = 10
	jobMaxTime      = 24 * 60 * 60
)

var (
	jobIDPattern  = regexp.MustCompile(`^[0-9]{8}-[0-9]{6}-[0-9a-f]{6}$`)
	jobDirPattern = regexp.MustCompile(`^/[A-Za-z0-9/._-]+$`)
)

type jobRunner struct {
	dir     string
	systemd bool
	user    string
	mu      sync.Mutex
	procs   map[string]*directJob
}

type jobMeta struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Started int64  `json:"started_at"`
	Timeout int    `json:"timeout_seconds"`
	Runner  string `json:"runner"`
}

type jobInfo struct {
	jobMeta
	State    string `json:"state"`
	ExitCode int    `json:"exit_code"`
	Signal   string `json:"signal"`
	Finished int64  `json:"finished_at"`
}

func jobsDir(flagValue string) string {
	if flagValue != "" {
		return flagValue
	}
	if state := strings.Split(os.Getenv("STATE_DIRECTORY"), ":")[0]; state != "" {
		return filepath.Join(state, "jobs")
	}
	return "/var/lib/litehelm/jobs"
}

func newJobRunner(dir string) (*jobRunner, error) {
	if !jobDirPattern.MatchString(dir) || strings.Contains(dir, "..") {
		return nil, errors.New("jobs directory must be an absolute path of [A-Za-z0-9/._-]")
	}
	if e := os.MkdirAll(dir, 0700); e != nil {
		return nil, fmt.Errorf("cannot create jobs directory %s: %v (systemd units need StateDirectory=litehelm)", dir, e)
	}
	st, e := os.Lstat(dir)
	if e != nil || !st.IsDir() {
		return nil, errors.New("jobs directory must be a real directory")
	}
	if sys, ok := st.Sys().(*syscall.Stat_t); !ok || int(sys.Uid) != os.Geteuid() {
		return nil, errors.New("jobs directory must be owned by the LiteHelm user")
	}
	if e = os.Chmod(dir, 0700); e != nil {
		return nil, e
	}
	user := strconv.Itoa(os.Geteuid())
	if n, ok := users()[user]; ok {
		user = n
	}
	_, missing := exec.LookPath("systemd-run")
	return &jobRunner{dir: dir, systemd: os.Geteuid() == 0 && systemd() && missing == nil, user: user, procs: map[string]*directJob{}}, nil
}

func (j *jobRunner) mode() string {
	if j.systemd {
		return "systemd"
	}
	return "direct"
}
func (j *jobRunner) path(id string, name ...string) string {
	return filepath.Join(append([]string{j.dir, id}, name...)...)
}
func jobUnit(id string) string { return "litehelm-job-" + id + ".service" }

// jobName keeps the label short and printable; empty labels use the first command line.
func jobName(name, script string) string {
	clean := func(s string) string {
		s = strings.Map(func(r rune) rune {
			if r < 32 || r == 127 {
				return -1
			}
			return r
		}, strings.TrimSpace(s))
		if utf8.RuneCountInString(s) > 60 {
			s = string([]rune(s)[:60]) + "…"
		}
		return s
	}
	if n := clean(name); n != "" {
		return n
	}
	for _, line := range strings.Split(script, "\n") {
		if line = strings.TrimSpace(line); line != "" && !strings.HasPrefix(line, "#") {
			return clean(line)
		}
	}
	return "script"
}

func writeFile(path string, data []byte, mode os.FileMode) error {
	f, e := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, mode)
	if e != nil {
		return e
	}
	if _, e = f.Write(data); e != nil {
		f.Close()
		return e
	}
	return f.Close()
}
func writeResult(path, value string) error {
	if e := os.WriteFile(path+".tmp", []byte(value+"\n"), 0600); e != nil {
		return e
	}
	return os.Rename(path+".tmp", path)
}
func shellQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'" }

func (j *jobRunner) running() int {
	n := 0
	for _, info := range j.list(context.Background()) {
		if info.State == "running" {
			n++
		}
	}
	return n
}

func (j *jobRunner) start(ctx context.Context, name, script string, timeout int) (jobInfo, error) {
	j.mu.Lock()
	defer j.mu.Unlock()
	if j.running() >= jobRunningLimit {
		return jobInfo{}, errConflict("Too many running jobs; wait or cancel one first")
	}
	stamp := make([]byte, 2)
	if _, e := rand.Read(stamp); e != nil {
		return jobInfo{}, e
	}
	now := time.Now()
	// Milliseconds before the random part keep IDs (and the job list) in start order within a second.
	id := now.UTC().Format("20060102-150405") + fmt.Sprintf("-%03x%s", now.Nanosecond()/1e6, hex.EncodeToString(stamp)[:3])
	meta := jobMeta{ID: id, Name: jobName(name, script), Started: now.Unix(), Timeout: timeout, Runner: j.mode()}
	if e := os.Mkdir(j.path(id), 0700); e != nil {
		return jobInfo{}, e
	}
	raw, _ := json.Marshal(meta)
	scriptPath, outPath := j.path(id, "script"), j.path(id, "output")
	argv := []string{"/bin/sh", scriptPath}
	if strings.HasPrefix(script, "#!") {
		argv = []string{scriptPath}
	}
	e := writeFile(j.path(id, "meta.json"), raw, 0600)
	if e == nil {
		e = writeFile(scriptPath, []byte(script), 0700)
	}
	if e == nil && j.systemd {
		e = j.startUnit(ctx, id, argv, timeout)
	} else if e == nil {
		e = j.startDirect(id, argv, outPath, time.Duration(timeout)*time.Second)
	}
	if e != nil {
		_ = os.RemoveAll(j.path(id))
		return jobInfo{}, e
	}
	j.prune()
	return jobInfo{jobMeta: meta, State: "running", ExitCode: -1}, nil
}

func (j *jobRunner) startUnit(ctx context.Context, id string, argv []string, timeout int) error {
	quoted := make([]string, len(argv))
	for i, a := range argv {
		quoted[i] = shellQuote(a)
	}
	run := "#!/bin/sh\nexec " + strings.Join(quoted, " ") + " >" + shellQuote(j.path(id, "output")) + " 2>&1 </dev/null\n"
	result := shellQuote(j.path(id, "result"))
	// systemd passes SERVICE_RESULT/EXIT_CODE/EXIT_STATUS to ExecStopPost, including timeouts and signals.
	post := "#!/bin/sh\nprintf '%s %s %s\\n' \"$SERVICE_RESULT\" \"$EXIT_CODE\" \"$EXIT_STATUS\" >" + result + ".tmp && mv -f " + result + ".tmp " + result + "\n"
	if e := writeFile(j.path(id, "run.sh"), []byte(run), 0700); e != nil {
		return e
	}
	if e := writeFile(j.path(id, "post.sh"), []byte(post), 0700); e != nil {
		return e
	}
	_, _, e := command(ctx, "systemd-run", "--quiet", "--collect", "--unit="+jobUnit(id), "--description=LiteHelm job "+id,
		"--property=User=root", "--property=WorkingDirectory=~", "--property=RuntimeMaxSec="+strconv.Itoa(timeout), "--property=TimeoutStopSec=15",
		"--property=ExecStopPost=/bin/sh "+j.path(id, "post.sh"), "--setenv=LITEHELM_JOB_ID="+id, "--", "/bin/sh", j.path(id, "run.sh"))
	return e
}

func (j *jobRunner) startDirect(id string, argv []string, outPath string, timeout time.Duration) error {
	out, e := os.OpenFile(outPath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if e != nil {
		return e
	}
	defer out.Close()
	cmd := exec.Command(argv[0], argv[1:]...)
	cmd.Stdout, cmd.Stderr = out, out
	cmd.Dir = "/"
	if home, e := os.UserHomeDir(); e == nil {
		if st, e := os.Stat(home); e == nil && st.IsDir() {
			cmd.Dir = home
		}
	}
	// Do not leak the management process memory limits or settings into deployed programs.
	for _, kv := range os.Environ() {
		if !strings.HasPrefix(kv, "LITEHELM_") && !strings.HasPrefix(kv, "GOMEMLIMIT=") && !strings.HasPrefix(kv, "GOGC=") {
			cmd.Env = append(cmd.Env, kv)
		}
	}
	cmd.Env = append(cmd.Env, "LITEHELM_JOB_ID="+id)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if e = cmd.Start(); e != nil {
		return e
	}
	job := &directJob{pgid: cmd.Process.Pid, done: make(chan struct{})}
	j.procs[id] = job
	timer := time.AfterFunc(timeout, func() { job.expired.Store(true); job.terminate() })
	go func() {
		err := cmd.Wait()
		close(job.done)
		timer.Stop()
		// Same "SERVICE_RESULT EXIT_CODE EXIT_STATUS" format systemd gives ExecStopPost.
		value := "success exited 0"
		if ws, ok := cmd.ProcessState.Sys().(syscall.WaitStatus); ok && ws.Signaled() {
			value = "signal killed " + signalName(ws.Signal())
		} else if err != nil {
			value = "exit-code exited " + strconv.Itoa(cmd.ProcessState.ExitCode())
		}
		if job.expired.Load() {
			_, rest, _ := strings.Cut(value, " ")
			value = "timeout " + rest
		}
		_ = writeResult(j.path(id, "result"), value)
		j.mu.Lock()
		delete(j.procs, id)
		j.mu.Unlock()
	}()
	return nil
}

type directJob struct {
	pgid    int
	done    chan struct{}
	expired atomic.Bool
}

// terminate signals the whole process group, escalating to KILL like systemd's stop timeout.
func (d *directJob) terminate() {
	_ = syscall.Kill(-d.pgid, syscall.SIGTERM)
	go func() {
		select {
		case <-d.done:
		case <-time.After(10 * time.Second):
			_ = syscall.Kill(-d.pgid, syscall.SIGKILL)
		}
	}()
}

func signalName(s syscall.Signal) string {
	names := map[syscall.Signal]string{syscall.SIGTERM: "TERM", syscall.SIGKILL: "KILL", syscall.SIGINT: "INT", syscall.SIGHUP: "HUP", syscall.SIGPIPE: "PIPE", syscall.SIGSEGV: "SEGV", syscall.SIGABRT: "ABRT"}
	if n, ok := names[s]; ok {
		return n
	}
	return strconv.Itoa(int(s))
}

type errConflict string

func (e errConflict) Error() string { return string(e) }

func (j *jobRunner) ids() []string {
	entries, _ := os.ReadDir(j.dir)
	ids := []string{}
	for _, en := range entries {
		if en.IsDir() && jobIDPattern.MatchString(en.Name()) {
			ids = append(ids, en.Name())
		}
	}
	sort.Sort(sort.Reverse(sort.StringSlice(ids)))
	return ids
}

func parseResult(info *jobInfo, raw string, cancelled bool) {
	f := strings.Fields(raw)
	for len(f) < 3 {
		f = append(f, "")
	}
	switch {
	case f[0] == "lost":
		info.State = "lost"
	case f[1] == "exited":
		info.ExitCode, _ = strconv.Atoi(f[2])
		info.State = "failed"
		if f[0] == "timeout" {
			info.State = "timeout"
		} else if info.ExitCode == 0 {
			info.State = "succeeded"
		} else if cancelled {
			info.State = "cancelled"
		}
	case f[1] == "killed" || f[1] == "dumped":
		info.Signal = f[2]
		info.State = "killed"
		if f[0] == "timeout" {
			info.State = "timeout"
		} else if cancelled {
			info.State = "cancelled"
		}
	case f[0] == "timeout":
		info.State = "timeout"
	default:
		info.State = "unknown"
	}
}

// activeUnits asks systemd once for all unfinished jobs; failures stay "unknown", never "lost".
func activeUnits(ctx context.Context, ids []string) (map[string]bool, error) {
	active := map[string]bool{}
	if len(ids) == 0 {
		return active, nil
	}
	args := []string{"show", "--no-pager", "--property=Id,ActiveState", "--"}
	for _, id := range ids {
		args = append(args, jobUnit(id))
	}
	raw, _, e := command(ctx, "systemctl", args...)
	if e != nil {
		return nil, e
	}
	for _, block := range strings.Split(strings.TrimSpace(raw), "\n\n") {
		props := map[string]string{}
		for _, line := range strings.Split(block, "\n") {
			if k, v, ok := strings.Cut(line, "="); ok {
				props[k] = v
			}
		}
		switch props["ActiveState"] {
		case "active", "activating", "deactivating", "reloading":
			active[props["Id"]] = true
		}
	}
	return active, nil
}

func (j *jobRunner) read(id string) (jobInfo, bool) {
	var info jobInfo
	raw, e := os.ReadFile(j.path(id, "meta.json"))
	if e != nil || json.Unmarshal(raw, &info.jobMeta) != nil || info.ID != id {
		return info, false
	}
	info.ExitCode = -1
	return info, true
}

func (j *jobRunner) settle(info *jobInfo) bool {
	raw, e := os.ReadFile(j.path(info.ID, "result"))
	if e != nil {
		return false
	}
	_, cancel := os.Stat(j.path(info.ID, "cancel"))
	parseResult(info, string(raw), cancel == nil)
	if st, e := os.Stat(j.path(info.ID, "result")); e == nil {
		info.Finished = st.ModTime().Unix()
	}
	return true
}

func (j *jobRunner) list(ctx context.Context) []jobInfo {
	jobs := []jobInfo{}
	var pending []int
	for _, id := range j.ids() {
		info, ok := j.read(id)
		if !ok {
			continue
		}
		if !j.settle(&info) {
			pending = append(pending, len(jobs))
		}
		jobs = append(jobs, info)
	}
	var active map[string]bool
	var err error
	if j.systemd {
		ids := []string{}
		for _, i := range pending {
			ids = append(ids, jobs[i].ID)
		}
		active, err = activeUnits(ctx, ids)
	}
	for _, i := range pending {
		info := &jobs[i]
		running := false
		if j.systemd {
			running = err == nil && active[jobUnit(info.ID)]
		} else {
			_, running = j.procs[info.ID]
		}
		switch {
		case running:
			info.State = "running"
		case j.settle(info):
		case err != nil:
			info.State = "unknown"
		default:
			// The unit or process ended without recording a result (reboot, LiteHelm restart in direct mode).
			_ = writeResult(j.path(info.ID, "result"), "lost - -")
			j.settle(info)
		}
	}
	return jobs
}

func (j *jobRunner) find(ctx context.Context, id string) (jobInfo, bool) {
	for _, info := range j.list(ctx) {
		if info.ID == id {
			return info, true
		}
	}
	return jobInfo{}, false
}

// prune keeps the newest finished jobs; running jobs are never removed.
func (j *jobRunner) prune() {
	jobs := j.list(context.Background())
	for i := jobKeep; i < len(jobs); i++ {
		if jobs[i].State != "running" && jobs[i].State != "unknown" {
			_ = os.RemoveAll(j.path(jobs[i].ID))
		}
	}
}

// readChunk returns bytes [start,end) of the output, never splitting a UTF-8 sequence
// that may still be completed by later writes. Without an offset it returns the tail.
func readChunk(path string, offset int64, tail bool, growing bool) (string, int64, int64, int64, error) {
	f, e := os.Open(path)
	if errors.Is(e, os.ErrNotExist) {
		return "", 0, 0, 0, nil
	}
	if e != nil {
		return "", 0, 0, 0, e
	}
	defer f.Close()
	st, e := f.Stat()
	if e != nil {
		return "", 0, 0, 0, e
	}
	size := st.Size()
	start := offset
	if tail || start > size || start < 0 {
		start = max(0, size-jobOutputChunk)
	}
	end := min(size, start+jobOutputChunk)
	buf := make([]byte, end-start)
	if _, e = f.ReadAt(buf, start); e != nil && e != io.EOF {
		return "", 0, 0, 0, e
	}
	if tail && start > 0 {
		skip := 0
		for skip < len(buf) && skip < 3 && !utf8.RuneStart(buf[skip]) {
			skip++
		}
		buf, start = buf[skip:], start+int64(skip)
	}
	if end < size || growing {
		for back := 1; back <= 3 && back <= len(buf); back++ {
			if at := len(buf) - back; utf8.RuneStart(buf[at]) {
				if !utf8.FullRune(buf[at:]) {
					buf = buf[:at]
				}
				break
			}
		}
	}
	return strings.ToValidUTF8(string(buf), "\uFFFD"), start, start + int64(len(buf)), size, nil
}

func (j *jobRunner) cancel(ctx context.Context, id string) error {
	j.mu.Lock()
	defer j.mu.Unlock()
	info, ok := j.find(ctx, id)
	if !ok {
		return os.ErrNotExist
	}
	if info.State != "running" {
		return errConflict("Job is not running")
	}
	if e := os.WriteFile(j.path(id, "cancel"), nil, 0600); e != nil {
		return e
	}
	if j.systemd {
		_, _, e := command(ctx, "systemctl", "stop", "--no-block", "--", jobUnit(id))
		return e
	}
	if job := j.procs[id]; job != nil {
		job.terminate()
	}
	return nil
}

func (j *jobRunner) remove(ctx context.Context, id string) error {
	j.mu.Lock()
	defer j.mu.Unlock()
	info, ok := j.find(ctx, id)
	if !ok {
		return os.ErrNotExist
	}
	if info.State == "running" || info.State == "unknown" {
		return errConflict("Job is still running or its state is unknown")
	}
	return os.RemoveAll(j.path(id))
}

func (a *app) jobsEndpoint(w http.ResponseWriter, r *http.Request) {
	p := strings.TrimPrefix(r.URL.Path, "/api/v1/jobs")
	if a.jobs == nil {
		if p == "" && r.Method == "GET" {
			output(w, 200, map[string]any{"supported": false, "jobs": []jobInfo{}, "message": a.execReason})
			return
		}
		fail(w, 403, "Script execution is disabled: "+a.execReason)
		return
	}
	j := a.jobs
	if p == "" {
		if r.Method == "GET" {
			j.mu.Lock()
			jobs := j.list(r.Context())
			j.mu.Unlock()
			output(w, 200, map[string]any{"supported": true, "runner": j.mode(), "user": j.user, "jobs": jobs})
			return
		}
		var req struct {
			Name    string `json:"name"`
			Script  string `json:"script"`
			Timeout int    `json:"timeout_seconds"`
		}
		if !decodeLimit(w, r, &req, 4*jobScriptLimit) {
			return
		}
		script := strings.ReplaceAll(req.Script, "\r\n", "\n")
		if strings.TrimSpace(script) == "" || len(script) > jobScriptLimit || strings.ContainsRune(script, 0) {
			fail(w, 400, "Script must be non-empty, at most 64 KiB and contain no NUL bytes")
			return
		}
		if req.Timeout == 0 {
			req.Timeout = jobDefaultTime
		}
		if req.Timeout < jobMinTime || req.Timeout > jobMaxTime {
			fail(w, 400, "timeout_seconds must be between 10 and 86400")
			return
		}
		info, e := j.start(r.Context(), req.Name, script, req.Timeout)
		if e != nil {
			jobError(w, e)
			return
		}
		log.Printf("action=exec job=%s name=%q bytes=%d runner=%s timeout=%d", info.ID, info.Name, len(script), info.Runner, info.Timeout)
		output(w, 200, map[string]any{"ok": true, "message": "Job started", "job": info})
		return
	}
	id, op, _ := strings.Cut(strings.TrimPrefix(p, "/"), "/")
	if !jobIDPattern.MatchString(id) {
		fail(w, 400, "Invalid job ID")
		return
	}
	switch {
	case op == "" && r.Method == "GET":
		j.mu.Lock()
		info, ok := j.find(r.Context(), id)
		j.mu.Unlock()
		if !ok {
			fail(w, 404, "Job not found")
			return
		}
		q := r.URL.Query()
		offset, e := strconv.ParseInt(q.Get("offset"), 10, 64)
		tail := q.Get("offset") == ""
		if !tail && (e != nil || offset < 0) {
			fail(w, 400, "Invalid output offset")
			return
		}
		text, start, next, size, e := readChunk(j.path(id, "output"), offset, tail, info.State == "running" || info.State == "unknown")
		if e != nil {
			fail(w, 500, "Cannot read job output")
			return
		}
		body := map[string]any{"job": info, "output": text, "offset": start, "next_offset": next, "size": size}
		if tail {
			script, _ := os.ReadFile(j.path(id, "script"))
			body["script"] = strings.ToValidUTF8(string(script), "\uFFFD")
		}
		output(w, 200, body)
	case op == "cancel" && r.Method == "POST":
		var req struct{}
		if !decode(w, r, &req) {
			return
		}
		if e := j.cancel(r.Context(), id); e != nil {
			jobError(w, e)
			return
		}
		log.Printf("action=exec-cancel job=%s", id)
		output(w, 200, map[string]any{"ok": true, "message": "Cancel requested"})
	case op == "delete" && r.Method == "POST":
		var req struct{}
		if !decode(w, r, &req) {
			return
		}
		if e := j.remove(r.Context(), id); e != nil {
			jobError(w, e)
			return
		}
		output(w, 200, map[string]any{"ok": true, "message": "Job deleted"})
	default:
		fail(w, 404, "Unknown job endpoint")
	}
}

func jobError(w http.ResponseWriter, e error) {
	var conflict errConflict
	switch {
	case errors.As(e, &conflict):
		fail(w, 409, e.Error())
	case errors.Is(e, os.ErrNotExist):
		fail(w, 404, "Job not found")
	default:
		fail(w, 500, e.Error())
	}
}
