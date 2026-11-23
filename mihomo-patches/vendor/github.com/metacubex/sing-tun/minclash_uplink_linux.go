package tun

import (
	"errors"
	"fmt"
	"net/netip"
	"strings"
	"time"

	"github.com/sagernet/netlink"
	"github.com/sagernet/netlink/nl"
	"golang.org/x/sys/unix"
)

func (m *networkUpdateMonitor) startMinClashAndroidMonitor() error {
	groups := []uint{unix.RTNLGRP_LINK, unix.RTNLGRP_IPV4_ROUTE, unix.RTNLGRP_IPV6_ROUTE}
	socket, err := nl.Subscribe(unix.NETLINK_ROUTE, append(groups, unix.RTNLGRP_IPV4_RULE, unix.RTNLGRP_IPV6_RULE)...)
	if err != nil {
		m.logger.Warn("subscribe to Android policy rules: ", err)
		m.startMinClashAndroidFallback(groups)
		return nil
	}
	m.watchMinClashAndroidUplink(socket, groups)
	return nil
}

func (m *networkUpdateMonitor) startMinClashAndroidFallback(groups []uint) {
	go m.pollMinClashAndroidUplink()
	socket, err := nl.Subscribe(unix.NETLINK_ROUTE, groups...)
	if err != nil {
		m.logger.Warn("subscribe to Android route and link updates: ", err)
		return
	}
	m.watchMinClashAndroidUplink(socket, nil)
}

func (m *networkUpdateMonitor) pollMinClashAndroidUplink() {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-m.close:
			return
		case <-ticker.C:
			m.emit()
		}
	}
}

func (m *networkUpdateMonitor) watchMinClashAndroidUplink(socket *nl.NetlinkSocket, fallbackGroups []uint) {
	// A failed reader may fall back while the monitor remains open.
	done := make(chan struct{})
	go func() {
		select {
		case <-m.close:
			socket.Close()
		case <-done:
		}
	}()
	go func() {
		defer close(done)
		m.readMinClashAndroidUplink(socket, fallbackGroups)
	}()
}

func (m *networkUpdateMonitor) readMinClashAndroidUplink(socket *nl.NetlinkSocket, fallbackGroups []uint) {
	defer socket.Close()
	for {
		messages, from, err := socket.Receive()
		if err != nil {
			select {
			case <-m.close:
				return
			default:
			}
			if errors.Is(err, unix.ENOBUFS) {
				m.emit()
				continue
			}
			if errors.Is(err, unix.EINTR) {
				continue
			}
			m.logger.Error("read Android network updates: ", err)
			if fallbackGroups != nil {
				m.startMinClashAndroidFallback(fallbackGroups)
			}
			return
		}
		if from.Pid != nl.PidKernel {
			continue
		}
	messagesLoop:
		for _, message := range messages {
			switch message.Header.Type {
			case unix.RTM_NEWLINK, unix.RTM_DELLINK, unix.RTM_NEWROUTE, unix.RTM_DELROUTE,
				unix.RTM_NEWRULE, unix.RTM_DELRULE:
				m.emit()
				break messagesLoop
			}
		}
	}
}

const (
	androidNetIDMask     = 0xffff
	androidExplicitNetID = 0x10000
	androidProtectedVPN  = 0x20000
)

type minclashAndroidTables struct {
	ipv4, ipv6, vpnTable     int
	ipv4Blocked, ipv6Blocked bool
	vpnEnabled, vpnRulesSeen bool
}

func (tables minclashAndroidTables) tableFor(family int) int {
	if tables.vpnTable != 0 {
		return tables.vpnTable
	}
	switch family {
	case unix.AF_INET:
		return tables.ipv4
	case unix.AF_INET6:
		return tables.ipv6
	default:
		return 0
	}
}

func (tables minclashAndroidTables) blockedFor(family int) bool {
	return tables.vpnTable == 0 && ((family == unix.AF_INET && tables.ipv4Blocked) ||
		(family == unix.AF_INET6 && tables.ipv6Blocked))
}

