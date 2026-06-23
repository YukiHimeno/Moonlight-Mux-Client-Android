package main

import (
	"bytes"
	"encoding/binary"
	"net"
	"testing"
)

func TestReadUDPFramesResponseBatch(t *testing.T) {
	body := []byte{
		0x00, 0x02,
		0x00, 0x03, 'a', 'b', 'c',
		0x01, 0x02, 'd', 'e',
	}
	raw := make([]byte, 0, 4+len(body))
	raw = binary.BigEndian.AppendUint32(raw, uint32(len(body)))
	raw = append(raw, body...)

	frames, err := readUDPFrames(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("readUDPFrames returned error: %v", err)
	}
	if len(frames) != 2 {
		t.Fatalf("got %d frames, want 2", len(frames))
	}
	if got, want := string(frames[0].payload), "abc"; got != want {
		t.Fatalf("frame 0 payload = %q, want %q", got, want)
	}
	if got, want := string(frames[1].payload), "de"; got != want {
		t.Fatalf("frame 1 payload = %q, want %q", got, want)
	}
	if frames[0].batchCount != 2 || frames[0].batchIndex != 0 {
		t.Fatalf("frame 0 batch metadata = %d/%d, want 0/2", frames[0].batchIndex, frames[0].batchCount)
	}
	if frames[1].batchCount != 2 || frames[1].batchIndex != 1 {
		t.Fatalf("frame 1 batch metadata = %d/%d, want 1/2", frames[1].batchIndex, frames[1].batchCount)
	}
	if frames[0].seq != 0 || frames[1].seq != 1 {
		t.Fatalf("frame seqs = %d, %d; want 0, 1", frames[0].seq, frames[1].seq)
	}
}

func TestReadUDPFramesResponseBatchWithMultiByteLength(t *testing.T) {
	payload := bytes.Repeat([]byte{0x90}, 1408)
	body := []byte{0x00, 0x01}
	body = binary.AppendUvarint(body, 0)
	body = binary.AppendUvarint(body, uint64(len(payload)))
	body = append(body, payload...)
	raw := make([]byte, 0, 4+len(body))
	raw = binary.BigEndian.AppendUint32(raw, uint32(len(body)))
	raw = append(raw, body...)

	frames, err := readUDPFrames(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("readUDPFrames returned error: %v", err)
	}
	if len(frames) != 1 {
		t.Fatalf("got %d frames, want 1", len(frames))
	}
	if !bytes.Equal(frames[0].payload, payload) {
		t.Fatalf("payload was not preserved")
	}
	if frames[0].batchMode != "uvarint" {
		t.Fatalf("batch mode = %q, want uvarint", frames[0].batchMode)
	}
}

func TestWriteUDPFrameUsesVarintsAndSequence(t *testing.T) {
	var out bytes.Buffer
	addr := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 47998}
	if err := writeUDPFrame(&out, addr, []byte("small"), 128); err != nil {
		t.Fatalf("writeUDPFrame returned error: %v", err)
	}

	raw := out.Bytes()
	if len(raw) < 4 {
		t.Fatalf("short frame")
	}
	frameLen := int(binary.BigEndian.Uint32(raw[:4]))
	if got := len(raw) - 4; got != frameLen {
		t.Fatalf("frame length = %d, want %d", frameLen, got)
	}
	frame := raw[4:]
	wantPrefix := []byte{
		0x01,
		0x80, 0x01,
		0x06,
		0x7f, 0x00, 0x00, 0x01,
		0xbb, 0x7e,
		0x01,
		0x80, 0x01,
		0x05,
	}
	if !bytes.Equal(frame[:len(wantPrefix)], wantPrefix) {
		t.Fatalf("frame prefix = % x, want % x", frame[:len(wantPrefix)], wantPrefix)
	}
	if got := string(frame[len(wantPrefix):]); got != "small" {
		t.Fatalf("payload = %q, want small", got)
	}
}
