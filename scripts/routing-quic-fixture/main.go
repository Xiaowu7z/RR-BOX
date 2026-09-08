// routing-quic-fixture wraps a supplied TLS ClientHello in a QUIC v1 Initial.
// Only standard-library crypto is used. It emits a deterministic, authenticated
// local test packet; it never opens a network connection. RFC 9001 sections 5/A.
package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"os"
)

func expand(secret []byte, label string, length int) []byte {
	label = "tls13 " + label
	info := []byte{byte(length >> 8), byte(length), byte(len(label))}
	info = append(info, label...)
	info = append(info, 0) // Empty TLS HKDF context.
	var result, previous []byte
	for counter := byte(1); len(result) < length; counter++ {
		mac := hmac.New(sha256.New, secret)
		mac.Write(previous)
		mac.Write(info)
		mac.Write([]byte{counter})
		previous = mac.Sum(nil)
		result = append(result, previous...)
	}
	return result[:length]
}

func varint(value int) []byte {
	if value < 64 {
		return []byte{byte(value)}
	}
	if value >= 16384 {
		panic("fixture exceeds two-byte QUIC variable integer")
	}
	return []byte{byte(value>>8) | 0x40, byte(value)}
}

func initial(hello []byte) ([]byte, error) {
	if len(hello) < 9 || hello[0] != 22 || hello[5] != 1 ||
		int(binary.BigEndian.Uint16(hello[3:5])) != len(hello)-5 {
		return nil, fmt.Errorf("expected one complete TLS ClientHello record")
	}
	// QUIC carries the TLS handshake bytes in a CRYPTO frame, without TLS records.
	frame := []byte{6, 0} // CRYPTO, offset zero.
	frame = append(frame, varint(len(hello)-5)...)
	frame = append(frame, hello[5:]...)
	if len(frame) > 1162 {
		return nil, fmt.Errorf("ClientHello does not fit the fixed local Initial")
	}
	frame = append(frame, make([]byte, 1162-len(frame))...) // Authenticated PADDING.
	dcid := []byte{0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08}
	salt, _ := hex.DecodeString("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")
	extract := hmac.New(sha256.New, salt)
	extract.Write(dcid)
	secret := expand(extract.Sum(nil), "client in", 32)
	key := expand(secret, "quic key", 16)
	iv := expand(secret, "quic iv", 12)
	hp := expand(secret, "quic hp", 16)
	// Long-header Initial, four-byte packet number, v1, no SCID or token.
	header := []byte{0xc3, 0, 0, 0, 1, byte(len(dcid))}
	header = append(header, dcid...)
	header = append(header, 0, 0)
	header = append(header, varint(4+len(frame)+16)...)
	pnOffset := len(header)
	header = append(header, 0, 0, 0, 1)
	iv[len(iv)-1] ^= 1 // IV XOR packet number.
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	packet := append(append([]byte{}, header...), aead.Seal(nil, iv, frame, header)...)
	protector, err := aes.NewCipher(hp)
	if err != nil {
		return nil, err
	}
	mask := make([]byte, 16)
	protector.Encrypt(mask, packet[pnOffset+4:pnOffset+4+16])
	packet[0] ^= mask[0] & 0x0f
	for index := 0; index < 4; index++ {
		packet[pnOffset+index] ^= mask[index+1]
	}
	if len(packet) != 1200 {
		return nil, fmt.Errorf("unexpected packet size %d", len(packet))
	}
	return packet, nil
}

func main() {
	input, err := io.ReadAll(io.LimitReader(os.Stdin, 4097))
	if err == nil {
		input, err = initial(input)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if _, err = os.Stdout.Write(input); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
