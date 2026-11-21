package tun

import (
	"net"
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
	tables := minclashAndroidDefaultTables(rules, allRoutes, m.androidTunTable, m.overrideAndroidVPN)
	routes := minclashAndroidSelectedRoutes(allRoutes, tables)
	families := androidUplinkInterfaces{ipv4Blocked: tables.ipv4Blocked, ipv6Blocked: tables.ipv6Blocked}
	linkIndex := 0
	if tables.vpnTable != 0 {
		linkIndex = minclashAndroidDefaultLink(routes, true)
	} else {
		ipv4, ipv6 := minclashAndroidFamilyLinks(routes, tables)
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
	}
	if linkIndex == 0 {
		if families.ipv4Blocked || families.ipv6Blocked {
			m.androidFamilies.Store(&families)
		}
		// With no usable policy, use a single unambiguous physical link. If both
		// Wi-Fi and mobile are present, wait for Android's rule instead of guessing.
		for _, route := range allRoutes {
			if route.Table == m.androidTunTable || route.Table == unix.RT_TABLE_LOCAL ||
				tables.blockedFor(route.Family) || !minclashAndroidDefaultRoute(route) {
				continue
			}
			candidate, err := m.interfaceFinder.ByIndex(route.LinkIndex)
			if err != nil || candidate.Flags&net.FlagLoopback != 0 || strings.HasPrefix(candidate.Name, "dummy") {
				continue
			}
			if linkIndex != 0 && linkIndex != route.LinkIndex {
				return ErrNoRoute
			}
			linkIndex = route.LinkIndex
			routes = append(routes, route)
		}
		if linkIndex == 0 {
			return ErrNoRoute
		}
	}
	newInterface, err := m.interfaceFinder.ByIndex(linkIndex)
	if err != nil {
		return err
	}
	m.androidFamilies.Store(&families)
	oldVPNEnabled := m.androidVPNEnabled.Swap(tables.vpnEnabled)
	oldInterface := m.defaultInterface.Swap(newInterface)
	uplink := minclashAndroidUplink(rules, routes, tables)
	knownUplink := m.androidUplink != ""
	uplinkChanged := uplink != m.androidUplink || m.noRoute.Load()
	m.androidUplink = uplink
	if !uplinkChanged && oldInterface != nil && oldInterface.Equals(*newInterface) && oldVPNEnabled == tables.vpnEnabled {
		return nil
	}
	var flags int
	if oldVPNEnabled != tables.vpnEnabled {
		flags = FlagAndroidVPNUpdate
	}
	m.emit(newInterface, flags)
	if knownUplink {
		m.logger.Info("MinClash refreshed Android default network")
	}
	return nil
}

func (m *defaultInterfaceMonitor) DefaultInterfaceForFamily(ipv6 bool) (*control.Interface, bool) {
	families := m.androidFamilies.Load()
	if families == nil {
		return nil, false
	}
	if ipv6 {
		return families.ipv6, families.ipv6Blocked
	}
	return families.ipv4, families.ipv4Blocked
}
