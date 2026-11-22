package process

import (
	"net/netip"

	C "github.com/metacubex/mihomo/constant"
)

// MinClashAppName resolves an unambiguous TUN socket owner on Android.
var MinClashAppName = func(*C.Metadata) string { return "" }

// MinClashVerifiedSocketUID requires a complete endpoint match. Zero means
// there is no verified owner or this platform cannot verify it.
var MinClashVerifiedSocketUID = func(*C.Metadata) uint32 { return 0 }

type socketOwner struct {
	uid       uint32
	found     bool
	ambiguous bool
	exact     bool
}

func (owner *socketOwner) consider(m *C.Metadata, source, destination netip.AddrPort, uid uint32) {
	sourceMatches := source.Addr().Unmap() == m.SrcIP.Unmap()
	if source.Port() != m.SrcPort || (!sourceMatches && !source.Addr().IsUnspecified()) {
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
	if !owner.exact {
		return 0
	}
	return owner.uniqueUID()
}
