package process

import (
	"net"
	"os"
	"testing"

	C "github.com/metacubex/mihomo/constant"
)

func TestMinClashCompleteNetlinkUDPDump(t *testing.T) {
	listener, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	client, err := net.DialUDP("udp4", nil, listener.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	src := client.LocalAddr().(*net.UDPAddr).AddrPort()
	dst := listener.LocalAddr().(*net.UDPAddr).AddrPort()
	if _, err := querySocketByNetlink("udp", src.Addr().Unmap(), int(src.Port())); err != nil {
		t.Skipf("the kernel does not expose UDP socket diagnostics: %v", err)
	}

	metadata := C.Metadata{NetWork: C.UDP, SrcIP: src.Addr(), SrcPort: src.Port(), DstIP: dst.Addr(), DstPort: dst.Port()}
	var owner socketOwner
	if !owner.considerCompleteNetlinkDump(&metadata, false) || !owner.exact || owner.uid != uint32(os.Getuid()) {
		t.Fatal("connected UDP socket did not appear in a complete IPv4 dump")
	}
	metadata.SrcIP, metadata.SrcPort, metadata.DstIP, metadata.DstPort = dst.Addr(), dst.Port(), src.Addr(), src.Port()
	owner = socketOwner{}
	if !owner.considerCompleteNetlinkDump(&metadata, false) || !owner.found || owner.exact || owner.verifiedUID() != 0 {
		t.Fatal("unconnected UDP socket did not appear as an unverified candidate")
	}
}
