package tunnel

import (
	"errors"

	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/component/protection"
	C "github.com/metacubex/mihomo/constant"
)

var minclashVerifySocketUID = process.MinClashVerifiedSocketUID
var errMinClashOriginUnknown = errors.New("MINCLASH_ORIGIN_UNKNOWN")

type minclashPacket struct {
	C.PacketAdapter
	metadata *C.Metadata
}

func (p *minclashPacket) Metadata() *C.Metadata { return p.metadata }
func minclashIsolatePacket(p C.PacketAdapter) C.PacketAdapter {
	if len(protection.Runtime.Apps) == 0 || p.Metadata().Type != C.TUN {
		return p
	}
	m := p.Metadata().Clone()
	m.MinClashIdentified = false
	m.MinClashProtected = false
	return &minclashPacket{p, m}
}

func minclashIdentify(m *C.Metadata) error {
	if len(protection.Runtime.Apps) == 0 || m.Type != C.TUN {
		return nil
	}
	uid := minclashVerifySocketUID(m)
	if uid == 0 {
		return errMinClashOriginUnknown
	}
	m.Uid = uid
	m.MinClashIdentified = true
	m.MinClashProtected = false
	for _, app := range protection.Runtime.Apps {
		if app.UID == uid {
			m.MinClashProtected = true
			m.Process = app.Package
			break
		}
	}
	return nil
}

// Only trust an identity verified at ingress, before metadata rewriting.
func minclashResolve(m *C.Metadata) (C.Proxy, bool, error) {
	if len(protection.Runtime.Apps) == 0 || m.Type != C.TUN {
		return nil, false, nil
	}
	if !m.MinClashIdentified {
		return nil, true, errMinClashOriginUnknown
	}
	target, protected := protection.Runtime.Target(m.Uid)
	if !protected {
		return nil, false, nil
	}
	m.MinClashProtected = true
	p, exists := proxies[target]
	if !exists {
		return nil, true, errors.New("MINCLASH_PROXY_MISSING")
	}
	return p, true, nil
}
