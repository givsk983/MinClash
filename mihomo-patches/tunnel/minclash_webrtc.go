package tunnel

import (
	"encoding/binary"
	"os"
	"strings"

	"github.com/metacubex/mihomo/component/process"
	C "github.com/metacubex/mihomo/constant"
)

// Read once per core session from the Root supervisor, independent of profile reloads.
var minclashWebRTCApps = minclashWebRTCRules(os.Getenv("MINCLASH_WEBRTC_APPS"))

func minclashWebRTCRules(value string) map[string]struct{} {
	apps := make(map[string]struct{})
	for _, name := range strings.Fields(value) {
		apps[name] = struct{}{}
	}
	return apps
}

func minclashRestrictApp(metadata *C.Metadata) bool {
	_, found := minclashWebRTCApps[process.MinClashAppName(metadata)]
	return found
}

func minclashRejectWebRTCTCP(metadata *C.Metadata) bool {
	return len(minclashWebRTCApps) > 0 && metadata.Type == C.TUN &&
		isSTUNPort(metadata.DstPort) && minclashRestrictApp(metadata)
}

func minclashRejectWebRTCUDP(packet C.PacketAdapter) bool {
	if len(minclashWebRTCApps) == 0 {
		return false
	}
	metadata := packet.Metadata()
	return metadata.Type == C.TUN &&
		(isSTUNPort(metadata.DstPort) || isSTUNDatagram(packet.Data())) && minclashRestrictApp(metadata)
}

func isSTUNPort(port uint16) bool {
	// RFC 8656 §4.1. Includes encrypted TURN on its default port.
	return port == 3478 || port == 5349
}

func isSTUNDatagram(payload []byte) bool {
	// RFC 8489 §5. TURN control messages and ICE connectivity checks use
	// this header even on ports shared with other protocols.
	const headerSize = 20
	if len(payload) < headerSize || payload[0]&0xc0 != 0 ||
		binary.BigEndian.Uint32(payload[4:8]) != 0x2112a442 {
		return false
	}
	length := int(binary.BigEndian.Uint16(payload[2:4]))
	return length%4 == 0 && length == len(payload)-headerSize
}
