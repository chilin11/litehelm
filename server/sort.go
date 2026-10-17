//go:build linux

package main

import "sort"

func sortProcesses(ps []process, cpu bool) {
	sort.Slice(ps, func(i, j int) bool {
		if cpu && ps[i].CPU != ps[j].CPU {
			return ps[i].CPU > ps[j].CPU
		}
		if ps[i].Memory != ps[j].Memory {
			return ps[i].Memory > ps[j].Memory
		}
		return ps[i].PID < ps[j].PID
	})
}
