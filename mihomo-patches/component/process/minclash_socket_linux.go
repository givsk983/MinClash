package process

import (
	"bufio"
	"context"
	"encoding/binary"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"github.com/mdlayher/netlink"
	"github.com/mdlayher/socket"
	"github.com/metacubex/mihomo/common/pool"
	C "github.com/metacubex/mihomo/constant"
	"golang.org/x/sys/unix"
)

// MinClashSocketUID accepts only plausible endpoints with a unique UID. It
// does not use the process lookup's port-only fallback.
func MinClashSocketUID(m *C.Metadata) uint32 {
	return minclashCollectSocketOwner(m, (*socketOwner).considerNetlinkSockets, (*socketOwner).considerProcSockets, false).uniqueUID()
}

func init() {
	MinClashVerifiedSocketUID = func(m *C.Metadata) uint32 {
		return minclashCollectSocketOwner(m, (*socketOwner).considerCompleteNetlinkDump, (*socketOwner).considerProcSockets, true).verifiedUID()
	}
}

func (owner *socketOwner) considerNetlinkSockets(m *C.Metadata, ipv6 bool) bool {
	address := m.SrcIP.Unmap()
	if ipv6 {
		address = netip.AddrFrom16(address.As16())
	}
	messages, err := querySocketByNetlink(m.NetWork.String(), address, int(m.SrcPort))
	if err != nil {
		return false
	}
	for _, message := range messages {
		owner.considerNetlinkRecord(m, message.Data)
	}
	return true
}

func (owner *socketOwner) considerNetlinkRecord(m *C.Metadata, data []byte) bool {
	if len(data) < inetDiagResponseSize {
		return false
	}
	response := (*inetDiagResponse)(unsafe.Pointer(&data[0]))
	var src, dst netip.Addr
	switch response.Family {
	case unix.AF_INET:
		src = netip.AddrFrom4([4]byte(response.Src[:4]))
		dst = netip.AddrFrom4([4]byte(response.Dst[:4]))
	case unix.AF_INET6:
		src = netip.AddrFrom16(response.Src)
		dst = netip.AddrFrom16(response.Dst)
	default:
		return false
	}
	owner.consider(m, netip.AddrPortFrom(src, binary.BigEndian.Uint16(response.SrcPort[:])),
		netip.AddrPortFrom(dst, binary.BigEndian.Uint16(response.DstPort[:])), response.UID)
	return true
}

// Read the terminal message ourselves because netlink.Conn.Execute discards it.
// An interrupted or truncated dump is not evidence that other UIDs are absent.
func (owner *socketOwner) considerCompleteNetlinkDump(m *C.Metadata, ipv6 bool) bool {
	request := inetDiagRequest{Family: unix.AF_INET, Protocol: unix.IPPROTO_TCP, States: 0xffffffff,
		Cookie: [2]uint32{0xffffffff, 0xffffffff}}
	if ipv6 {
		request.Family = unix.AF_INET6
	}
	if m.NetWork == C.UDP {
		request.Protocol = unix.IPPROTO_UDP
	}
	// Filter by port and family only, leaving both addresses and the remote port wildcarded.
	binary.BigEndian.PutUint16(request.SrcPort[:], m.SrcPort)
	const sequence = 1 // only one request is sent on this socket
	message := netlink.Message{Header: netlink.Header{Length: uint32(unix.NLMSG_HDRLEN + inetDiagRequestSize),
		Type:  SOCK_DIAG_BY_FAMILY,
		Flags: netlink.Request | netlink.Dump, Sequence: sequence},
		Data: (*(*[inetDiagRequestSize]byte)(unsafe.Pointer(&request)))[:]}
	encoded, err := message.MarshalBinary()
	if err != nil {
		return false
	}
	conn, err := socket.Socket(unix.AF_NETLINK, unix.SOCK_RAW, unix.NETLINK_INET_DIAG, "minclash-inet-diag", nil)
	if err != nil {
		return false
	}
	defer conn.Close()
	if err = conn.Bind(&unix.SockaddrNetlink{Family: unix.AF_NETLINK}); err != nil {
		return false
	}
	ctx, cancel := context.WithTimeout(context.Background(), 250*time.Millisecond)
	defer cancel()
	if _, err = conn.Sendmsg(ctx, encoded, nil, &unix.SockaddrNetlink{Family: unix.AF_NETLINK}, 0); err != nil {
		return false
	}
	buffer := pool.Get(32 * 1024)
	defer pool.Put(buffer)
	complete := true
	for {
		n, _, flags, from, err := conn.Recvmsg(ctx, buffer, nil, 0)
		peer, kernel := from.(*unix.SockaddrNetlink)
		if err != nil || !kernel || peer.Pid != 0 || flags&unix.MSG_TRUNC != 0 {
			return false
		}
		replies, err := syscall.ParseNetlinkMessage(buffer[:n])
		if err != nil || len(replies) == 0 {
			return false
		}
		for _, reply := range replies {
			if reply.Header.Seq != sequence {
				return false
			}
			if reply.Header.Flags&unix.NLM_F_DUMP_INTR != 0 {
				complete = false
			}
			switch reply.Header.Type {
			case unix.NLMSG_DONE:
				return complete && (len(reply.Data) == 0 || len(reply.Data) >= 4 && binary.NativeEndian.Uint32(reply.Data) == 0)
			case SOCK_DIAG_BY_FAMILY:
				if len(reply.Data) == 0 || reply.Data[0] != request.Family || !owner.considerNetlinkRecord(m, reply.Data) {
					complete = false
				}
			default:
				return false
			}
		}
	}
}

func (owner *socketOwner) considerProcSockets(m *C.Metadata, ipv6 bool) bool {
	path := "/proc/net/" + m.NetWork.String()
	if ipv6 {
		path += "6"
	}
	file, err := os.Open(path)
	if err != nil {
		return false
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	if !scanner.Scan() { // column headings
		return false
	}
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 10 {
			return false
		}
		src, srcPort, err := parseHexAddrPort(fields[1], ipv6)
		if err != nil {
			return false
		}
		if srcPort != m.SrcPort {
			continue
		}
		dst, dstPort, err := parseHexAddrPort(fields[2], ipv6)
		if err != nil {
			return false
		}
		uid, err := strconv.ParseUint(fields[7], 10, 32)
		if err != nil {
			return false
		}
		owner.consider(m, netip.AddrPortFrom(src, srcPort), netip.AddrPortFrom(dst, dstPort), uint32(uid))
	}
	return scanner.Err() == nil
}
