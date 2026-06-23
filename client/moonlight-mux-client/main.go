package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

const (
	magic             = "SAKURA-MUX-0002"
	defaultBasePort   = 47989
	defaultUDPTimeout = 60
)

var pbkdfSalt = []byte{
	0x3a, 0xc8, 0xe7, 0xb8, 0x86, 0xab, 0xb9, 0x4d,
	0x65, 0x6d, 0x76, 0xd1, 0xdc, 0xc3, 0xe8, 0xd0,
	0xe4, 0xbb, 0xf9, 0x05, 0x01, 0xb1, 0x0d, 0xa1,
	0xad, 0x23, 0x7e, 0x29, 0xaf, 0x72, 0x05, 0x87,
}

type mapping struct {
	index  uint16
	proto  string
	offset int
}

var defaultMappings = []mapping{
	{index: 0, proto: "tcp", offset: 0},
	{index: 1, proto: "tcp", offset: -5},
	{index: 2, proto: "tcp", offset: 21},
	{index: 3, proto: "udp", offset: 9},
	{index: 4, proto: "udp", offset: 11},
	{index: 5, proto: "udp", offset: 10},
}

type config struct {
	server     string
	localHost  string
	basePort   int
	key        string
	udpTimeout int
	debug      bool
	tcpOnly    bool
}

func main() {
	var cfg config
	flag.StringVar(&cfg.server, "server", "", "mux server address (host:port)")
	flag.StringVar(&cfg.localHost, "local", "127.0.0.1", "local host to bind")
	flag.IntVar(&cfg.basePort, "port", defaultBasePort, "local base port")
	flag.StringVar(&cfg.key, "key", "", "authentication key")
	flag.IntVar(&cfg.udpTimeout, "udp_timeout", defaultUDPTimeout, "UDP timeout in seconds")
	flag.BoolVar(&cfg.debug, "debug", false, "verbose logging")
	flag.BoolVar(&cfg.tcpOnly, "tcp_only", false, "only start TCP listeners")
	flag.Parse()

	if cfg.server == "" {
		log.Fatal("server address must be specified")
	}
	if len(cfg.key) < 8 {
		log.Fatal("key is required and must be at least 8 characters long")
	}
	if cfg.basePort < 1 || cfg.basePort > 65535 {
		log.Fatal("base port must be between 1 and 65535")
	}
	if cfg.udpTimeout < 10 {
		cfg.udpTimeout = 10
	}

	derivedKey := pbkdf2SHA256([]byte(cfg.key), pbkdfSalt, 10000, 32)
	log.Printf("moonlightmux moonlight client prototype")
	log.Printf("config loaded: ServerAddr=%s, BasePort=%d, LocalHost=%s", cfg.server, cfg.basePort, cfg.localHost)

	var wg sync.WaitGroup
	errCh := make(chan error, len(defaultMappings))
	stop := make(chan struct{})

	for _, m := range defaultMappings {
		port := cfg.basePort + m.offset
		if port < 1 || port > 65535 {
			log.Fatalf("mapped port out of range: base=%d offset=%d", cfg.basePort, m.offset)
		}
		switch m.proto {
		case "tcp":
			wg.Add(1)
			go func(m mapping, port int) {
				defer wg.Done()
				errCh <- runTCPListener(cfg, derivedKey, m, port, stop)
			}(m, port)
		case "udp":
			if cfg.tcpOnly {
				continue
			}
			wg.Add(1)
			go func(m mapping, port int) {
				defer wg.Done()
				errCh <- runUDPListener(cfg, derivedKey, m, port, stop)
			}(m, port)
		}
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)

	select {
	case sig := <-sigCh:
		log.Printf("received %s, shutting down", sig)
		close(stop)
	case err := <-errCh:
		if err != nil {
			close(stop)
			log.Fatal(err)
		}
	}

	wg.Wait()
}

func runTCPListener(cfg config, key []byte, m mapping, port int, stop <-chan struct{}) error {
	addr := net.JoinHostPort(cfg.localHost, strconv.Itoa(port))
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("listen TCP index %d on %s: %w", m.index, addr, err)
	}
	defer ln.Close()
	log.Printf("client listening on TCP %s", addr)

	go func() {
		<-stop
		_ = ln.Close()
	}()

	for {
		conn, err := ln.Accept()
		if err != nil {
			select {
			case <-stop:
				return nil
			default:
				return err
			}
		}
		go handleTCPConn(cfg, key, m, conn)
	}
}

