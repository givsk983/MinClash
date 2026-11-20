//go:build !cmfa

package sing_tun

import (
	"github.com/metacubex/mihomo/component/process"
	C "github.com/metacubex/mihomo/constant"
)

func init() {
	process.MinClashAppName = minclashAppName
}

func minclashAppName(m *C.Metadata) string {
	uid := process.MinClashSocketUID(m)
	appID := uid % 100000
	if appID < 10000 || appID >= 20000 {
		return ""
	}
	packages, err := getPackageManager()
	if err != nil {
		return ""
	}
	if _, shared := packages.SharedPackageByID(appID); shared {
		return ""
	}
	names, _ := packages.PackagesByID(appID)
	if len(names) != 1 {
		return ""
	}
	return names[0]
}
