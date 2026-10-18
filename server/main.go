//go:build linux

package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

type app struct {
	token    [32]byte
	power    bool
	slots    chan struct{}
	mutation sync.Mutex
	unit     string
	scanMu   sync.Mutex
	lastScan time.Time
	// jobs is nil unless -allow-exec is set and the jobs directory is usable.
	jobs       *jobRunner
	execReason string
}

func main() {
	listen := flag.String("listen", "127.0.0.1:9091", "HTTP listen address; loopback only")
	tokenFile := flag.String("token-file", "/etc/litehelm/token", "file containing a random bearer token (mode 0600)")
	power := flag.Bool("allow-power", false, "enable reboot/poweroff (requires root and systemd)")
	allowExec := flag.Bool("allow-exec", os.Getenv("LITEHELM_ALLOW_EXEC") == "1", "enable arbitrary script jobs as the LiteHelm user (also LITEHELM_ALLOW_EXEC=1)")
	jobsFlag := flag.String("jobs-dir", "", "script job directory (default $STATE_DIRECTORY/jobs or /var/lib/litehelm/jobs)")
	generate := flag.Bool("generate-token", false, "print a new random token and exit")
	flag.Parse()
	if *generate {
		b := make([]byte, 32)
		if _, e := rand.Read(b); e != nil {
			log.Fatal(e)
		}
		fmt.Println(hex.EncodeToString(b))
		return
	}
	host, _, err := net.SplitHostPort(*listen)
	if err != nil || net.ParseIP(host) == nil || !net.ParseIP(host).IsLoopback() {
		log.Fatal("listen must be a loopback IP, e.g. 127.0.0.1:9091; publish through an HTTPS reverse proxy")
	}
	st, err := os.Stat(*tokenFile)
	if err != nil {
		log.Fatal(err)
	}
	if !st.Mode().IsRegular() || st.Mode().Perm()&0077 != 0 {
		log.Fatal("token file must be a regular file with permissions 0600")
	}
	raw, err := os.ReadFile(*tokenFile)
	if err != nil {
		log.Fatal(err)
	}
	token := strings.TrimSpace(string(raw))
	if len(token) < 32 {
		log.Fatal("token must contain at least 32 characters; use -generate-token")
	}
	a := &app{token: sha256.Sum256([]byte(token)), power: *power, slots: make(chan struct{}, 2), unit: os.Getenv("LITEHELM_UNIT")}
	if a.unit == "" {
		a.unit = "litehelm.service"
	}
	a.execReason = "start the server with -allow-exec or LITEHELM_ALLOW_EXEC=1"
	if *allowExec {
		// A broken jobs directory disables scripts but keeps the management API reachable.
		if a.jobs, err = newJobRunner(jobsDir(*jobsFlag)); err != nil {
			a.execReason = err.Error()
			log.Printf("script jobs disabled: %v", err)
		}
	}
	srv := &http.Server{Addr: *listen, Handler: a, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 25 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 16 << 10}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		c, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(c)
	}()
	execMode := "off"
	if a.jobs != nil {
		execMode = a.jobs.mode() + " as " + a.jobs.user
	}
	log.Printf("LiteHelm listening on %s (power=%t exec=%s)", *listen, *power, execMode)
	if e := srv.ListenAndServe(); e != nil && !errors.Is(e, http.ErrServerClosed) {
		log.Fatal(e)
	}
}

