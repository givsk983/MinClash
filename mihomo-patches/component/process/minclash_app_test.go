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
	owner.consider(&metadata, netip.MustParseAddrPort("[::ffff:192.0.2.10]:34567"), dst, 10121)
	owner.consider(&metadata, netip.MustParseAddrPort("[::]:34567"), wildcard, 10121)
	if !owner.found || owner.ambiguous || owner.uid != 10121 {
		t.Fatal("multiple matching sockets for one UID should identify the same app")
	}
	owner.consider(&metadata, netip.MustParseAddrPort("0.0.0.0:34567"), wildcard, 10122)
	if !owner.ambiguous {
		t.Fatal("another UID on a wildcard socket must prevent attribution")
	}

	metadata.NetWork = C.TCP
	owner = socketOwner{}
	owner.consider(&metadata, src, wildcard, 10120)
	if owner.found {
		t.Fatal("an unconnected TCP socket claimed the packet")
	}
}
