#!/usr/bin/env python3
"""Exercise pinned native HEV with the exported production YAML, without root/TUN.

Build its initialized source checkout with:
  make -C hev-socks5-tunnel -j2 shared CFLAGS=-Wno-error=unused-result
Then run:
  python3 scripts/verify-hev-native-dns.py \
    --hev-library hev-socks5-tunnel/bin/libhev-socks5-tunnel.so

An AF_UNIX datagram socketpair provides the external tun_fd supported by HEV.
Real IPv4 UDP/TCP packets traverse HEV/lwIP and its native SOCKS client. A local
SOCKS observer supplies deterministic DNS answers; verify-hev-dns.py separately
tests production sing-box routing and resolvers. No Internet, root, Python
packages, Android device, or changes to the production handshake profile needed.
"""

import argparse
import ctypes
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time


CLIENT = "198.18.0.1"
RESOLVER = "198.18.0.2"
TIMEOUT = 8
ANSWERS = {1: "203.0.113.17", 28: "2001:db8::17"}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def checksum(data):
    data += b"\0" * (len(data) % 2)
    total = sum(struct.unpack("!%dH" % (len(data) // 2), data))
    while total >> 16:
        total = (total & 0xFFFF) + (total >> 16)
    return (~total) & 0xFFFF


def ipv4_packet(protocol, payload):
    header = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(payload), 1, 0,
                         64, protocol, 0, socket.inet_aton(CLIENT), socket.inet_aton(RESOLVER))
    return header[:10] + struct.pack("!H", checksum(header)) + header[12:] + payload


def transport_checksum(protocol, payload):
    pseudo = socket.inet_aton(CLIENT) + socket.inet_aton(RESOLVER)
    return checksum(pseudo + struct.pack("!BBH", 0, protocol, len(payload)) + payload)


def udp_packet(port, payload):
    segment = struct.pack("!HHHH", port, 53, 8 + len(payload), 0) + payload
    segment = segment[:6] + struct.pack("!H", transport_checksum(17, segment) or 0xFFFF) + segment[8:]
    return ipv4_packet(17, segment)


def tcp_packet(port, sequence, acknowledgement, flags, payload=b""):
    segment = struct.pack("!HHIIBBHHH", port, 53, sequence, acknowledgement,
                          0x50, flags, 65535, 0, 0) + payload
    segment = segment[:16] + struct.pack("!H", transport_checksum(6, segment)) + segment[18:]
    return ipv4_packet(6, segment)


def receive_segment(tun, protocol, port, deadline):
    while time.monotonic() < deadline:
        tun.settimeout(max(0.01, deadline - time.monotonic()))
        packet = tun.recv(65535)
        require(len(packet) >= 20 and packet[0] >> 4 == 4, "HEV returned a non-IPv4 packet")
        header_size = (packet[0] & 15) * 4
        require(checksum(packet[:header_size]) == 0, "Invalid IPv4 reply checksum")
        require(struct.unpack("!H", packet[2:4])[0] == len(packet), "Invalid IPv4 reply length")
        if packet[9] != protocol:
            continue
        require(packet[12:20] == socket.inet_aton(RESOLVER) + socket.inet_aton(CLIENT),
                "HEV changed the DNS reply IP endpoints")
        segment = packet[header_size:]
        source_port, target_port = struct.unpack("!HH", segment[:4])
        if target_port != port:
            continue
        require(source_port == 53, "HEV changed the DNS reply source port")
        pseudo = packet[12:20] + struct.pack("!BBH", 0, protocol, len(segment))
        if protocol == 6 or segment[6:8] != b"\0\0":
            require(checksum(pseudo + segment) == 0, "Invalid transport reply checksum")
        return segment
    raise TimeoutError("No matching DNS response from native HEV")


def dns_query(qtype):
    name = "native-hev-%s.example.test" % ("a" if qtype == 1 else "aaaa")
    qname = b"".join(bytes([len(label)]) + label.encode() for label in name.split(".")) + b"\0"
    query = struct.pack("!HHHHHH", 0x7200 + qtype, 0x0100, 1, 0, 0, 0) + qname + struct.pack("!HH", qtype, 1)
    address = ipaddress.ip_address(ANSWERS[qtype]).packed
    response = query[:2] + struct.pack("!HHHHH", 0x8180, 1, 1, 0, 0) + query[12:]
    response += b"\xc0\x0c" + struct.pack("!HHIH", qtype, 1, 60, len(address)) + address
    return name, query, response


def receive_exact(sock, length):
    data = bytearray()
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        require(bool(chunk), "SOCKS peer closed before completing the message")
        data.extend(chunk)
    return bytes(data)


class SocksObserver:
    """One local SOCKS session; withhold auth reply to verify pipeline mode."""

    def __init__(self, protocol, query, response):
        self.protocol = protocol
        self.query = query
        self.response = response
        self.error = None
        self.observed = None
        self.stop = threading.Event()
        self.done = threading.Event()
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.listener.settimeout(TIMEOUT)
        self.port = self.listener.getsockname()[1]
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()

    def serve(self):
        try:
            with self.listener.accept()[0] as connection:
                connection.settimeout(TIMEOUT)
                require(receive_exact(connection, 3) == b"\x05\x01\x00", "Unexpected SOCKS auth methods")
                # The request must arrive before we acknowledge authentication.
                header = receive_exact(connection, 4)
                require(header[0] == 5 and header[2:] == b"\x00\x01", "Expected an IPv4 SOCKS request")
                target = receive_exact(connection, 6)
                if self.protocol == "udp":
                    require(header[1] == 3, "DNS UDP did not use SOCKS UDP ASSOCIATE")
                    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as relay:
                        relay.bind(("127.0.0.1", 0))
                        relay.settimeout(TIMEOUT)
                        reply = b"\x05\x00\x00\x01" + socket.inet_aton("127.0.0.1")
                        connection.sendall(b"\x05\x00" + reply + struct.pack("!H", relay.getsockname()[1]))
                        datagram, peer = relay.recvfrom(65535)
                        require(datagram[:4] == b"\x00\x00\x00\x01", "Unexpected SOCKS UDP framing")
                        require(datagram[4:10] == socket.inet_aton(RESOLVER) + struct.pack("!H", 53),
                                "SOCKS UDP target is not the production DNS endpoint")
                        require(datagram[10:] == self.query, "HEV modified the original UDP DNS query")
                        self.observed = {"command": "udp-associate", "target": RESOLVER + ":53",
                                         "original_query_preserved": True, "pipeline_observed": True}
                        relay.sendto(datagram[:10] + self.response, peer)
                        self.done.set()
                        self.stop.wait(TIMEOUT)
                else:
                    require(header[1] == 1, "DNS TCP did not use SOCKS CONNECT")
                    require(target == socket.inet_aton(RESOLVER) + struct.pack("!H", 53),
                            "SOCKS TCP target is not the production DNS endpoint")
                    connection.sendall(b"\x05\x00\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00")
                    length = struct.unpack("!H", receive_exact(connection, 2))[0]
                    require(receive_exact(connection, length) == self.query, "HEV modified the original TCP DNS query")
                    self.observed = {"command": "connect", "target": RESOLVER + ":53",
                                     "original_query_preserved": True, "pipeline_observed": True}
                    connection.sendall(struct.pack("!H", len(self.response)) + self.response)
                    self.done.set()
                    self.stop.wait(TIMEOUT)
        except Exception as error:
            self.error = str(error)
            self.done.set()

    def close(self):
        self.stop.set()
        self.listener.close()
        self.thread.join(timeout=TIMEOUT + 1)


def exchange_tcp(tun, port, query):
    deadline = time.monotonic() + TIMEOUT
    sequence = 1000
    tun.send(tcp_packet(port, sequence, 0, 0x02))
    segment = receive_segment(tun, 6, port, deadline)
    peer_seq, acknowledged = struct.unpack("!II", segment[4:12])
    require(segment[13] & 0x12 == 0x12 and acknowledged == sequence + 1, "Invalid TCP SYN-ACK")
    sequence += 1
    peer_seq += 1
    tun.send(tcp_packet(port, sequence, peer_seq, 0x10))
    framed_query = struct.pack("!H", len(query)) + query
    tun.send(tcp_packet(port, sequence, peer_seq, 0x18, framed_query))
    sequence += len(framed_query)
    assembled = bytearray()
    while time.monotonic() < deadline:
        segment = receive_segment(tun, 6, port, deadline)
        require(not segment[13] & 0x04, "HEV reset the DNS TCP stream")
        packet_seq = struct.unpack("!I", segment[4:8])[0]
        payload = segment[(segment[12] >> 4) * 4:]
        if payload:
            require(packet_seq == peer_seq, "Unexpected TCP reply sequence")
            assembled.extend(payload)
            peer_seq += len(payload)
            tun.send(tcp_packet(port, sequence, peer_seq, 0x10))
            if len(assembled) >= 2:
                size = struct.unpack("!H", assembled[:2])[0]
                if len(assembled) >= size + 2:
                    require(len(assembled) == size + 2, "Unexpected trailing TCP DNS bytes")
                    return bytes(assembled[2:])
    raise TimeoutError("No complete TCP DNS answer from native HEV")


def library_environment(library):
    # Upstream `make shared` links these shared libraries without an rpath.
    checkout = library.parent.parent
    directories = [checkout / "third-part" / name / "bin" for name in ("yaml", "lwip", "hev-task-system")]
    environment = os.environ.copy()
    environment["LD_LIBRARY_PATH"] = os.pathsep.join(str(p) for p in directories) + os.pathsep + environment.get("LD_LIBRARY_PATH", "")
    return environment


def verify_case(library, yaml, protocol, qtype):
    name, query, response = dns_query(qtype)
    observer = SocksObserver(protocol, query, response)
    tun, native_tun = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
    process = None
    case = {"transport": protocol, "qtype": "A" if qtype == 1 else "AAAA", "domain": name,
            "answer": ANSWERS[qtype], "passed": False}
    try:
        with tempfile.TemporaryDirectory(prefix="rrbox-hev-native-") as temporary:
            config = Path(temporary) / "hev.yaml"
            instrumented, replacements = re.subn(r"(?m)^  port: \d+$", "  port: %d" % observer.port, yaml)
            require(replacements == 1, "Expected exactly one production SOCKS port")
            config.write_text(instrumented)
            with (Path(temporary) / "native.log").open("w+") as log:
                process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "--worker",
                                            str(library), str(config), str(native_tun.fileno())],
                                           pass_fds=(native_tun.fileno(),), env=library_environment(library),
                                           stdout=log, stderr=subprocess.STDOUT)
                native_tun.close()
                try:
                    if protocol == "udp":
                        tun.send(udp_packet(43001, query))
                        segment = receive_segment(tun, 17, 43001, time.monotonic() + TIMEOUT)
                        require(struct.unpack("!H", segment[4:6])[0] == len(segment), "Invalid UDP DNS length")
                        actual = segment[8:]
                    else:
                        actual = exchange_tcp(tun, 43002, query)
                    require(observer.done.wait(TIMEOUT), "SOCKS observer did not finish")
                    require(observer.error is None, observer.error)
                    require(actual == response, "Native HEV modified the upstream DNS answer")
                    require(ipaddress.ip_address(ANSWERS[qtype]) not in ipaddress.ip_network("100.64.0.0/10"),
                            "Fixture answer unexpectedly uses the removed mapped address pool")
                    case.update(observer.observed)
                    case.update(passed=True, response_preserved=True, synthetic_answer=False,
                                query_sha256=hashlib.sha256(query).hexdigest(),
                                response_sha256=hashlib.sha256(actual).hexdigest())
                finally:
                    if process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(timeout=2)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=2)
                    log.seek(0)
                    case["native_log"] = log.read()[-4000:]
    except Exception as error:
        case["error"] = str(error)
        if observer.error:
            case["observer_error"] = observer.error
    finally:
        tun.close()
        native_tun.close()
        observer.close()
    return case


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hev-library", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, default=Path("app/build/hev-dns-fixtures"))
    parser.add_argument("--report", type=Path, default=Path("build-reports/hev-native-dns-validation.json"))
    args = parser.parse_args()
    report = {"schema": 1, "status": "failed", "scope": "native HEV IPv4 TUN packets through SOCKS",
              "backend": "loopback SOCKS observer with deterministic DNS answers; sing-box tested separately",
              "cases": []}
    try:
        library = args.hev_library.resolve()
        require(library.is_file(), "Build the host HEV shared library before running this gate")
        manifest = json.loads((args.fixtures / "manifest.json").read_text())
        require(manifest["dns_address"] == RESOLVER, "Unexpected production DNS endpoint")
        yaml = (args.fixtures / "hev.yaml").read_text()
        require(not re.search(r"(?m)^mapdns:", yaml), "Production native mapdns must be absent")
        for line in ("  mtu: 8500", "  ipv4: " + CLIENT, "  address: 127.0.0.1", "  udp: 'udp'",
                     "  pipeline: true", "  tcp-fastopen: true", "  task-stack-size: 86016",
                     "  tcp-buffer-size: 131072", "  udp-recv-buffer-size: 1048576", "  udp-copy-buffer-nums: 32"):
            require(line in yaml.splitlines(), "Production HEV profile changed: " + line.strip())
        report.update(library_sha256=hashlib.sha256(library.read_bytes()).hexdigest(),
                      production_yaml_sha256=hashlib.sha256(yaml.encode()).hexdigest(),
                      fixture_producer=manifest["producer"], only_config_override="SOCKS loopback observer port",
                      profile={"mtu": 8500, "pipeline": True, "tcp_fastopen": True, "udp": "udp", "mapdns": False},
                      limitations=["External socketpair TUN descriptor; Android VpnService not exercised",
                                   "TFO remains enabled; kernel TFO negotiation is not asserted",
                                   "Documentation-range DNS answers are supplied by the local SOCKS observer"])
        for protocol in ("udp", "tcp"):
            for qtype in (1, 28):
                case = verify_case(library, yaml, protocol, qtype)
                report["cases"].append(case)
                print("%s %s %s" % ("PASS" if case["passed"] else "FAIL", protocol, case["qtype"]), flush=True)
        report["checks"] = len(report["cases"])
        report["passed"] = sum(case["passed"] for case in report["cases"])
        report["status"] = "passed" if report["passed"] == 4 else "failed"
    except Exception as error:
        report["error"] = str(error)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print("Native HEV DNS: %s; report: %s" % (report["status"], args.report))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--worker":
        # lwIP exports weak fallback hev_malloc/hev_free symbols. Load the
        # strong task allocator first, matching the Android static link; ELF
        # dependency order otherwise mixes its allocations with libc free.
        task_library = Path(sys.argv[2]).parent.parent / "third-part/hev-task-system/bin/libhev-task-system.so"
        allocator = ctypes.CDLL(str(task_library), mode=ctypes.RTLD_GLOBAL)
        native = ctypes.CDLL(sys.argv[2])
        native.hev_socks5_tunnel_main_from_file.argtypes = [ctypes.c_char_p, ctypes.c_int]
        native.hev_socks5_tunnel_main_from_file.restype = ctypes.c_int
        sys.exit(native.hev_socks5_tunnel_main_from_file(os.fsencode(sys.argv[3]), int(sys.argv[4])))
    sys.exit(main())
