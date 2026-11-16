package outbound

import (
	"context"
	C "github.com/metacubex/mihomo/constant"
	"net"
	"testing"
	"time"
)

func TestMinClashDirectNeverOpensSocket(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	m := &C.Metadata{NetWork: C.TCP, MinClashProtected: true}
	if err = m.SetRemoteAddress(l.Addr().String()); err != nil {
		t.Fatal(err)
	}
	for _, d := range []*Direct{NewDirect(), NewCompatible(), NewDirectWithOption(DirectOption{Name: "RenamedDirect"})} {
		if c, e := d.DialContext(context.Background(), m); e == nil {
			c.Close()
			t.Fatal("direct allowed")
		}
		if c, e := d.ListenPacketContext(context.Background(), m); e == nil {
			c.Close()
			t.Fatal("UDP direct allowed")
		}
	}
	l.(*net.TCPListener).SetDeadline(time.Now().Add(20 * time.Millisecond))
	if c, err := l.Accept(); err == nil {
		c.Close()
		t.Fatal("protected request reached direct endpoint")
	}
	m.MinClashProtected = false
	c, err := NewDirect().DialContext(context.Background(), m)
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
}
