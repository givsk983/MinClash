package process

import (
	"net/netip"
	"testing"

	C "github.com/metacubex/mihomo/constant"
)

func TestMinClashSocketAttribution(t *testing.T) {
	src := netip.MustParseAddrPort("192.0.2.10:34567")
	dst := netip.MustParseAddrPort("192.0.2.20:3478")
	wildcard := netip.MustParseAddrPort("[::]:0")
	metadata := C.Metadata{NetWork: C.UDP, SrcIP: src.Addr(), SrcPort: src.Port(),
		DstIP: dst.Addr(), DstPort: dst.Port()}
	var owner socketOwner
	owner.consider(&metadata, netip.MustParseAddrPort("192.0.2.99:34567"), dst, 10120)
	owner.consider(&metadata, src, netip.MustParseAddrPort("192.0.2.99:3478"), 10120)
	if owner.found {
		t.Fatal("same port on an unrelated endpoint claimed the packet")
	}
	owner = socketOwner{}
	owner.consider(&metadata, netip.MustParseAddrPort("[::]:34567"), wildcard, 10121)
	if owner.uniqueUID() != 10121 || owner.verifiedUID() != 0 {
		t.Fatal("wildcard owner is suitable for app display only")
	}
	owner.consider(&metadata, netip.MustParseAddrPort("[::ffff:192.0.2.10]:34567"), dst, 10121)
	if owner.verifiedUID() != 10121 {
		t.Fatal("multiple matching sockets for one UID should identify the same app")
	}
	owner.consider(&metadata, netip.MustParseAddrPort("0.0.0.0:34567"), wildcard, 10122)
	if owner.uniqueUID() != 0 || owner.verifiedUID() != 0 {
		t.Fatal("another UID on a wildcard socket must prevent attribution")
	}

	metadata.NetWork = C.TCP
	owner = socketOwner{}
	owner.consider(&metadata, src, wildcard, 10120)
	if owner.found {
		t.Fatal("an unconnected TCP socket claimed the packet")
	}

	src = netip.MustParseAddrPort("[2001:db8::12]:34567")
	dst = netip.MustParseAddrPort("[2001:db8::34]:443")
	metadata = C.Metadata{NetWork: C.TCP, SrcIP: src.Addr(), SrcPort: src.Port(), DstIP: dst.Addr(), DstPort: dst.Port()}
	owner.consider(&metadata, src, dst, 10121)
	if owner.verifiedUID() != 10121 {
		t.Fatal("exact IPv6 TCP socket did not identify its owner")
	}
}

func TestMinClashVerifiedUDPRejectsOtherUIDOnSourcePort(t *testing.T) {
	src := netip.MustParseAddrPort("192.0.2.10:34567")
	dst := netip.MustParseAddrPort("192.0.2.20:3478")
	otherDst := netip.MustParseAddrPort("192.0.2.30:443")
	metadata := C.Metadata{NetWork: C.UDP, SrcIP: src.Addr(), SrcPort: src.Port(),
		DstIP: dst.Addr(), DstPort: dst.Port()}
	for _, otherSrc := range []netip.AddrPort{src, netip.MustParseAddrPort("192.0.2.99:34567")} {
		owner := socketOwner{}
		owner.consider(&metadata, otherSrc, otherDst, 10120)
		owner.consider(&metadata, src, dst, 10121)
		if owner.uniqueUID() != 10121 || owner.verifiedUID() != 0 {
			t.Fatalf("another UID on %v must invalidate security attribution only", otherSrc)
		}
	}
	owner := socketOwner{}
	owner.consider(&metadata, src, dst, 10121)
	owner.consider(&metadata, src, otherDst, 10121)
	if owner.verifiedUID() != 10121 {
		t.Fatal("same-UID UDP sockets should not make attribution ambiguous")
	}
}

func TestMinClashExactSocketFallbackChecksBothFamilies(t *testing.T) {
	src := netip.MustParseAddrPort("192.0.2.10:34567")
	dst := netip.MustParseAddrPort("192.0.2.20:3478")
	otherDst := netip.MustParseAddrPort("192.0.2.30:443")
	metadata := C.Metadata{NetWork: C.UDP, SrcIP: src.Addr(), SrcPort: src.Port(),
		DstIP: dst.Addr(), DstPort: dst.Port()}
	empty := func(*socketOwner, *C.Metadata, bool) bool { return true }
	proc := func(owner *socketOwner, m *C.Metadata, ipv6 bool) bool {
		if !ipv6 {
			owner.consider(m, src, dst, 10121)
		}
		return true
	}
	if got := minclashCollectSocketOwner(&metadata, empty, proc, true).verifiedUID(); got != 10121 {
		t.Fatalf("complete /proc exact match = %d", got)
	}
	missingIPv6 := func(owner *socketOwner, m *C.Metadata, ipv6 bool) bool {
		return !ipv6 && proc(owner, m, ipv6)
	}
	if got := minclashCollectSocketOwner(&metadata, empty, missingIPv6, true).verifiedUID(); got != 0 {
		t.Fatalf("an incomplete IPv6 fallback authorized UID %d", got)
	}
	if got := minclashCollectSocketOwner(&metadata, missingIPv6, missingIPv6, true).verifiedUID(); got != 0 {
		t.Fatalf("an IPv4 match with no IPv6 evidence authorized UID %d", got)
	}
	interruptedMatch := func(owner *socketOwner, m *C.Metadata, _ bool) bool {
		owner.consider(m, src, dst, 10121)
		return false
	}
	if got := minclashCollectSocketOwner(&metadata, interruptedMatch, empty, true).verifiedUID(); got != 0 {
		t.Fatalf("an exact match from an interrupted dump authorized UID %d", got)
	}
	for _, complete := range []bool{false, true} {
		competingUID := func(owner *socketOwner, m *C.Metadata, ipv6 bool) bool {
			if ipv6 {
				owner.consider(m, netip.MustParseAddrPort("[::ffff:192.0.2.10]:34567"), otherDst, 10120)
			}
			return complete
		}
		if got := minclashCollectSocketOwner(&metadata, competingUID, proc, true).verifiedUID(); got != 0 {
			t.Fatalf("/proc replaced competing UID evidence from a dump (complete %v) to authorize UID %d", complete, got)
		}
	}
}
