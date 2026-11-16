package tunnel

import (
	"errors"
	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/component/protection"
	C "github.com/metacubex/mihomo/constant"
	"path/filepath"
)

var minclashFindProcess = process.FindProcessName

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
	if len(protection.Runtime.Apps) == 0 || m.Type != C.TUN || m.MinClashIdentified {
		return nil
	}
	uid, path, err := minclashFindProcess(m.NetWork.String(), m.SrcIP, int(m.SrcPort))
	if err != nil || uid == 0 {
		return errors.New("MINCLASH_ORIGIN_UNKNOWN")
	}
	m.Uid = uid
	m.Process = filepath.Base(path)
	m.ProcessPath = path
	m.MinClashIdentified = true
	_, m.MinClashProtected = protection.Runtime.Target(uid)
	return nil
}

// Run before SpecialProxy and global/direct modes. Never trust a user-switchable
// find-process-mode for a security decision. Unknown TUN origins fail closed.
func minclashResolve(m *C.Metadata) (C.Proxy, bool, error) {
	if err := minclashIdentify(m); err != nil {
		return nil, true, err
	}
	if m.MinClashIdentified {
		if target, ok := protection.Runtime.Target(m.Uid); ok {
			m.MinClashProtected = true
			p, exists := proxies[target]
			if !exists {
				return nil, true, errors.New("MINCLASH_PROXY_MISSING")
			}
			return p, true, nil
		}
	}
	return nil, false, nil
}