func minclashAndroidRuleKind(rule netlink.Rule) int {
	if rule.UIDRange != nil || rule.Invert || rule.IifName != "lo" || rule.OifName != "" ||
		rule.Table <= 0 || rule.Mask < 0 || rule.Src.Bits() > 0 || rule.Dst.Bits() > 0 {
		return 0
	}
	mask := rule.Mask & (androidExplicitNetID | androidNetIDMask)
	if mask == androidNetIDMask && rule.Mark&androidNetIDMask != 0 {
		return 0
	}
	return mask
}

func minclashAndroidDefaultTables(rules []netlink.Rule, routes []netlink.Route, tunTable int, overrideVPN bool) minclashAndroidTables {
	var tables minclashAndroidTables
	for _, rule := range rules {
		if rule.Table <= 0 || rule.Table == tunTable {
			continue
		}
		if rule.UIDRange != nil && (rule.Mask == androidProtectedVPN || rule.Mask == androidProtectedVPN|androidExplicitNetID) {
			tables.vpnRulesSeen = true
		}
		if rule.Mask == androidProtectedVPN && rule.UIDRange != nil {
			tables.vpnEnabled = true
			if overrideVPN {
				return minclashAndroidTables{vpnTable: rule.Table, vpnEnabled: true, vpnRulesSeen: true}
			}
		}
		if minclashAndroidRuleKind(rule) != androidNetIDMask || tables.tableFor(rule.Family) != 0 {
			continue
		}
		route, found := minclashAndroidPreferredRoute(routes, rule.Family, rule.Table)
		if !found || route.Type == unix.RTN_THROW {
			continue
		}
		switch rule.Family {
		case unix.AF_INET:
			tables.ipv4 = rule.Table
			tables.ipv4Blocked = route.Type != unix.RTN_UNICAST
		case unix.AF_INET6:
			tables.ipv6 = rule.Table
			tables.ipv6Blocked = route.Type != unix.RTN_UNICAST
		}
	}
	return tables
}

func minclashAndroidPreferredRoute(routes []netlink.Route, family, table int) (netlink.Route, bool) {
	var preferred netlink.Route
	found := false
	for _, route := range routes {
		if route.Family != family || route.Table != table || !minclashAndroidDefaultPrefix(route) {
			continue
		}
		switch route.Type {
		case unix.RTN_UNICAST:
			if !minclashAndroidDefaultRoute(route) {
				continue
			}
		case unix.RTN_THROW, unix.RTN_UNREACHABLE, unix.RTN_BLACKHOLE, unix.RTN_PROHIBIT:
		default:
			continue
		}
		if !found || route.Priority < preferred.Priority {
			preferred, found = route, true
		}
	}
	return preferred, found
}

func minclashAndroidSelectedRoutes(routes []netlink.Route, tables minclashAndroidTables) []netlink.Route {
	var selected []netlink.Route
	for _, route := range routes {
		if route.Table != 0 && route.Table == tables.tableFor(route.Family) {
			selected = append(selected, route)
		}
	}
	return selected
}

func minclashAndroidFamilyLinks(routes []netlink.Route, tables minclashAndroidTables) (ipv4, ipv6 int) {
	if route, ok := minclashAndroidPreferredRoute(routes, unix.AF_INET, tables.ipv4); ok && route.Type == unix.RTN_UNICAST {
		ipv4 = route.LinkIndex
	}
	if route, ok := minclashAndroidPreferredRoute(routes, unix.AF_INET6, tables.ipv6); ok && route.Type == unix.RTN_UNICAST {
		ipv6 = route.LinkIndex
	}
	return ipv4, ipv6
}