func handleTCPConn(cfg config, key []byte, m mapping, local net.Conn) {
	defer local.Close()

	if cfg.debug {
		log.Printf("dial TCP index %d from %s", m.index, local.RemoteAddr())
	}

	remote, err := dialMux(cfg.server, key, m.index)
	if err != nil {
		log.Printf("TCP index %d handshake failed: %v", m.index, err)
		return
	}
	defer remote.Close()

	proxy(local, remote)
}

func runUDPListener(cfg config, key []byte, m mapping, port int, stop <-chan struct{}) error {
	addr := net.JoinHostPort(cfg.localHost, strconv.Itoa(port))
	udpAddr, err := net.ResolveUDPAddr("udp", addr)
	if err != nil {
		return err
	}
	conn, err := net.ListenUDP("udp", udpAddr)
	if err != nil {
		return fmt.Errorf("listen UDP index %d on %s: %w", m.index, addr, err)
	}
	_ = conn.SetReadBuffer(4 * 1024 * 1024)
	_ = conn.SetWriteBuffer(4 * 1024 * 1024)
	defer conn.Close()
	log.Printf("client listening on UDP %s", addr)

	remote, err := dialMux(cfg.server, key, m.index)
	if err != nil {
		return fmt.Errorf("dial UDP index %d: %w", m.index, err)
	}
	defer remote.Close()
	if cfg.debug {
		log.Printf("dial UDP index %d from %s", m.index, addr)
	}

	var writeMu sync.Mutex
	var peerMu sync.RWMutex
	var lastPeer *net.UDPAddr
	var localStats udpStats
	var remoteStats udpStats
	var sendSeq uint64

	if cfg.debug {
		go logUDPStats(m.index, stop, &localStats, &remoteStats)
	}

	go func() {
		<-stop
		_ = conn.Close()
		_ = remote.Close()
	}()

	go func() {
		for {
			frames, err := readUDPFrames(remote)
			if err != nil {
				select {
				case <-stop:
				default:
					if cfg.debug && err != io.EOF {
						log.Printf("UDP index %d read frame ended: %v", m.index, err)
					}
				}
				return
			}

			for _, frame := range frames {
				peer := frame.addr
				if peer == nil {
					peerMu.RLock()
					if lastPeer != nil {
						copied := *lastPeer
						peer = &copied
					}
					peerMu.RUnlock()
				}
				if peer == nil {
					if cfg.debug {
						log.Printf("UDP index %d dropping response before peer is known", m.index)
					}
					continue
				}
				if cfg.debug {
					count := remoteStats.observe(len(frame.payload))
					if shouldLogUDPPacket(count) || frame.trailing != 0 {
						log.Printf(
							"UDP index %d mux->local packets=%d peer=%s frame=%d batch=%d/%d mode=%s seq=%d payload=%d trailing=%d prefix=%s",
							m.index,
							count,
							peer,
							frame.frameLen,
							frame.batchIndex+1,
							frame.batchCount,
							frame.batchMode,
							frame.seq,
							len(frame.payload),
							frame.trailing,
							frame.prefix,
						)
					}
				}
				if _, err := conn.WriteToUDP(frame.payload, peer); err != nil {
					select {
					case <-stop:
					default:
						log.Printf("UDP index %d write local failed: %v", m.index, err)
					}
					return
				}
			}
		}
	}()

	for {
		buf := make([]byte, 64*1024)
		n, peer, err := conn.ReadFromUDP(buf)
		if err != nil {
			select {
			case <-stop:
				return nil
			default:
				return err
			}
		}

		peerMu.Lock()
		copied := *peer
		lastPeer = &copied
		peerMu.Unlock()

		if cfg.debug {
			count := localStats.observe(n)
			if shouldLogUDPPacket(count) {
				log.Printf("UDP index %d local->mux packets=%d peer=%s payload=%d", m.index, count, peer, n)
			}
		}

		writeMu.Lock()
		seq := sendSeq
		sendSeq++
		err = writeUDPFrame(remote, peer, buf[:n], seq)
		writeMu.Unlock()
		if err != nil {
			return fmt.Errorf("UDP index %d write frame failed: %w", m.index, err)
		}
	}
}

type udpFrame struct {
	addr       *net.UDPAddr
	payload    []byte
	frameLen   int
	batchIndex int
	batchCount int
	batchMode  string
	seq        uint64
	trailing   int
	prefix     string
}

type udpStats struct {
	packets    uint64
	bytes      uint64
	maxPayload uint64
}

