//go:build linux

package main

import (
	"fmt"
	"net/netip"
	"strings"
)

// These are both the widest allowed peer ranges and the only permitted bind ranges.
var privateRanges = peerRanges{
	netip.MustParsePrefix("127.0.0.0/8"), netip.MustParsePrefix("::1/128"),
	netip.MustParsePrefix("10.0.0.0/8"), netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.168.0.0/16"), netip.MustParsePrefix("fc00::/7"),
	netip.MustParsePrefix("100.64.0.0/10"),
}

type peerRanges []netip.Prefix

func hostPrefix(ip netip.Addr) netip.Prefix { return netip.PrefixFrom(ip, ip.BitLen()) }

func (ranges peerRanges) covers(p netip.Prefix) bool {
	for _, r := range ranges {
		if r.Bits() <= p.Bits() && r.Contains(p.Addr()) {
			return true
		}
	}
	return false
}

func parseEndpoint(s string) (netip.AddrPort, error) {
	ap, err := netip.ParseAddrPort(s)
	if err != nil || ap.Port() == 0 || ap.Addr().Zone() != "" {
		return netip.AddrPort{}, fmt.Errorf("expected a concrete IP:port (1..65535, no zone)")
	}
	return netip.AddrPortFrom(ap.Addr().Unmap(), ap.Port()), nil
}

func (ranges peerRanges) accepts(remoteAddr string) bool {
	ap, err := parseEndpoint(remoteAddr)
	return err == nil && ranges.covers(hostPrefix(ap.Addr()))
}

func parsePeerRange(s string) (netip.Prefix, error) {
	if ip, err := netip.ParseAddr(s); err == nil && ip.Zone() == "" {
		return hostPrefix(ip.Unmap()), nil
	}
	p, err := netip.ParsePrefix(s)
	if err != nil {
		return netip.Prefix{}, fmt.Errorf("invalid source IP or CIDR %q", s)
	}
	if p.Addr().Is4In6() {
		if p.Bits() < 96 {
			return netip.Prefix{}, fmt.Errorf("mapped IPv4 prefix %q crosses address families", s)
		}
		p = netip.PrefixFrom(p.Addr().Unmap(), p.Bits()-96)
	}
	return p.Masked(), nil
}

func networkPolicy(listen string, private bool, list string, listSet bool) (netip.AddrPort, peerRanges, error) {
	ap, err := parseEndpoint(listen)
	if err != nil {
		return ap, nil, fmt.Errorf("listen: %w", err)
	}
	if !private && !ap.Addr().IsLoopback() {
		return ap, nil, fmt.Errorf("listen must be a loopback IP unless -allow-private-listen is enabled")
	}
	if !privateRanges.covers(hostPrefix(ap.Addr())) {
		return ap, nil, fmt.Errorf("listen IP must be loopback, RFC1918, ULA or CGNAT")
	}
	if !listSet {
		if private {
			return ap, privateRanges, nil
		}
		return ap, nil, nil
	}
	var ranges peerRanges
	for _, item := range strings.Split(list, ",") {
		p, err := parsePeerRange(strings.TrimSpace(item))
		if err != nil {
			return ap, nil, err
		}
		if !privateRanges.covers(p) {
			return ap, nil, fmt.Errorf("source range %s is not wholly within a permitted network", p)
		}
		ranges = append(ranges, p)
	}
	return ap, ranges, nil
}