func minclashAndroidPhysicalFallbacks(routes []netlink.Route, tables minclashAndroidTables, tunTable int, usableLink func(int) bool) []netlink.Route {
	var fallbacks []netlink.Route
	for _, family := range []int{unix.AF_INET, unix.AF_INET6} {
		if tables.tableFor(family) != 0 {
			continue
		}
		var candidate netlink.Route
		for _, route := range routes {
			if route.Family != family || route.Table == tunTable || route.Table == unix.RT_TABLE_LOCAL ||
				!minclashAndroidDefaultRoute(route) || !usableLink(route.LinkIndex) {
				continue
			}
			if candidate.LinkIndex != 0 && candidate.LinkIndex != route.LinkIndex {
				candidate = netlink.Route{}
				break
			}
			if candidate.LinkIndex == 0 || route.Priority < candidate.Priority {
				candidate = route
			}
		}
		if candidate.LinkIndex != 0 {
			fallbacks = append(fallbacks, candidate)
		}
	}
	return fallbacks
}

func minclashAndroidScopedFallbacks(routes []netlink.Route, tables minclashAndroidTables, tunTable, linkIndex int) []netip.Prefix {
	if linkIndex == 0 {
		return nil
	}
	// A link with no default for one family can still carry a specifically routed
	// destination. Only preserve these routes on the already chosen interface.
	var prefixes []netip.Prefix
	for _, route := range routes {
		if route.LinkIndex != linkIndex || route.Table == tunTable || route.Table == unix.RT_TABLE_LOCAL ||
			tables.tableFor(route.Family) != 0 || route.Type != unix.RTN_UNICAST ||
			route.Flags&unix.RTNH_F_LINKDOWN != 0 || minclashAndroidDefaultPrefix(route) {
			continue
		}
		if prefix, err := netip.ParsePrefix(route.Dst.String()); err == nil {
			prefixes = append(prefixes, prefix)
		}
	}
	return prefixes
}

func minclashAndroidDefaultLink(routes []netlink.Route, overrideVPN bool) int {
	var linkIndex int
	for _, route := range routes {
		if minclashAndroidDefaultRoute(route) {
			if route.Family == unix.AF_INET {
				return route.LinkIndex
			}
			if linkIndex == 0 {
				linkIndex = route.LinkIndex
			}
		}
	}
	// Keep the upstream behavior for Android VPN tables containing only split routes.
	if linkIndex == 0 && overrideVPN && len(routes) != 0 {
		return routes[0].LinkIndex
	}
	return linkIndex
}

func minclashAndroidDefaultRoute(route netlink.Route) bool {
	if route.LinkIndex <= 0 || route.Type != unix.RTN_UNICAST || route.Flags&unix.RTNH_F_LINKDOWN != 0 {
		return false
	}
	return minclashAndroidDefaultPrefix(route)
}

func minclashAndroidDefaultPrefix(route netlink.Route) bool {
	if route.Dst == nil {
		return true
	}
	ones, bits := route.Dst.Mask.Size()
	return ones == 0 && bits > 0
}

func minclashAndroidUplink(rules []netlink.Rule, routes []netlink.Route, tables minclashAndroidTables) string {
	var identity strings.Builder
	fmt.Fprintf(&identity, "%d,%t,%d,%t,%d", tables.ipv4, tables.ipv4Blocked, tables.ipv6, tables.ipv6Blocked, tables.vpnTable)
	for _, rule := range rules {
		if rule.Table == tables.tableFor(rule.Family) {
			switch minclashAndroidRuleKind(rule) {
			case androidNetIDMask, androidExplicitNetID | androidNetIDMask:
				fmt.Fprintf(&identity, ";rule:%d,%d,%x,%x", rule.Family, rule.Table, rule.Mark, rule.Mask)
			}
		}
	}
	for _, route := range routes {
		if minclashAndroidDefaultRoute(route) {
			fmt.Fprintf(&identity, ";route:%d,%d,%d,%v,%v,%d", route.Family, route.Table, route.LinkIndex, route.Gw, route.Src, route.Priority)
		}
	}
	return identity.String()
}