func (s *udpStats) observe(payloadLen int) uint64 {
	n := uint64(payloadLen)
	count := atomic.AddUint64(&s.packets, 1)
	atomic.AddUint64(&s.bytes, n)
	for {
		old := atomic.LoadUint64(&s.maxPayload)
		if n <= old || atomic.CompareAndSwapUint64(&s.maxPayload, old, n) {
			break
		}
	}
	return count
}

func logUDPStats(index uint16, stop <-chan struct{}, localStats, remoteStats *udpStats) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	var lastLocalPackets, lastLocalBytes uint64
	var lastRemotePackets, lastRemoteBytes uint64
	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
			localPackets := atomic.LoadUint64(&localStats.packets)
			localBytes := atomic.LoadUint64(&localStats.bytes)
			remotePackets := atomic.LoadUint64(&remoteStats.packets)
			remoteBytes := atomic.LoadUint64(&remoteStats.bytes)

			localDeltaPackets := localPackets - lastLocalPackets
			localDeltaBytes := localBytes - lastLocalBytes
			remoteDeltaPackets := remotePackets - lastRemotePackets
			remoteDeltaBytes := remoteBytes - lastRemoteBytes
			if localDeltaPackets != 0 || remoteDeltaPackets != 0 {
				log.Printf(
					"UDP index %d stats local->mux pps=%d Bps=%d total=%d max=%d mux->local pps=%d Bps=%d total=%d max=%d",
					index,
					localDeltaPackets,
					localDeltaBytes,
					localPackets,
					atomic.LoadUint64(&localStats.maxPayload),
					remoteDeltaPackets,
					remoteDeltaBytes,
					remotePackets,
					atomic.LoadUint64(&remoteStats.maxPayload),
				)
			}

			lastLocalPackets = localPackets
			lastLocalBytes = localBytes
			lastRemotePackets = remotePackets
			lastRemoteBytes = remoteBytes
		}
	}
}

func shouldLogUDPPacket(count uint64) bool {
	return count <= 5 || count%100 == 0
}

func dialMux(server string, key []byte, index uint16) (net.Conn, error) {
	conn, err := dialTCP(server)
	if err != nil {
		return nil, err
	}
	if tcp, ok := conn.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
		_ = tcp.SetKeepAlive(true)
		_ = tcp.SetKeepAlivePeriod(30 * time.Second)
		_ = tcp.SetReadBuffer(4 * 1024 * 1024)
		_ = tcp.SetWriteBuffer(4 * 1024 * 1024)
	}

	greeting := make([]byte, len(magic)+16+16)
	if _, err := io.ReadFull(conn, greeting); err != nil {
		_ = conn.Close()
		return nil, err
	}
	if string(greeting[:len(magic)]) != magic {
		_ = conn.Close()
		return nil, fmt.Errorf("unexpected magic %q", string(greeting[:len(magic)]))
	}

	nonce := greeting[len(magic)+16:]
	mac := hmac.New(sha256.New, key)
	_, _ = mac.Write(nonce)

	reply := make([]byte, 0, sha256.Size+2)
	reply = append(reply, mac.Sum(nil)...)
	reply = binary.BigEndian.AppendUint16(reply, index)
	if _, err := conn.Write(reply); err != nil {
		_ = conn.Close()
		return nil, err
	}

	status := make([]byte, 1)
	if _, err := io.ReadFull(conn, status); err != nil {
		_ = conn.Close()
		return nil, err
	}
	if status[0] != 0 {
		_ = conn.Close()
		return nil, fmt.Errorf("server rejected mux index %d with status 0x%02x", index, status[0])
	}
	return conn, nil
}

func dialTCP(address string) (net.Conn, error) {
	conn, err := net.Dial("tcp", address)
	if err == nil {
		return conn, nil
	}

	host, port, splitErr := net.SplitHostPort(address)
	if splitErr != nil || net.ParseIP(host) != nil {
		return nil, err
	}

	ips, resolveErr := resolveHostFallback(host)
	if resolveErr != nil {
		return nil, fmt.Errorf("%w; fallback DNS lookup failed: %v", err, resolveErr)
	}

	dialer := net.Dialer{Timeout: 10 * time.Second}
	var lastErr error = err
	for _, ip := range ips {
		conn, dialErr := dialer.Dial("tcp", net.JoinHostPort(ip.String(), port))
		if dialErr == nil {
			return conn, nil
		}
		lastErr = dialErr
	}
	return nil, fmt.Errorf("%w; fallback DNS resolved %d addresses, last dial failed: %v", err, len(ips), lastErr)
}

