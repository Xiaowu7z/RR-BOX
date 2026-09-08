// This harness is built inside the pinned sing-box module. It exercises the
// same sing-tun system stack as Android libbox with the native helper's real FD.
// Echo handlers isolate the local TUN/TCP return path from DNS and remote nodes.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"sync/atomic"
	"time"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type echoHandler struct {
	tcpBytes atomic.Int64
	udpBytes atomic.Int64
}

func (*echoHandler) JudgeFlow(uint8, netip.AddrPort, netip.AddrPort, []byte) tun.FlowVerdict {
	return tun.FlowVerdict{Action: tun.ActionAccept}
}

func (*echoHandler) NewDNSPacket([]byte, M.Socksaddr, M.Socksaddr, N.PacketWriter) {}

func (h *echoHandler) NewConnectionEx(_ context.Context, conn net.Conn, _ M.Socksaddr, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(20 * time.Second))
	n, err := io.Copy(conn, conn)
	h.tcpBytes.Add(n)
	if onClose != nil {
		onClose(err)
	}
}

func (h *echoHandler) NewPacketConnectionEx(_ context.Context, conn N.PacketConn, _ M.Socksaddr, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	defer conn.Close()
	for {
		packet := buf.NewPacket()
		destination, err := conn.ReadPacket(packet)
		if err != nil {
			packet.Release()
			if onClose != nil {
				onClose(err)
			}
			return
		}
		h.udpBytes.Add(int64(packet.Len()))
		// WritePacket takes ownership of the packet buffer.
		if err = conn.WritePacket(packet, destination); err != nil {
			if onClose != nil {
				onClose(err)
			}
			return
		}
	}
}

func run() error {
	fd := flag.Int("fd", -1, "already configured TUN descriptor passed by the real Root helper")
	name := flag.String("name", "", "native TUN name")
	flag.Parse()
	if *fd < 0 || *name == "" || os.Geteuid() == 0 {
		return fmt.Errorf("requires a real TUN FD and an unprivileged core UID")
	}
	options := tun.Options{
		Name: *name, FileDescriptor: *fd, MTU: 1500,
		Inet4Address: []netip.Prefix{netip.MustParsePrefix("172.19.0.1/30")},
		Inet6Address: []netip.Prefix{netip.MustParsePrefix("fdfe:dcba:9876::1/126")},
		DNSMode: tun.DNSModeDisabled, Logger: logger.NOP(),
	}
	device, err := tun.New(options)
	if err != nil {
		return err
	}
	defer device.Close()
	handler := &echoHandler{}
	stack, err := tun.NewStack("system", tun.StackOptions{
		Context: context.Background(), Tun: device, TunOptions: options,
		UDPTimeout: 30 * time.Second, ICMPTimeout: 30 * time.Second,
		Handler: handler, Logger: logger.NOP(),
	})
	if err != nil {
		return err
	}
	if err = stack.Start(); err != nil {
		return err
	}
	defer stack.Close()
	if err = device.Start(); err != nil {
		return err
	}
	fmt.Println("STACK_READY")
	scanner := bufio.NewScanner(os.Stdin)
	if !scanner.Scan() || scanner.Text() != "STOP" {
		return fmt.Errorf("missing orderly harness stop")
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]int64{
		"tcp_echo_bytes": handler.tcpBytes.Load(), "udp_echo_bytes": handler.udpBytes.Load(),
	})
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
