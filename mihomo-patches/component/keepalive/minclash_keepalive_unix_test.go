//go:build go1.23 && (linux || android)

package keepalive

import (
	"errors"
	"net"
	"testing"

	"golang.org/x/sys/unix"
)

func TestMinClashKernelSocketPolicy(t *testing.T) {
	for _, mode := range []string{"off", "conservative", "balanced", "aggressive"} {
		t.Run(mode, func(t *testing.T) {
			t.Setenv("MINCLASH_TCP_KEEPALIVE", mode)
			idle, interval := minclashKeepAliveTiming()
			SetDisableKeepAlive(true)
			listener, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			dialer := &net.Dialer{}
			SetNetDialer(dialer)
			conn, err := dialer.Dial("tcp", listener.Addr().String())
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			socket, err := conn.(*net.TCPConn).SyscallConn()
			if err != nil {
				t.Fatal(err)
			}
			if err = socket.Control(func(fd uintptr) {
				if mode == "off" {
					actual, err := unix.GetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_KEEPALIVE)
					if err != nil || actual != 0 {
						t.Errorf("default-off socket enabled: %d, %v", actual, err)
					}
					return
				}
				for _, option := range []struct{ level, name, want int }{
					{unix.SOL_SOCKET, unix.SO_KEEPALIVE, 1},
					{unix.IPPROTO_TCP, unix.TCP_KEEPIDLE, int(idle.Seconds())},
					{unix.IPPROTO_TCP, unix.TCP_KEEPINTVL, int(interval.Seconds())},
					{unix.IPPROTO_TCP, unix.TCP_KEEPCNT, 3},
				} {
					actual, err := unix.GetsockoptInt(int(fd), option.level, option.name)
					if err != nil || actual != option.want {
						t.Errorf("socket option %d: got %d, want %d, err %v", option.name, actual, option.want, err)
					}
				}
			}); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestMinClashCapabilityFailure(t *testing.T) {
	for failed := 0; failed < 4; failed++ {
		calls := 0
		supported := minclashCheckOptions(120, 20, func(level, name, value int) error {
			calls++
			if calls == failed+1 {
				return errors.New("kernel rejects option")
			}
			return nil
		})
		if supported || calls != failed+1 {
			t.Fatalf("capability failure %d not handled: supported=%v calls=%d", failed, supported, calls)
		}
	}
}