func resolveHostFallback(host string) ([]net.IPAddr, error) {
	dnsServers := []string{
		"223.5.5.5:53",
		"119.29.29.29:53",
		"1.1.1.1:53",
		"8.8.8.8:53",
	}
	var lastErr error
	for _, dnsServer := range dnsServers {
		resolver := net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
				dialer := net.Dialer{Timeout: 2 * time.Second}
				return dialer.DialContext(ctx, "udp", dnsServer)
			},
		}
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		ips, err := resolver.LookupIPAddr(ctx, host)
		cancel()
		if err == nil && len(ips) > 0 {
			return ips, nil
		}
		if err != nil {
			lastErr = err
		}
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no addresses returned")
	}
	return nil, lastErr
}

func proxy(a, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_, _ = io.Copy(a, b)
		closeWrite(a)
	}()
	go func() {
		defer wg.Done()
		_, _ = io.Copy(b, a)
		closeWrite(b)
	}()
	wg.Wait()
	_ = a.Close()
	_ = b.Close()
}

func closeWrite(conn net.Conn) {
	if tcp, ok := conn.(*net.TCPConn); ok {
		_ = tcp.CloseWrite()
		return
	}
	_ = conn.Close()
}

func writeUDPFrame(w io.Writer, addr *net.UDPAddr, payload []byte, seq uint64) error {
	ip4 := addr.IP.To4()
	if ip4 == nil {
		return fmt.Errorf("IPv6 UDP framing is not implemented yet: %s", addr)
	}
	body := make([]byte, 0, 1+binary.MaxVarintLen64+binary.MaxVarintLen64+6+1+binary.MaxVarintLen64+binary.MaxVarintLen64+len(payload))
	body = append(body, 0x01)
	body = binary.AppendUvarint(body, seq)
	body = binary.AppendUvarint(body, 6)
	body = append(body, ip4...)
	body = binary.BigEndian.AppendUint16(body, uint16(addr.Port))
	body = append(body, 0x01)
	body = binary.AppendUvarint(body, seq)
	body = binary.AppendUvarint(body, uint64(len(payload)))
	body = append(body, payload...)

	frame := make([]byte, 0, 4+len(body))
	frame = binary.BigEndian.AppendUint32(frame, uint32(len(body)))
	frame = append(frame, body...)
	_, err := w.Write(frame)
	return err
}

func readUDPFrames(r io.Reader) ([]udpFrame, error) {
	var lenBuf [4]byte
	if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
		return nil, err
	}
	frameLen := binary.BigEndian.Uint32(lenBuf[:])
	if frameLen < 1 || frameLen > 65535 {
		return nil, fmt.Errorf("invalid UDP frame length %d", frameLen)
	}
	frame := make([]byte, frameLen)
	if _, err := io.ReadFull(r, frame); err != nil {
		return nil, err
	}
	prefix := framePrefix(frame)

	if frame[0] == 0x00 {
		frames, err := readUDPResponseFrames(frame, prefix)
		if err != nil {
			return nil, err
		}
		return frames, nil
	}

	if frame[0] != 0x01 {
		return nil, fmt.Errorf("unsupported UDP frame type 0x%02x", frame[0])
	}
	offset := 1
	addrSeq, err := readFrameUvarint(frame, &offset)
	if err != nil {
		return nil, fmt.Errorf("invalid UDP address sequence: %w", err)
	}
	addrLen, err := readFrameUvarint(frame, &offset)
	if err != nil {
		return nil, fmt.Errorf("invalid UDP address length: %w", err)
	}
	if addrLen != 6 {
		return nil, fmt.Errorf("unsupported UDP address length %d", addrLen)
	}
	if len(frame) < offset+int(addrLen)+1 {
		return nil, fmt.Errorf("short UDP frame")
	}
	ip := net.IPv4(frame[offset], frame[offset+1], frame[offset+2], frame[offset+3])
	port := int(binary.BigEndian.Uint16(frame[offset+4 : offset+6]))
	offset += int(addrLen)
	if frame[offset] != 0x01 {
		return nil, fmt.Errorf("unsupported UDP payload marker 0x%02x", frame[offset])
	}
	offset++
	payloadSeq, err := readFrameUvarint(frame, &offset)
	if err != nil {
		return nil, fmt.Errorf("invalid UDP payload sequence: %w", err)
	}
	payloadLen, err := readFrameUvarint(frame, &offset)
	if err != nil {
		return nil, fmt.Errorf("invalid UDP payload length: %w", err)
	}
	if payloadLen > uint64(len(frame)-offset) {
		return nil, fmt.Errorf("invalid UDP payload length %d in frame length %d", payloadLen, len(frame))
	}
	payloadOffset := offset
	offset += int(payloadLen)
	out := udpFrame{
		addr:       &net.UDPAddr{IP: ip, Port: port},
		payload:    append([]byte(nil), frame[payloadOffset:offset]...),
		frameLen:   len(frame),
		batchCount: 1,
		batchMode:  "single",
		seq:        payloadSeq,
		prefix:     prefix,
		trailing:   len(frame) - offset,
	}
	if addrSeq != payloadSeq {
		out.batchMode = "single-mixed-seq"
	}
	return []udpFrame{out}, nil
}

