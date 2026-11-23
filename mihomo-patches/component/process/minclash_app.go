package process

import (
	"net/netip"

	C "github.com/metacubex/mihomo/constant"
)

// MinClashAppName resolves an unambiguous TUN socket owner on Android.
var MinClashAppName = func(*C.Metadata) string { return "" }

// MinClashVerifiedSocketUID requires a complete endpoint match without a
// conflicting UID. Zero means ownership could not be verified.
var MinClashVerifiedSocketUID = func(*C.Metadata) uint32 { return 0 }

type socketOwner struct {
	uid             uint32
	found           bool
	ambiguous       bool
	exact           bool
	udpPortUID      uint32
	udpPortSeen     bool
	udpPortConflict bool
}

func (owner *socketOwner) consider(m *C.Metadata, source, destination netip.AddrPort, uid uint32) {
	if source.Port() != m.SrcPort {
		return
	}
	if m.NetWork == C.UDP {
		// A datagram can override the socket's connected destination and use
		// packet info to select a different local address. Keep every UID on
		// this port in the security check, independently of the display match.
		owner.udpPortConflict = owner.udpPortConflict || (owner.udpPortSeen && owner.udpPortUID != uid)
		owner.udpPortUID, owner.udpPortSeen = uid, true
	}
	sourceMatches := source.Addr().Unmap() == m.SrcIP.Unmap()
	if !sourceMatches && !source.Addr().IsUnspecified() {
		return
	}
	destinationMatches := destination.Port() == m.DstPort && destination.Addr().Unmap() == m.DstIP.Unmap()
	if !destinationMatches && (m.NetWork != C.UDP || destination.Port() != 0 || !destination.Addr().IsUnspecified()) {
		return
	}
	owner.ambiguous = owner.ambiguous || (owner.found && owner.uid != uid)
	owner.exact = owner.exact || (sourceMatches && destinationMatches)
	owner.uid, owner.found = uid, true
}

func (owner socketOwner) uniqueUID() uint32 {
	if owner.ambiguous {
		return 0
	}
	return owner.uid
}

func (owner socketOwner) verifiedUID() uint32 {
	if !owner.exact || owner.udpPortConflict {
		return 0
	}
	return owner.uniqueUID()
}

type minclashSocketTable func(*socketOwner, *C.Metadata, bool) bool

func minclashCollectSocketOwner(m *C.Metadata, netlink, proc minclashSocketTable, recoverExact bool) socketOwner {
	if m.NetWork != C.TCP && m.NetWork != C.UDP {
		return socketOwner{}
	}
	ip := m.SrcIP.Unmap()
	if !ip.IsValid() || ip.IsUnspecified() || !m.DstIP.IsValid() || m.DstIP.IsUnspecified() || m.SrcPort == 0 || m.DstPort == 0 {
		return socketOwner{}
	}
	families := []bool{true}
	if ip.Is4() {
		// A dual-stack AF_INET6 socket can send IPv4-mapped datagrams.
		families = []bool{false, true}
	}
	var owner socketOwner
	var readNetlink [2]bool
	for i, ipv6 := range families {
		previousExact := owner.exact
		if netlink(&owner, m, ipv6) {
			readNetlink[i] = true
			continue
		}
		// An interrupted enumeration can reveal a conflict, but cannot
		// establish a new exact match.
		owner.exact = previousExact
		if !proc(&owner, m, ipv6) {
			return socketOwner{}
		}
	}
	if recoverExact && !owner.exact && !owner.ambiguous && !owner.udpPortConflict {
		// A successful socket dump can miss rows visible to /proc. Cover all
		// relevant families there and keep every UID from the first scan.
		for i, ipv6 := range families {
			if readNetlink[i] && !proc(&owner, m, ipv6) {
				return socketOwner{}
			}
		}
	}
	return owner
}