func output(w http.ResponseWriter, status int, v any) {
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func fail(w http.ResponseWriter, status int, msg string) {
	output(w, status, map[string]string{"error": msg})
}
func (a *app) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	// Bearer only; never cookie-authenticate or enable cross-origin browser requests.
	if r.Header.Get("Origin") != "" {
		fail(w, 403, "Browser cross-origin requests are disabled")
		return
	}
	auth := r.Header.Get("Authorization")
	sum := sha256.Sum256([]byte(strings.TrimPrefix(auth, "Bearer ")))
	if !strings.HasPrefix(auth, "Bearer ") || subtle.ConstantTimeCompare(sum[:], a.token[:]) != 1 {
		fail(w, 401, "Invalid management token")
		return
	}
	if r.Method != "GET" && r.Method != "POST" {
		fail(w, 405, "Method not allowed")
		return
	}
	select {
	case a.slots <- struct{}{}:
		defer func() { <-a.slots }()
	default:
		fail(w, 429, "Server busy; try again shortly")
		return
	}
	if r.Method == "POST" {
		if !a.mutation.TryLock() {
			fail(w, 409, "Another operation is in progress")
			return
		}
		defer a.mutation.Unlock()
	}
	p := r.URL.Path
	switch {
	case p == "/api/v1/system" && r.Method == "GET":
		a.system(w, r)
	case p == "/api/v1/processes" && r.Method == "GET":
		a.processes(w, r)
	case strings.HasPrefix(p, "/api/v1/processes/") && strings.HasSuffix(p, "/signal") && r.Method == "POST":
		a.signalProcess(w, r)
	case p == "/api/v1/services" && r.Method == "GET":
		a.services(w, r)
	case strings.HasPrefix(p, "/api/v1/services/"):
		a.service(w, r)
	case p == "/api/v1/ports" && r.Method == "GET":
		a.ports(w, r)
	case p == "/api/v1/timers" && r.Method == "GET":
		a.timers(w, r)
	case strings.HasPrefix(p, "/api/v1/timers/") && strings.HasSuffix(p, "/run") && r.Method == "POST":
		a.runTimer(w, r)
	case p == "/api/v1/disks" && r.Method == "GET":
		a.disks(w, r)
	case p == "/api/v1/disk-scan" && r.Method == "POST":
		a.diskScan(w, r)
	case p == "/api/v1/jobs" || strings.HasPrefix(p, "/api/v1/jobs/"):
		a.jobsEndpoint(w, r)
	case p == "/api/v1/power" && r.Method == "POST":
		a.powerAction(w, r)
	default:
		fail(w, 404, "Unknown API endpoint")
	}
}
func decode(w http.ResponseWriter, r *http.Request, v any) bool { return decodeLimit(w, r, v, 4096) }
func decodeLimit(w http.ResponseWriter, r *http.Request, v any, limit int64) bool {
	if !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		fail(w, 415, "Content-Type must be application/json")
		return false
	}
	r.Body = http.MaxBytesReader(w, r.Body, limit)
	defer r.Body.Close()
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if e := d.Decode(v); e != nil {
		fail(w, 400, "Invalid JSON request")
		return false
	}
	if e := d.Decode(new(any)); e != io.EOF {
		fail(w, 400, "Expected one JSON object")
		return false
	}
	return true
}
func number(s string) uint64  { n, _ := strconv.ParseUint(s, 10, 64); return n }
func file(path string) string { b, _ := os.ReadFile(path); return string(b) }
func uptime() float64 {
	f := strings.Fields(file("/proc/uptime"))
	if len(f) == 0 {
		return 0
	}
	n, _ := strconv.ParseFloat(f[0], 64)
	return n
}
func systemd() bool { _, e := os.Stat("/run/systemd/system"); return e == nil }
func cpuTicks() (uint64, uint64, error) {
	b, e := os.ReadFile("/proc/stat")
	if e != nil {
		return 0, 0, e
	}
	line := strings.SplitN(string(b), "\n", 2)[0]
	f := strings.Fields(line)
	if len(f) < 6 || f[0] != "cpu" {
		return 0, 0, errors.New("Invalid /proc/stat")
	}
	var total, idle uint64
	for i := 1; i < len(f) && i <= 8; i++ {
		n := number(f[i])
		total += n
		if i == 4 || i == 5 {
			idle += n
		}
	}
	return total, idle, nil
}
func (a *app) system(w http.ResponseWriter, r *http.Request) {
	t1, i1, e := cpuTicks()
	if e != nil {
		fail(w, 500, e.Error())
		return
	}
	select {
	case <-r.Context().Done():
		return
	case <-time.After(150 * time.Millisecond):
	}
	t2, i2, e := cpuTicks()
	if e != nil {
		fail(w, 500, e.Error())
		return
	}
	cpu := 0.0
	if t2 > t1 && i2 >= i1 && i2-i1 <= t2-t1 {
		cpu = 100 * float64(t2-t1-(i2-i1)) / float64(t2-t1)
	}
	mem := map[string]uint64{}
	for _, l := range strings.Split(file("/proc/meminfo"), "\n") {
		f := strings.Fields(l)
		if len(f) >= 2 {
			mem[strings.TrimSuffix(f[0], ":")] = number(f[1]) * 1024
		}
	}
	avail, ok := mem["MemAvailable"]
	if !ok {
		avail = mem["MemFree"] + mem["Buffers"] + mem["Cached"]
	}
	if avail > mem["MemTotal"] {
		avail = mem["MemTotal"]
	}
	var fs syscall.Statfs_t
	if e = syscall.Statfs("/", &fs); e != nil {
		fail(w, 500, "Cannot inspect root filesystem")
		return
	}
	total := fs.Blocks * uint64(fs.Bsize)
	free := fs.Bavail * uint64(fs.Bsize)
	used := (fs.Blocks - fs.Bfree) * uint64(fs.Bsize)
	var rx, tx uint64
	for _, l := range strings.Split(file("/proc/net/dev"), "\n") {
		parts := strings.SplitN(l, ":", 2)
		if len(parts) != 2 || strings.TrimSpace(parts[0]) == "lo" {
			continue
		}
		f := strings.Fields(parts[1])
		if len(f) >= 9 {
			rx += number(f[0])
			tx += number(f[8])
		}
	}
	load := []float64{0, 0, 0}
	f := strings.Fields(file("/proc/loadavg"))
	for i := 0; i < 3 && i < len(f); i++ {
		load[i], _ = strconv.ParseFloat(f[i], 64)
	}
	name, _ := os.Hostname()
	osName := "Linux"
	for _, l := range strings.Split(file("/etc/os-release"), "\n") {
		if strings.HasPrefix(l, "PRETTY_NAME=") {
			osName = strings.Trim(strings.TrimPrefix(l, "PRETTY_NAME="), "\"")
		}
	}
	output(w, 200, map[string]any{"hostname": name, "os": osName, "arch": runtime.GOARCH, "uptime_seconds": uint64(uptime()), "cpu_percent": cpu, "cpu_cores": runtime.NumCPU(), "load": load, "memory": map[string]uint64{"total": mem["MemTotal"], "used": mem["MemTotal"] - avail, "available": avail}, "swap": map[string]uint64{"total": mem["SwapTotal"], "used": mem["SwapTotal"] - mem["SwapFree"]}, "disk": map[string]uint64{"total": total, "used": used, "free": free}, "network": map[string]uint64{"rx_bytes": rx, "tx_bytes": tx}, "timestamp": time.Now().Unix(), "capabilities": map[string]bool{"process_signal": os.Geteuid() == 0, "service_control": os.Geteuid() == 0 && systemd(), "power": a.power && os.Geteuid() == 0 && systemd(), "exec": a.jobs != nil}})
}