func readUDPResponseFrames(frame []byte, prefix string) ([]udpFrame, error) {
	offset := 1
	batchCount64, err := readFrameUvarint(frame, &offset)
	if err != nil {
		return nil, fmt.Errorf("invalid UDP response batch count: %w", err)
	}
	if batchCount64 < 1 {
		return nil, fmt.Errorf("invalid UDP response batch count %d", batchCount64)
	}
	if batchCount64 > 4096 {
		return nil, fmt.Errorf("unreasonable UDP response batch count %d", batchCount64)
	}
	batchCount := int(batchCount64)
	frames := make([]udpFrame, 0, batchCount)
	for i := 0; i < batchCount; i++ {
		seq, err := readFrameUvarint(frame, &offset)
		if err != nil {
			return nil, fmt.Errorf("invalid UDP response sequence at batch item %d/%d in frame length %d: %w", i+1, batchCount, len(frame), err)
		}
		payloadLen, err := readFrameUvarint(frame, &offset)
		if err != nil {
			return nil, fmt.Errorf("invalid UDP response payload length at batch item %d/%d in frame length %d: %w", i+1, batchCount, len(frame), err)
		}
		if payloadLen < 1 || payloadLen > uint64(len(frame)-offset) {
			return nil, fmt.Errorf("invalid UDP response payload length %d at batch item %d/%d in frame length %d", payloadLen, i+1, batchCount, len(frame))
		}
		payloadOffset := offset
		offset += int(payloadLen)
		item := udpFrame{
			payload:    append([]byte(nil), frame[payloadOffset:offset]...),
			frameLen:   len(frame),
			batchIndex: i,
			batchCount: batchCount,
			batchMode:  "uvarint",
			seq:        seq,
			prefix:     prefix,
		}
		if i == batchCount-1 {
			item.trailing = len(frame) - offset
		}
		frames = append(frames, item)
	}
	if offset != len(frame) {
		return nil, fmt.Errorf("UDP response batch left %d trailing bytes after %d items in frame length %d", len(frame)-offset, batchCount, len(frame))
	}
	return frames, nil
}

func readFrameUvarint(frame []byte, offset *int) (uint64, error) {
	if *offset >= len(frame) {
		return 0, io.ErrUnexpectedEOF
	}
	value, n := binary.Uvarint(frame[*offset:])
	if n == 0 {
		return 0, io.ErrUnexpectedEOF
	}
	if n < 0 {
		return 0, fmt.Errorf("varint overflow")
	}
	*offset += n
	return value, nil
}

func framePrefix(frame []byte) string {
	n := len(frame)
	if n > 24 {
		n = 24
	}
	return fmt.Sprintf("% x", frame[:n])
}

func pbkdf2SHA256(password, salt []byte, iter, keyLen int) []byte {
	hLen := sha256.Size
	numBlocks := (keyLen + hLen - 1) / hLen
	out := make([]byte, 0, numBlocks*hLen)

	for block := 1; block <= numBlocks; block++ {
		mac := hmac.New(sha256.New, password)
		_, _ = mac.Write(salt)
		var intBuf [4]byte
		binary.BigEndian.PutUint32(intBuf[:], uint32(block))
		_, _ = mac.Write(intBuf[:])
		u := mac.Sum(nil)
		t := append([]byte(nil), u...)

		for i := 1; i < iter; i++ {
			mac = hmac.New(sha256.New, password)
			_, _ = mac.Write(u)
			u = mac.Sum(nil)
			for j := range t {
				t[j] ^= u[j]
			}
		}
		out = append(out, t...)
	}
	return out[:keyLen]
}
