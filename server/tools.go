//go:build linux

package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
)

type portInfo struct {
	Protocol string `json:"protocol"`
	Address  string `json:"address"`
	PID      int    `json:"pid"`
	Process  string `json:"process"`
	Service  string `json:"service"`
}

var socketProcess = regexp.MustCompile(`\("([^"]*)",pid=(\d+)`)

func processService(pid int) string {
	if pid <= 0 {
		return ""
	}
	for _, line := range strings.Split(file(fmt.Sprintf("/proc/%d/cgroup", pid)), "\n") {
		for _, part := range strings.Split(line, "/") {
			if unitPattern.MatchString(part) {
				return part
			}
		}
	}
	return ""
}
func parsePorts(raw string) ([]portInfo, bool) {
	rows := []portInfo{}
	truncated := false
	for _, line := range strings.Split(raw, "\n") {
		f := strings.Fields(line)
		if len(f) < 6 {
			continue
		}
		if f[0] != "tcp" && f[0] != "udp" {
			continue
		}
		matches := socketProcess.FindAllStringSubmatch(line, -1)
		if len(matches) == 0 {
			rows = append(rows, portInfo{Protocol: f[0], Address: f[4]})
		}
		for _, m := range matches {
			pid, _ := strconv.Atoi(m[2])
			rows = append(rows, portInfo{f[0], f[4], pid, m[1], processService(pid)})
		}
		if len(rows) >= 500 {
			rows = rows[:500]
			truncated = true
			break
		}
	}
	return rows, truncated
}
func (a *app) ports(w http.ResponseWriter, r *http.Request) {
	if _, e := exec.LookPath("ss"); e != nil {
		output(w, 200, map[string]any{"supported": false, "ports": []portInfo{}, "message": "ss is not installed", "truncated": false})
		return
	}
	raw, tr, e := commandLimit(r.Context(), 256<<10, "ss", "-H", "-lntup")
	if e != nil {
		fail(w, 500, e.Error())
		return
	}
	rows, cut := parsePorts(raw)
	output(w, 200, map[string]any{"supported": true, "ports": rows, "truncated": tr || cut})
}

type timerInfo struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	Active      string `json:"active_state"`
	Sub         string `json:"sub_state"`
	Next        string `json:"next_run"`
	Last        string `json:"last_run"`
	Unit        string `json:"unit"`
	CanRun      bool   `json:"can_run"`
}

var timerPattern = regexp.MustCompile(`^[a-zA-Z0-9_][a-zA-Z0-9_.@:\x2d]*\.timer$`)

func timerAllowed(name string) bool {
	for _, s := range strings.Split(os.Getenv("LITEHELM_RUN_TIMERS"), ",") {
		if strings.TrimSpace(s) == name {
			return true
		}
	}
	return false
}
func parseTimers(raw string, a *app) []timerInfo {
	rows := []timerInfo{}
	for _, block := range strings.Split(strings.TrimSpace(raw), "\n\n") {
		props := map[string]string{}
		for _, line := range strings.Split(block, "\n") {
			k, v, ok := strings.Cut(line, "=")
			if ok {
				props[k] = v
			}
		}
		name := props["Id"]
		if !timerPattern.MatchString(name) {
			continue
		}
		unit := props["Unit"]
		if unit == "" {
			unit = strings.TrimSuffix(name, ".timer") + ".service"
		}
		next := props["NextElapseUSecRealtime"]
		if next == "" || next == "0" {
			next = "n/a"
		}
		rows = append(rows, timerInfo{name, props["Description"], props["ActiveState"], props["SubState"], next, props["LastTriggerUSec"], unit, timerAllowed(name) && unitPattern.MatchString(unit) && !a.protectedUnit(unit) && os.Geteuid() == 0})
	}
	return rows
}
func (a *app) timers(w http.ResponseWriter, r *http.Request) {
	if !systemd() {
		output(w, 200, map[string]any{"supported": false, "timers": []timerInfo{}, "truncated": false})
		return
	}
	raw, tr, e := commandLimit(r.Context(), 512<<10, "systemctl", "show", "--all", "--no-pager", "--property=Id,Description,ActiveState,SubState,NextElapseUSecRealtime,LastTriggerUSec,Unit", "--", "*.timer")
	if e != nil {
		fail(w, 500, e.Error())
		return
	}
	if tr {
		fail(w, 500, "Timer output exceeds limit; refusing incomplete list")
		return
	}
	output(w, 200, map[string]any{"supported": true, "timers": parseTimers(raw, a), "truncated": false})
}
func (a *app) runTimer(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/api/v1/timers/"), "/run")
	if !timerPattern.MatchString(name) || len(name) > 200 {
		fail(w, 400, "Invalid timer name")
		return
	}
	var req struct {
		Confirm string `json:"confirm"`
	}
	if !decode(w, r, &req) {
		return
	}
	if req.Confirm != name {
		fail(w, 400, "Timer confirmation does not match")
		return
	}
	if !systemd() || os.Geteuid() != 0 || !timerAllowed(name) {
		fail(w, 403, "Timer is not allowed for manual execution")
		return
	}
	// Resolve again immediately before starting; client never supplies a service to execute.
	raw, tr, e := command(r.Context(), "systemctl", "show", "--property=Id,Description,ActiveState,SubState,Unit", "--", name)
	if e != nil || tr {
		fail(w, 500, "Cannot resolve timer target")
		return
	}
	rows := parseTimers(raw, a)
	if len(rows) != 1 || rows[0].Name != name || !rows[0].CanRun {
		fail(w, 403, "Timer target is invalid or protected")
		return
	}
	if _, _, e = command(r.Context(), "systemctl", "start", "--no-block", "--", rows[0].Unit); e != nil {
		fail(w, 500, e.Error())
		return
	}
	log.Printf("action=timer name=%q unit=%q", name, rows[0].Unit)
	output(w, 200, map[string]any{"ok": true, "message": "Task start accepted"})
}
