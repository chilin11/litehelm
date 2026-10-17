//go:build linux

package main

import (
	"sort"
	"strings"
)

// Include installed units that systemd garbage-collected after stopping.
// Uninstantiated templates cannot be started without an instance name.
func mergeServices(loaded, installed string) []serviceInfo {
	units := map[string]serviceInfo{}
	for _, line := range strings.Split(loaded, "\n") {
		f := strings.Fields(line)
		if len(f) > 0 && f[0] == "●" {
			f = f[1:]
		}
		if len(f) < 4 || !unitPattern.MatchString(f[0]) {
			continue
		}
		units[f[0]] = serviceInfo{f[0], strings.Join(f[4:], " "), f[1], f[2], f[3]}
	}
	for _, line := range strings.Split(installed, "\n") {
		f := strings.Fields(line)
		if len(f) < 2 || !unitPattern.MatchString(f[0]) || strings.HasSuffix(f[0], "@.service") {
			continue
		}
		if _, ok := units[f[0]]; ok {
			continue
		}
		load, active, sub := "not-loaded", "inactive", "dead"
		if f[1] == "masked" || f[1] == "masked-runtime" {
			load, active, sub = "masked", "unknown", "masked"
		}
		units[f[0]] = serviceInfo{f[0], "已安装 · " + f[1], load, active, sub}
	}
	result := make([]serviceInfo, 0, len(units))
	for _, unit := range units {
		result = append(result, unit)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].Name < result[j].Name })
	return result
}
