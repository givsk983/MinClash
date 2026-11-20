package tunnel

import (
	"io"
	"net"
	"net/netip"
	"testing"

	"github.com/metacubex/mihomo/component/process"
	C "github.com/metacubex/mihomo/constant"
	N "github.com/metacubex/mihomo/context"
)

type webRTCTestPacket struct {
	C.PacketAdapter
	metadata C.Metadata
	payload  []byte
	dropped  bool
}

func (p *webRTCTestPacket) Metadata() *C.Metadata { return &p.metadata }
func (p *webRTCTestPacket) Data() []byte          { return p.payload }
func (p *webRTCTestPacket) Drop()                 { p.dropped = true }

func TestMinClashSTUNDatagram(t *testing.T) {
	binding := []byte{0x00, 0x01, 0, 0, 0x21, 0x12, 0xa4, 0x42, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}
	allocate := append([]byte(nil), binding...)
	allocate[1], allocate[3] = 0x03, 8
	allocate = append(allocate, 0, 0x19, 0, 4, 17, 0, 0, 0) // REQUESTED-TRANSPORT: UDP
	for _, test := range []struct {
		name    string
		payload []byte
		want    bool
	}{
		{"ICE binding request", binding, true},
		{"TURN allocate request", allocate, true},
		{"truncated header", binding[:19], false},
		{"truncated body", allocate[:24], false},
		{"unexpected trailing bytes", append(binding[:20:20], 0, 0, 0, 0), false},
		{"unaligned body", append([]byte{0, 1, 0, 1}, append(binding[4:20:20], 1)...), false},
		{"QUIC-like header", append([]byte{0xc0}, binding[1:]...), false},
		{"other protocol", make([]byte, 20), false},
	} {
		t.Run(test.name, func(t *testing.T) {
			if got := isSTUNDatagram(test.payload); got != test.want {
				t.Fatalf("isSTUNDatagram = %t, want %t", got, test.want)
			}
		})
	}
}

func TestMinClashWebRTCTunnelIngress(t *testing.T) {
	savedRules, savedStatus, savedMode := minclashWebRTCApps, status.Load(), Mode()
	savedResolver := process.MinClashAppName
	t.Cleanup(func() {
		minclashWebRTCApps = savedRules
		process.MinClashAppName = savedResolver
		status.Store(savedStatus)
		SetMode(savedMode)
	})
	OnRunning()
	packet := &webRTCTestPacket{
		metadata: C.Metadata{Type: C.TUN, DstIP: netip.MustParseAddr("2001:db8::1"), DstPort: 49152},
		payload:  []byte{0, 1, 0, 0, 0x21, 0x12, 0xa4, 0x42, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12},
	}
	turn := C.Metadata{Type: C.TUN, DstIP: netip.MustParseAddr("192.0.2.1"), DstPort: 5349}
	lookups := 0
	app := "org.example.live"
	process.MinClashAppName = func(*C.Metadata) string { lookups++; return app }
	minclashWebRTCApps = minclashWebRTCRules("")
	if minclashRejectWebRTCUDP(packet) || minclashRejectWebRTCTCP(&turn) {
		t.Fatal("disabled policy changed traffic")
	}
	if lookups != 0 {
		t.Fatal("disabled policy resolved the app")
	}
	minclashWebRTCApps = minclashWebRTCRules("org.example.live\norg.example.second")
	for _, mode := range []TunnelMode{Rule, Global, Direct} {
		app = "org.example.live"
		SetMode(mode)
		packet.dropped = false
		handleUDPConn(packet)
		if !packet.dropped {
			t.Fatalf("%v: IPv6 STUN was not dropped before NAT", mode)
		}
		client, server := net.Pipe()
		handleTCPConn(N.NewConnContext(server, &turn))
		if _, err := client.Read(make([]byte, 1)); err != io.EOF {
			t.Errorf("%v: blocked TCP connection returned %v", mode, err)
		}
		client.Close()
		for _, allowed := range []string{"com.tencent.mm", ""} {
			app = allowed
			if minclashRejectWebRTCUDP(packet) || minclashRejectWebRTCTCP(&turn) {
				t.Fatalf("%v: untargeted app %q was blocked", mode, allowed)
			}
		}
	}
	app = "org.example.second"
	packet.metadata.DstPort, packet.payload = 3478, nil
	if !minclashRejectWebRTCUDP(packet) {
		t.Fatal("default STUN port was not blocked")
	}
	packet.metadata.DstPort = 443
	turn.DstPort = 443
	previousLookups := lookups
	if minclashRejectWebRTCUDP(packet) || minclashRejectWebRTCTCP(&turn) {
		t.Fatal("ordinary HTTPS/QUIC was blocked")
	}
	packet.metadata.Type, packet.metadata.DstPort = C.INNER, 3478
	turn.Type, turn.DstPort = C.INNER, 5349
	if minclashRejectWebRTCUDP(packet) || minclashRejectWebRTCTCP(&turn) {
		t.Fatal("internal core traffic was blocked")
	}
	if lookups != previousLookups {
		t.Fatal("unrelated traffic triggered app lookup")
	}
}
