package tun

import (
	"net"
	"net/netip"
	"strings"

	"github.com/metacubex/sing/common/control"
	"github.com/sagernet/netlink"
	"golang.org/x/sys/unix"
)

func (m *defaultInterfaceMonitor) checkUpdate() error {
	if m.androidTunTable == 0 {
		return m.upstreamAndroidCheckUpdate()
	}
	rules, err := netlink.RuleList(netlink.FAMILY_ALL)
	if err != nil {
		return err
	}
	allRoutes, err := netlink.RouteListFiltered(netlink.FAMILY_ALL,
		&netlink.Route{Table: unix.RT_TABLE_UNSPEC}, netlink.RT_FILTER_TABLE)
	if err != nil {
		return err
	}
	return m.updateMinClashAndroidUplink(rules, allRoutes)
}

func (m *defaultInterfaceMonitor) updateMinClashAndroidUplink(rules []netlink.Rule, allRoutes []netlink.Route) error {
	tables := minclashAndroidDefaultTables(rules, allRoutes, m.androidTunTable, m.overrideAndroidVPN)
	routes := minclashAndroidSelectedRoutes(allRoutes, tables)
	families := androidUplinkInterfaces{ipv4Blocked: tables.ipv4Blocked, ipv6Blocked: tables.ipv6Blocked}
	usableLink := func(index int) bool {
		candidate, err := m.interfaceFinder.ByIndex(index)
		return err == nil && candidate.Flags&net.FlagLoopback == 0 && !strings.HasPrefix(candidate.Name, "dummy")
	}
	linkIndex := 0
	var err error
	if tables.vpnTable != 0 {
		linkIndex = minclashAndroidDefaultLink(routes, true)
	} else {
		ipv4, ipv6 := minclashAndroidFamilyLinks(routes, tables)
		if !tables.vpnRulesSeen {
			for _, route := range minclashAndroidPhysicalFallbacks(allRoutes, tables, m.androidTunTable, usableLink) {
				if route.Family == unix.AF_INET {
					ipv4 = route.LinkIndex
				} else {
					ipv6 = route.LinkIndex
				}
				routes = append(routes, route)
			}
			// A missing or ambiguous family cannot borrow the other family's link.
			families.ipv4Blocked = ipv4 == 0
			families.ipv6Blocked = ipv6 == 0
		}
		if ipv4 != 0 {
			families.ipv4, err = m.interfaceFinder.ByIndex(ipv4)
			if err != nil {
				return err
			}
		}
		if ipv6 != 0 {
			families.ipv6, err = m.interfaceFinder.ByIndex(ipv6)
			if err != nil {
				return err
			}
		}
		linkIndex = ipv4
		if linkIndex == 0 {
			linkIndex = ipv6
		}
		if !tables.vpnRulesSeen && (families.ipv4Blocked || families.ipv6Blocked) {
			families.fallbackPrefixes = minclashAndroidScopedFallbacks(allRoutes, tables, m.androidTunTable, linkIndex)
		}
	}
	if linkIndex == 0 && tables.vpnRulesSeen {
		if families.ipv4Blocked || families.ipv6Blocked {
			m.androidFamilies.Store(&families)
		}
		// Retain the existing Android VPN fallback behavior.
		// With no usable policy, use a single unambiguous physical link. If both
		// Wi-Fi and mobile are present, wait for Android's rule instead of guessing.
		for _, route := range allRoutes {
			if route.Table == m.androidTunTable || route.Table == unix.RT_TABLE_LOCAL ||
				tables.blockedFor(route.Family) || !minclashAndroidDefaultRoute(route) {
				continue
			}
			if !usableLink(route.LinkIndex) {
				continue
			}
			if linkIndex != 0 && linkIndex != route.LinkIndex {
				return ErrNoRoute
			}
			linkIndex = route.LinkIndex
			routes = append(routes, route)
		}
	}
	if linkIndex == 0 {
		if !tables.vpnRulesSeen {
			m.publishMinClashAndroidUplink(nil, families, false, "")
		}
		return ErrNoRoute
	}
	newInterface, err := m.interfaceFinder.ByIndex(linkIndex)
	if err != nil {
		return err
	}
	m.publishMinClashAndroidUplink(newInterface, families, tables.vpnEnabled, minclashAndroidUplink(rules, routes, tables))
	return nil
}

func (m *defaultInterfaceMonitor) publishMinClashAndroidUplink(newInterface *control.Interface, families androidUplinkInterfaces, vpnEnabled bool, uplink string) {
	m.androidFamilies.Store(&families)
	oldVPNEnabled := m.androidVPNEnabled.Swap(vpnEnabled)
	oldInterface := m.defaultInterface.Swap(newInterface)
	knownUplink := m.androidUplink != ""
	uplinkChanged := uplink != m.androidUplink || newInterface != nil && m.noRoute.Load()
	m.androidUplink = uplink
	sameInterface := oldInterface == newInterface || oldInterface != nil && newInterface != nil && oldInterface.Equals(*newInterface)
	if !uplinkChanged && sameInterface && oldVPNEnabled == vpnEnabled {
		return
	}
	var flags int
	if oldVPNEnabled != vpnEnabled {
		flags = FlagAndroidVPNUpdate
	}
	m.emit(newInterface, flags)
	if newInterface != nil && (knownUplink || m.noRoute.Load()) {
		m.logger.Info("MinClash refreshed Android default network")
	}
}

func (m *defaultInterfaceMonitor) DefaultInterfaceForDestination(destination netip.Addr) (*control.Interface, bool) {
	families := m.androidFamilies.Load()
	if families == nil {
		return nil, false
	}
	selected, other, blocked := families.ipv4, families.ipv6, families.ipv4Blocked
	if destination.Is6() {
		selected, other, blocked = families.ipv6, families.ipv4, families.ipv6Blocked
	}
	if blocked && other != nil {
		for _, prefix := range families.fallbackPrefixes {
			if prefix.Contains(destination) {
				return other, false
			}
		}
	}
	return selected, blocked
}
