package process

import (
	"net/netip"

	C "github.com/metacubex/mihomo/constant"
)

// MinClashAppName resolves an unambiguous TUN socket owner on Android.
var MinClashAppName = func(*C.Metadata) string { return "" }

type socketOwner struct {
	uid       uint32
	found     bool
	ambiguous bool
}

func (owner *socketOwner) consider(m *C.Metadata, source, destination netip.AddrPort, uid uint32) {
	if source.Port() != m.SrcPort ||
		(!source.Addr().IsUnspecified() && source.Addr().Unmap() != m.SrcIP.Unmap()) {
		return
	}
	if destination.Port() != m.DstPort || destination.Addr().Unmap() != m.DstIP.Unmap() {
		if m.NetWork != C.UDP || destination.Port() != 0 || !destination.Addr().IsUnspecified() {
			return
		}
	}
	owner.ambiguous = owner.ambiguous || (owner.found && owner.uid != uid)
	owner.uid, owner.found = uid, true
}
