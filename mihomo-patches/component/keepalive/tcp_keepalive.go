// MinClash modification, 2026-11-14: opt-in TCP recovery for the Root supervisor.
package keepalive

import (
	"net"
	"os"
	"runtime"
	"time"

	"github.com/metacubex/mihomo/common/atomic"
)

var (
	keepAliveIdle     = atomic.NewInt64(0)
	keepAliveInterval = atomic.NewInt64(0)
	disableKeepAlive  = atomic.NewBool(false)
)

func SetKeepAliveIdle(t time.Duration) {
	keepAliveIdle.Store(int64(t))
}

func SetKeepAliveInterval(t time.Duration) {
	keepAliveInterval.Store(int64(t))
}

func KeepAliveIdle() time.Duration {
	if minclashKeepAlive() {
		idle, _ := minclashKeepAliveTiming()
		return idle
	}
	return time.Duration(keepAliveIdle.Load())
}

func KeepAliveInterval() time.Duration {
	if minclashKeepAlive() {
		_, interval := minclashKeepAliveTiming()
		return interval
	}
	return time.Duration(keepAliveInterval.Load())
}

// Missing/unknown values preserve upstream behavior. Probe kernel support once,
// without creating a connection, before enabling any of the optional policies.
func minclashKeepAliveTiming() (time.Duration, time.Duration) {
	switch os.Getenv("MINCLASH_TCP_KEEPALIVE") {
	case "conservative":
		return 180 * time.Second, 30 * time.Second
	case "balanced":
		return 120 * time.Second, 20 * time.Second
	case "aggressive":
		return 60 * time.Second, 15 * time.Second
	default:
		return 0, 0
	}
}

func minclashKeepAlive() bool {
	idle, _ := minclashKeepAliveTiming()
	return idle > 0 && minclashKeepAliveSupported()
}

func SetDisableKeepAlive(disable bool) {
	if minclashKeepAlive() {
		setDisableKeepAlive(false)
	} else if runtime.GOOS == "android" {
		setDisableKeepAlive(true)
	} else {
		setDisableKeepAlive(disable)
	}
}

func setDisableKeepAlive(disable bool) {
	disableKeepAlive.Store(disable)
}

func DisableKeepAlive() bool {
	return disableKeepAlive.Load()
}

func SetNetDialer(dialer *net.Dialer) {
	setNetDialer(dialer)
}

func SetNetListenConfig(lc *net.ListenConfig) {
	setNetListenConfig(lc)
}

func TCPKeepAlive(c net.Conn) {
	if tcp, ok := c.(TCPConn); ok && tcp != nil {
		tcpKeepAlive(tcp)
	}
}
