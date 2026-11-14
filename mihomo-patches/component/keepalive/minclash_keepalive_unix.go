//go:build go1.23 && (linux || android)

// MinClash modification, 2026-11-14: one-time optional TCP capability check.
package keepalive

import (
	"sync"

	"golang.org/x/sys/unix"
)

var minclashKeepAliveSupported = sync.OnceValue(func() bool {
	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_STREAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		return false
	}
	defer unix.Close(fd)
	idle, interval := minclashKeepAliveTiming()
	return minclashCheckOptions(int(idle.Seconds()), int(interval.Seconds()), func(level, name, value int) error {
		return unix.SetsockoptInt(fd, level, name, value)
	})
})

func minclashCheckOptions(idle, interval int, set func(int, int, int) error) bool {
	for _, option := range []struct{ level, name, value int }{
		{unix.SOL_SOCKET, unix.SO_KEEPALIVE, 1},
		{unix.IPPROTO_TCP, unix.TCP_KEEPIDLE, idle},
		{unix.IPPROTO_TCP, unix.TCP_KEEPINTVL, interval},
		{unix.IPPROTO_TCP, unix.TCP_KEEPCNT, 3},
	} {
		if set(option.level, option.name, option.value) != nil {
			return false
		}
	}
	return true
}