type process struct {
	PID    int     `json:"pid"`
	Name   string  `json:"name"`
	User   string  `json:"user"`
	State  string  `json:"state"`
	CPU    float64 `json:"cpu_percent"`
	Memory uint64  `json:"memory_bytes"`
	Start  string  `json:"start_time"`
	PPID   int     `json:"-"`
}

func parseStat(raw string, up float64) (process, error) {
	var p process
	left := strings.Index(raw, "(")
	right := strings.LastIndex(raw, ")")
	if left < 1 || right <= left {
		return p, errors.New("Invalid process stat")
	}
	p.PID, _ = strconv.Atoi(strings.TrimSpace(raw[:left]))
	p.Name = raw[left+1 : right]
	f := strings.Fields(raw[right+1:])
	if len(f) < 22 {
		return p, errors.New("Incomplete process stat")
	}
	p.State = f[0]
	p.PPID, _ = strconv.Atoi(f[1])
	p.Start = f[19]
	// Linux USER_HZ is 100 on the supported architectures, independent of kernel CONFIG_HZ.
	elapsed := up - float64(number(p.Start))/100
	if elapsed > 0 {
		p.CPU = 100 * (float64(number(f[11])) + float64(number(f[12]))) / 100 / elapsed
	}
	rss, _ := strconv.ParseInt(f[21], 10, 64)
	if rss > 0 {
		p.Memory = uint64(rss) * uint64(os.Getpagesize())
	}
	return p, nil
}
func readProcess(pid int) (process, error) {
	b, e := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if e != nil {
		return process{}, e
	}
	return parseStat(string(b), uptime())
}
func users() map[string]string {
	m := map[string]string{}
	for _, l := range strings.Split(file("/etc/passwd"), "\n") {
		f := strings.Split(l, ":")
		if len(f) > 2 {
			m[f[2]] = f[0]
		}
	}
	return m
}
func (a *app) processes(w http.ResponseWriter, r *http.Request) {
	entries, e := os.ReadDir("/proc")
	if e != nil {
		fail(w, 500, e.Error())
		return
	}
	ps := []process{}
	names := users()
	up := uptime()
	for _, en := range entries {
		pid, e := strconv.Atoi(en.Name())
		if e != nil {
			continue
		}
		b, readErr := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
		if readErr != nil {
			continue
		}
		p, e := parseStat(string(b), up)
		if e != nil {
			continue
		}
		p.User = "?"
		for _, l := range strings.Split(file(fmt.Sprintf("/proc/%d/status", pid)), "\n") {
			if strings.HasPrefix(l, "Uid:") {
				f := strings.Fields(l)
				if len(f) > 1 {
					p.User = f[1]
					if n, ok := names[p.User]; ok {
						p.User = n
					}
				}
				break
			}
		}
		ps = append(ps, p)
	}
	sortProcesses(ps, r.URL.Query().Get("sort") == "cpu")
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 {
		limit = 100
	}
	if limit > 300 {
		limit = 300
	}
	if len(ps) > limit {
		ps = ps[:limit]
	}
	output(w, 200, map[string]any{"processes": ps, "timestamp": time.Now().Unix()})
}
func protectedProcess(p process) bool {
	if p.PID <= 1 {
		return true
	}
	id := os.Getpid()
	for i := 0; i < 64 && id > 1; i++ {
		if p.PID == id {
			return true
		}
		q, e := readProcess(id)
		if e != nil {
			break
		}
		id = q.PPID
	}
	n := strings.ToLower(p.Name)
	for _, s := range append([]string{"litehelm", "sshd", "systemd", "init", "dbus-daemon", "networkmanager", "systemd-network"}, protectedNames("LITEHELM_PROTECTED_PROCESSES")...) {
		if strings.HasPrefix(n, s) {
			return true
		}
	}
	return false
}
func (a *app) signalProcess(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/api/v1/processes/"), "/signal")
	pid, e := strconv.Atoi(id)
	if e != nil || pid <= 1 {
		fail(w, 400, "Invalid or protected PID")
		return
	}
	var req struct {
		Signal string `json:"signal"`
		Start  string `json:"start_time"`
	}
	if !decode(w, r, &req) {
		return
	}
	sig := unix.SIGTERM
	if req.Signal == "KILL" {
		sig = unix.SIGKILL
	} else if req.Signal != "TERM" {
		fail(w, 400, "Only TERM and KILL are supported")
		return
	}
	// pidfd binds signal delivery to this process instance, closing the PID reuse race.
	fd, e := unix.PidfdOpen(pid, 0)
	if e != nil {
		fail(w, 409, "Process unavailable or kernel lacks pidfd support (Linux 5.3+ required for safe signalling)")
		return
	}
	defer unix.Close(fd)
	p, e := readProcess(pid)
	if e != nil || p.Start != req.Start || req.Start == "" {
		fail(w, 409, "Process changed or exited; refresh the list")
		return
	}
	if protectedProcess(p) {
		fail(w, 403, "This process is protected to preserve host access")
		return
	}
	if e = unix.PidfdSendSignal(fd, sig, nil, 0); e != nil {
		fail(w, 403, "Cannot signal process: "+e.Error())
		return
	}
	log.Printf("action=signal pid=%d name=%q signal=%s", pid, p.Name, req.Signal)
	output(w, 200, map[string]any{"ok": true, "message": "Signal sent"})
}

