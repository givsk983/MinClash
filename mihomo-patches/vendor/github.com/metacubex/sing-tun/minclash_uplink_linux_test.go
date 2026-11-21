package tun

import (
	"net"
	"testing"

	"github.com/sagernet/netlink"
	"golang.org/x/sys/unix"
)

func TestMinClashAndroidUplinkSameInterface(t *testing.T) {
	rules := []netlink.Rule{
		{Family: unix.AF_INET, Table: 1008, IifName: "lo", Mask: 0xffff},
		{Family: unix.AF_INET, Table: 1008, IifName: "lo", Mark: 0x64, Mask: 0x1ffff},
		{Family: unix.AF_INET, Table: 1010, IifName: "lo", Mark: 0x65, Mask: 0x1ffff},
	}
	routes := []netlink.Route{
		{Family: unix.AF_INET, Table: 1008, LinkIndex: 8, Gw: net.IPv4(10, 0, 0, 1), Type: unix.RTN_UNICAST},
		{Family: unix.AF_INET, Table: 1010, LinkIndex: 8, Gw: net.IPv4(10, 0, 0, 1), Type: unix.RTN_UNICAST},
	}
	tables := minclashAndroidDefaultTables(rules, routes, 20882, false)
	before := minclashAndroidUplink(rules, routes, tables)
	rules[2].Mark = 0x66
	if minclashAndroidUplink(rules, routes, tables) != before {
		t.Fatal("standby network changed the selected uplink")
	}
	rules[1].Mark = 0x67
	afterNetID := minclashAndroidUplink(rules, routes, tables)
	if afterNetID == before {
		t.Fatal("a new carrier can reuse the same interface and gateway")
	}
	routes[0].Gw = net.IPv4(10, 0, 0, 2)
	if minclashAndroidUplink(rules, routes, tables) == afterNetID {
		t.Fatal("gateway change on the same interface was ignored")
	}
	rules[0].Table = 1010
	changedTables := minclashAndroidDefaultTables(rules, routes, 20882, false)
	if minclashAndroidUplink(rules, routes, changedTables) == minclashAndroidUplink(rules, routes, tables) {
		t.Fatal("switching policy tables on the same interface was ignored")
	}
}

func TestMinClashAndroidPolicyTables(t *testing.T) {
	rules := []netlink.Rule{
		{Family: unix.AF_INET, Table: 20882, IifName: "lo", Mask: 0xffff},
		{Family: unix.AF_INET, Table: 1010, IifName: "lo", Mask: 0xffff, UIDRange: netlink.NewRuleUIDRange(10000, 10000)},
		{Family: unix.AF_INET6, Table: 1017, IifName: "lo", Mark: 0x40000, Mask: 0x4ffff},
		{Family: unix.AF_INET, Table: 1016, IifName: "lo", Mask: 0xffff},
	}
	routes := []netlink.Route{
		{Family: unix.AF_INET6, Table: 1017, LinkIndex: 7, Type: unix.RTN_UNICAST},
		{Family: unix.AF_INET, Table: 1010, LinkIndex: 9, Type: unix.RTN_UNICAST},
		{Family: unix.AF_INET, Table: 1016, LinkIndex: 8, Type: unix.RTN_UNICAST},
	}
	tables := minclashAndroidDefaultTables(rules, routes, 20882, false)
	if tables.ipv4 != 1016 || tables.ipv6 != 1017 {
		t.Fatalf("wrong system policy tables: %+v", tables)
	}
	selected := minclashAndroidSelectedRoutes(routes, tables)
	if ipv4, ipv6 := minclashAndroidFamilyLinks(selected, tables); len(selected) != 2 || ipv4 != 8 || ipv6 != 7 {
		t.Fatalf("selected IPv4 link %d and IPv6 link %d, want 8 and 7: %v", ipv4, ipv6, selected)
	}
	if ipv4, ipv6 := minclashAndroidFamilyLinks(minclashAndroidSelectedRoutes(routes[:2], tables), tables); ipv4 != 0 || ipv6 != 7 {
		t.Fatalf("IPv6-only default picked IPv4 link %d and IPv6 link %d", ipv4, ipv6)
	}
}

func TestMinClashAndroidPolicySearch(t *testing.T) {
	rules := []netlink.Rule{
		{Family: unix.AF_INET, Table: 1001, IifName: "lo", Mask: 0xffff, Priority: 30000},
		{Family: unix.AF_INET, Table: 1002, IifName: "lo", Mask: 0xffff, Priority: 30001},
	}
	live := netlink.Route{Family: unix.AF_INET, Table: 1002, LinkIndex: 8, Type: unix.RTN_UNICAST}
	tests := []struct {
		name    string
		routes  []netlink.Route
		table   int
		blocked bool
	}{
		{name: "empty", table: 1002},
		{name: "down", table: 1002, routes: []netlink.Route{
			{Family: unix.AF_INET, Table: 1001, LinkIndex: 7, Flags: unix.RTNH_F_LINKDOWN, Type: unix.RTN_UNICAST},
		}},
		{name: "throw", table: 1002, routes: []netlink.Route{
			{Family: unix.AF_INET, Table: 1001, Type: unix.RTN_THROW},
		}},
		{name: "terminal", table: 1001, blocked: true, routes: []netlink.Route{
			{Family: unix.AF_INET, Table: 1001, Type: unix.RTN_BLACKHOLE, Priority: 10},
			{Family: unix.AF_INET, Table: 1001, LinkIndex: 7, Type: unix.RTN_UNICAST, Priority: 20},
		}},
		{name: "lower metric live", table: 1001, routes: []netlink.Route{
			{Family: unix.AF_INET, Table: 1001, Type: unix.RTN_UNREACHABLE, Priority: 20},
			{Family: unix.AF_INET, Table: 1001, LinkIndex: 7, Type: unix.RTN_UNICAST, Priority: 10},
		}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			tables := minclashAndroidDefaultTables(rules, append(test.routes, live), 20882, false)
			if tables.ipv4 != test.table || tables.ipv4Blocked != test.blocked {
				t.Fatalf("default table = %d, blocked = %v; want %d, %v", tables.ipv4, tables.ipv4Blocked, test.table, test.blocked)
			}
		})
	}
}

func TestMinClashAndroidDefaultLink(t *testing.T) {
	routes := []netlink.Route{
		{LinkIndex: 5, Type: unix.RTN_UNICAST, Dst: &net.IPNet{IP: net.IPv4(10, 0, 0, 0), Mask: net.CIDRMask(8, 32)}},
		{LinkIndex: 7, Type: unix.RTN_UNICAST, Flags: unix.RTNH_F_LINKDOWN},
		{LinkIndex: 9, Type: unix.RTN_UNICAST},
	}
	if link := minclashAndroidDefaultLink(routes, false); link != 9 {
		t.Fatalf("first live default link = %d, want 9", link)
	}
	if link := minclashAndroidDefaultLink(routes[:2], false); link != 0 {
		t.Fatalf("accepted link %d without a live default route", link)
	}
	if link := minclashAndroidDefaultLink(routes[:1], true); link != 5 {
		t.Fatalf("Android VPN split route link = %d, want 5", link)
	}
}
