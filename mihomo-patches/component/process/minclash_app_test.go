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