var unitPattern = regexp.MustCompile(`^[a-zA-Z0-9_][a-zA-Z0-9_.@:\x2d]*\.service$`)

type serviceInfo struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	Load        string `json:"load_state"`
	Active      string `json:"active_state"`
	Sub         string `json:"sub_state"`
}
type cappedBuffer struct {
	buffer    bytes.Buffer
	limit     int
	truncated bool
}

func (b *cappedBuffer) Write(p []byte) (int, error) {
	n := len(p)
	left := b.limit - b.buffer.Len()
	if n > left {
		p = p[:max(0, left)]
		b.truncated = true
	}
	_, _ = b.buffer.Write(p)
	return n, nil
}
func (b *cappedBuffer) String() string { return b.buffer.String() }
func command(ctx context.Context, name string, args ...string) (string, bool, error) {
	return commandLimit(ctx, 64<<10, name, args...)
}
func commandLimit(ctx context.Context, limit int, name string, args ...string) (string, bool, error) {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Env = append(os.Environ(), "LC_ALL=C", "SYSTEMD_PAGER=", "SYSTEMD_COLORS=0")
	out := &cappedBuffer{limit: limit}
	errout := &cappedBuffer{limit: 4096}
	cmd.Stdout = out
	cmd.Stderr = errout
	e := cmd.Run()
	if e != nil {
		return "", false, fmt.Errorf("%s: %s", e, strings.TrimSpace(errout.String()))
	}
	return strings.ToValidUTF8(out.String(), ""), out.truncated, nil
}
func (a *app) services(w http.ResponseWriter, r *http.Request) {
	services := []serviceInfo{}
	if !systemd() {
		output(w, 200, map[string]any{"services": services, "supported": false})
		return
	}
	raw, truncated, e := commandLimit(r.Context(), 512<<10, "systemctl", "list-units", "--type=service", "--all", "--no-pager", "--plain", "--no-legend")
	if e != nil {
		fail(w, 500, e.Error())
		return
	}
	if truncated {
		fail(w, 500, "Service list exceeds output limit; refusing an incomplete result")
		return
	}
	installed, truncated, e := commandLimit(r.Context(), 512<<10, "systemctl", "list-unit-files", "--type=service", "--no-pager", "--no-legend")
	if e != nil {
		fail(w, 500, e.Error())
		return
	}
	if truncated {
		fail(w, 500, "Installed service list exceeds output limit; refusing an incomplete result")
		return
	}
	services = mergeServices(raw, installed)
	if len(services) > 5000 {
		fail(w, 500, "Too many services; refusing an incomplete result")
		return
	}
	output(w, 200, map[string]any{"services": services, "supported": true})
}
func (a *app) protectedUnit(name string) bool {
	if name == a.unit {
		return true
	}
	n := strings.ToLower(strings.SplitN(name, "@", 2)[0])
	for _, s := range append([]string{"litehelm", "ssh", "sshd", "systemd", "dbus", "networkmanager", "networking", "firewalld", "ufw"}, protectedNames("LITEHELM_PROTECTED_SERVICES")...) {
		if strings.HasPrefix(n, s) {
			return true
		}
	}
	return false
}
func (a *app) service(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/api/v1/services/")
	i := strings.LastIndex(rest, "/")
	if i < 0 {
		fail(w, 404, "Unknown service endpoint")
		return
	}
	name, op := rest[:i], rest[i+1:]
	if !unitPattern.MatchString(name) || len(name) > 200 {
		fail(w, 400, "Invalid service name")
		return
	}
	if !systemd() {
		fail(w, 409, "systemd is unavailable on this host")
		return
	}
	if op == "logs" && r.Method == "GET" {
		out, tr, e := command(r.Context(), "journalctl", "--unit="+name, "-n", "80", "--no-pager", "--output=short-iso")
		if e != nil {
			fail(w, 500, e.Error())
			return
		}
		output(w, 200, map[string]any{"logs": out, "truncated": tr})
		return
	}
	if op != "action" || r.Method != "POST" {
		fail(w, 404, "Unknown service endpoint")
		return
	}
	var req struct {
		Action string `json:"action"`
	}
	if !decode(w, r, &req) {
		return
	}
	if req.Action != "start" && req.Action != "stop" && req.Action != "restart" {
		fail(w, 400, "Invalid service action")
		return
	}
	if a.protectedUnit(name) {
		fail(w, 403, "This service is protected to preserve host access")
		return
	}
	if _, _, e := command(r.Context(), "systemctl", req.Action, "--", name); e != nil {
		fail(w, 500, e.Error())
		return
	}
	log.Printf("action=service unit=%q operation=%s", name, req.Action)
	output(w, 200, map[string]any{"ok": true, "message": "Service action completed"})
}
func (a *app) powerAction(w http.ResponseWriter, r *http.Request) {
	if !a.power || os.Geteuid() != 0 || !systemd() {
		fail(w, 403, "Host power control is disabled")
		return
	}
	var req struct {
		Action  string `json:"action"`
		Confirm string `json:"confirm"`
	}
	if !decode(w, r, &req) {
		return
	}
	host, _ := os.Hostname()
	if req.Confirm != host {
		fail(w, 400, "Hostname confirmation does not match")
		return
	}
	if req.Action != "reboot" && req.Action != "poweroff" {
		fail(w, 400, "Invalid power action")
		return
	}
	// Scheduling survives the HTTP response and allows the client to see acceptance first.
	_, _, e := command(r.Context(), "systemd-run", "--unit=litehelm-power", "--on-active=5s", "--collect", "--", "systemctl", req.Action)
	if e != nil {
		fail(w, 500, e.Error())
		return
	}
	log.Printf("action=power operation=%s", req.Action)
	output(w, 200, map[string]any{"ok": true, "message": "Power action scheduled in 5 seconds"})
}

func protectedNames(key string) []string {
	var names []string
	for _, name := range strings.Split(os.Getenv(key), ",") {
		if name = strings.TrimSpace(strings.ToLower(name)); name != "" {
			names = append(names, name)
		}
	}
	return names
}
