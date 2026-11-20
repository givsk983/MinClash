package process

import (
	"bufio"
	"encoding/binary"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"unsafe"

	C "github.com/metacubex/mihomo/constant"
)

// MinClashSocketUID accepts only plausible endpoints with a unique UID. It
// does not use the process lookup's port-only fallback.
func MinClashSocketUID(m *C.Metadata) uint32 {
	ip := m.SrcIP.Unmap()
	if !ip.IsValid() || !m.DstIP.IsValid() || m.SrcPort == 0 {
		return 0
	}
	addresses := []netip.Addr{ip}
	if ip.Is4() {
		addresses = append(addresses, netip.AddrFrom16(ip.As16()))
	}
	var owner socketOwner
	for _, address := range addresses {
		messages, err := querySocketByNetlink(m.NetWork.String(), address, int(m.SrcPort))
		if err != nil {
			if !owner.considerProcSockets(m, address.Is6()) {
				return 0
			}
			continue
		}
		for _, message := range messages {
			if len(message.Data) < inetDiagResponseSize {
				continue
			}
			socket := (*inetDiagResponse)(unsafe.Pointer(&message.Data[0]))
			var src, dst netip.Addr
			if address.Is4() {
				src = netip.AddrFrom4([4]byte(socket.Src[:4]))
				dst = netip.AddrFrom4([4]byte(socket.Dst[:4]))
			} else {
				src = netip.AddrFrom16(socket.Src)
				dst = netip.AddrFrom16(socket.Dst)
			}
			owner.consider(m, netip.AddrPortFrom(src, binary.BigEndian.Uint16(socket.SrcPort[:])),
				netip.AddrPortFrom(dst, binary.BigEndian.Uint16(socket.DstPort[:])), socket.UID)
		}
	}
	if owner.ambiguous {
		return 0
	}
	return owner.uid
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
	scanner.Scan() // column headings
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 10 {
			continue
		}
		src, srcPort, err := parseHexAddrPort(fields[1], ipv6)
		if err != nil || srcPort != m.SrcPort {
			continue
		}
		dst, dstPort, err := parseHexAddrPort(fields[2], ipv6)
		if err != nil {
			continue
		}
		uid, err := strconv.ParseUint(fields[7], 10, 32)
		if err == nil {
			owner.consider(m, netip.AddrPortFrom(src, srcPort), netip.AddrPortFrom(dst, dstPort), uint32(uid))
		}
	}
	return scanner.Err() == nil
}
