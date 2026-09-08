#!/usr/bin/env python3
"""Exercise production routing/DNS JSON in a real pinned sing-box, over loopback.

Only the test transports are substituted: TUN -> SOCKS with its original tag;
direct/proxy -> distinct local SOCKS observers; upstream DNS -> local UDP DNS.
Route and DNS rules, order, final policy and SRS payloads are not reimplemented.
No probe contacts the named websites or a real account. See the validation doc.
"""

import argparse
import contextlib
import copy
import hashlib
import ipaddress
import json
import pathlib
import socket
import socketserver
import ssl
import struct
import subprocess
import sys
import threading
import time
import zlib


TIMEOUT = 5
CORE_VERSION = "1.14.0"
CORE_COMMIT = "0b8995879f29a9b98ee027bc17b75e101445b238"


def receive(sock, length):
    data = b""
    while len(data) < length:
        block = sock.recv(length - len(data))
        if not block:
            raise EOFError("Connection closed before the expected reply")
        data += block
    return data


def address(host, port):
    try:
        parsed = ipaddress.ip_address(host)
        return bytes([1 if parsed.version == 4 else 4]) + parsed.packed + struct.pack("!H", port)
    except ValueError:
        encoded = host.encode("idna")
        return b"\x03" + bytes([len(encoded)]) + encoded + struct.pack("!H", port)


def read_address(sock):
    kind = receive(sock, 1)[0]
    length = {1: 4, 4: 16}.get(kind)
    if kind == 3:
        length = receive(sock, 1)[0]
    if length is None:
        raise ValueError(f"Unsupported SOCKS address kind: {kind}")
    raw = receive(sock, length)
    host = raw.decode("ascii") if kind == 3 else str(ipaddress.ip_address(raw))
    return host, struct.unpack("!H", receive(sock, 2))[0]


def split_udp(packet):
    if packet[:3] != b"\x00\x00\x00":
        raise ValueError("Fragmented or invalid SOCKS UDP reply")
    kind = packet[3]
    end = 4 + {1: 4, 4: 16}.get(kind, 0)
    if kind == 3:
        end = 5 + packet[4]
    if kind not in (1, 3, 4) or len(packet) < end + 2:
        raise ValueError("Truncated SOCKS UDP address")
    return packet[3:end + 2], packet[end + 2:]


def connect_socks(port):
    sock = socket.create_connection(("127.0.0.1", port), TIMEOUT)
    sock.settimeout(TIMEOUT)
    sock.sendall(b"\x05\x01\x00")
    if receive(sock, 2) != b"\x05\x00":
        sock.close()
        raise ValueError("SOCKS no-auth handshake failed")
    return sock


def check_socks_reply(sock):
    reply = receive(sock, 3)
    if reply != b"\x05\x00\x00":
        raise ValueError(f"SOCKS request failed: {reply.hex()}")
    return read_address(sock)


def tcp_exchange(port, host, target_port, payload, dns=False):
    with connect_socks(port) as sock:
        # Complete CONNECT before sending application bytes. The pinned server's
        # buffered handshake reader does not preserve a pipelined payload. Its
        # LazyConn.Read sends success before sniffing, so this cannot deadlock sniff.
        sock.sendall(b"\x05\x01\x00" + address(host, target_port))
        check_socks_reply(sock)
        sock.sendall(payload)
        if dns:
            return receive(sock, struct.unpack("!H", receive(sock, 2))[0])
        return receive(sock, 7)  # Both observer labels have six bytes plus newline.


def udp_exchange(port, host, target_port, payload):
    with connect_socks(port) as control:
        control.sendall(b"\x05\x03\x00" + address("0.0.0.0", 0))
        relay_host, relay_port = check_socks_reply(control)
        if relay_host in ("0.0.0.0", "::"):
            relay_host = "127.0.0.1"
        # The test must never accept a relay outside loopback.
        if not ipaddress.ip_address(relay_host).is_loopback:
            raise ValueError(f"Non-loopback SOCKS relay: {relay_host}")
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
            udp.bind(("127.0.0.1", 0))
            udp.settimeout(TIMEOUT)
            udp.sendto(b"\x00\x00\x00" + address(host, target_port) + payload, (relay_host, relay_port))
            return split_udp(udp.recvfrom(65535)[0])[1]


class ThreadedTCP(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True


class ThreadedUDP(socketserver.ThreadingUDPServer):
    daemon_threads = True
    allow_reuse_address = True


class ObserverTCP(socketserver.BaseRequestHandler):
    def handle(self):
        sock = self.request
        sock.settimeout(TIMEOUT)
        try:
            version, method_count = receive(sock, 2)
            if version != 5 or 0 not in receive(sock, method_count):
                return
            sock.sendall(b"\x05\x00")
            version, command, reserved = receive(sock, 3)
            host, port = read_address(sock)
            if version != 5 or reserved != 0 or command not in (1, 3):
                return
            sock.sendall(b"\x05\x00\x00" + address("127.0.0.1", self.server.udp_port))
            if command == 1:
                sock.recv(65535)
                sock.sendall(self.server.marker)
            else:
                while sock.recv(1024):
                    pass
        except (OSError, EOFError, ValueError):
            # Client shutdown after a completed observation is expected.
            pass


class ObserverUDP(socketserver.BaseRequestHandler):
    def handle(self):
        packet, sock = self.request
        try:
            encoded_address, _ = split_udp(packet)
            sock.sendto(b"\x00\x00\x00" + encoded_address + self.server.marker, self.client_address)
        except (OSError, ValueError, IndexError):
            pass


def dns_question(packet):
    if len(packet) < 17 or struct.unpack("!H", packet[4:6])[0] != 1:
        raise ValueError("Expected one DNS question")
    offset = 12
    labels = []
    while packet[offset]:
        length = packet[offset]
        if length > 63:
            raise ValueError("Compressed DNS questions are not used by the fixture")
        offset += 1
        labels.append(packet[offset:offset + length].decode("ascii"))
        offset += length
    offset += 1
    qtype, qclass = struct.unpack("!HH", packet[offset:offset + 4])
    return ".".join(labels), qtype, qclass, offset + 4


class FixtureDNS(socketserver.BaseRequestHandler):
    def handle(self):
        packet, sock = self.request
        try:
            domain, qtype, qclass, end = dns_question(packet)
            with self.server.query_lock:
                self.server.queries.append((domain, qtype))
            if qtype == 1:
                # A public DNS answer in China does not imply a matched domain rule.
                data = socket.inet_aton("223.5.5.5" if domain == "rr-unlisted-cn.invalid" else self.server.answer)
            elif qtype == 28:
                data = ipaddress.ip_address("2001:db8::1234").packed
            else:
                data = None
            count = int(data is not None and qclass == 1)
            reply = packet[:2] + struct.pack("!HHHHH", 0x8180, 1, count, 0, 0) + packet[12:end]
            if count:
                reply += b"\xc0\x0c" + struct.pack("!HHIH", qtype, 1, 30, len(data)) + data
            sock.sendto(reply, self.client_address)
        except (ValueError, IndexError, OSError, struct.error):
            pass


def query(domain):
    qname = b"".join(bytes([len(label)]) + label.encode("ascii") for label in domain.split(".")) + b"\x00"
    return struct.pack("!HHHHHH", 0x5242, 0x0100, 1, 0, 0, 0) + qname + struct.pack("!HH", 1, 1)


def parse_dns_answer(packet):
    if packet[:2] != b"RB" or len(packet) < 12:
        raise ValueError("Incorrect DNS transaction")
    flags, count = struct.unpack("!H", packet[2:4])[0], struct.unpack("!H", packet[6:8])[0]
    if flags & 15 or count != 1:
        raise ValueError(f"Expected successful single-answer DNS response: flags={flags}, answers={count}")
    _, _, _, end = dns_question(packet)
    if packet[end:end + 2] != b"\xc0\x0c":
        # sing-box can repack without compression; find the answer name end.
        while packet[end]:
            end += packet[end] + 1
        end += 1
    else:
        end += 2
    kind, qclass, ttl, length = struct.unpack("!HHIH", packet[end:end + 10])
    if (kind, qclass, length) != (1, 1, 4):
        raise ValueError("Expected IPv4 DNS answer")
    return socket.inet_ntoa(packet[end + 10:end + 14])


@contextlib.contextmanager
def environment():
    servers = []
    observers, dns_servers = {}, {}
    try:
        for name in ("direct", "proxy"):
            udp = ThreadedUDP(("127.0.0.1", 0), ObserverUDP)
            tcp = ThreadedTCP(("127.0.0.1", 0), ObserverTCP)
            # Fixed-width labels simplify packet verification.
            udp.marker = tcp.marker = (name.ljust(6) + "\n").encode("ascii")
            tcp.udp_port = udp.server_address[1]
            servers.extend([udp, tcp])
            observers[name] = tcp.server_address[1]
        for name, answer in (("dns-direct", "223.5.5.5"), ("dns-remote", "1.1.1.1")):
            server = ThreadedUDP(("127.0.0.1", 0), FixtureDNS)
            server.answer, server.queries, server.query_lock = answer, [], threading.Lock()
            servers.append(server)
            dns_servers[name] = server
        for server in servers:
            threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
        yield observers, dns_servers
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()


def available_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def instrument(original, port, observers, dns_servers):
    config = copy.deepcopy(original)
    inbound_tag = config["inbounds"][0]["tag"]
    config["inbounds"] = [{"type": "socks", "tag": inbound_tag, "listen": "127.0.0.1", "listen_port": port}]
    if {item["tag"] for item in config["outbounds"]} != {"direct", "proxy"}:
        raise ValueError("Fixture instrumenter must be updated for changed production outbounds")
    config["outbounds"] = [
        {"type": "socks", "tag": name, "server": "127.0.0.1", "server_port": target, "version": "5"}
        for name, target in observers.items()
    ]
    if {item["tag"] for item in config["dns"]["servers"]} != set(dns_servers):
        raise ValueError("Fixture instrumenter must be updated for changed production DNS servers")
    config["dns"]["servers"] = [
        {"type": "udp", "tag": name, "server": "127.0.0.1", "server_port": server.server_address[1]}
        for name, server in dns_servers.items()
    ]
    config["log"] = {"level": "debug", "timestamp": False}
    config["route"]["auto_detect_interface"] = False
    # Ensure no routing decision, DNS selection rule, or SRS content was rewritten.
    for section in ("dns", "route"):
        for field in ("rules", "final", "rule_set"):
            if config[section].get(field) != original[section].get(field):
                raise AssertionError(f"Instrumentation changed {section}.{field}")
    return config


@contextlib.contextmanager
def running_core(binary, config, directory, name):
    path = directory / f"{name}.json"
    path.write_text(json.dumps(config, indent=2))
    subprocess.run([binary, "check", "-c", str(path)], check=True, capture_output=True, text=True, timeout=30)
    with (directory / f"{name}.log").open("w") as log:
        process = subprocess.Popen([binary, "run", "-c", str(path)], stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f"Native core exited early: {process.returncode}")
                try:
                    with connect_socks(config["inbounds"][0]["listen_port"]):
                        break
                except OSError:
                    time.sleep(0.025)
            else:
                raise TimeoutError("Native core did not open its loopback listener")
            yield
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save_report(path, report):
    report["check_count"] = sum(len(item["checks"]) for item in report["variants"])
    report["failure_count"] = sum(not check["passed"] for variant in report["variants"] for check in variant["checks"])
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    temporary.replace(path)


def expected_outbound(meta, group):
    if not meta["smart"] or group.get("bundled_only") and meta["rules"] != "bundled":
        return "proxy"
    return group["outbound"]


def verify_srs_decode(binary, paths, directory, report):
    """Same minimal configuration as SrsRuleSetValidation.validationConfig."""
    def config(files):
        return {"log": {"disabled": True}, "outbounds": [{"type": "direct", "tag": "validation-direct"}],
                "route": {"final": "validation-direct", "rule_set": [
                    {"type": "local", "tag": f"validation-{i}", "format": "binary", "path": str(path)}
                    for i, path in enumerate(files)]}}
    valid = directory / "srs-valid-check.json"
    valid.write_text(json.dumps(config(paths)))
    subprocess.run([binary, "check", "-c", str(valid)], check=True, capture_output=True, text=True, timeout=30)
    bad = directory / "invalid-body.srs"
    bad.write_bytes(b"SRS\x05" + zlib.compress(b"\x01\x7f"))
    invalid = directory / "srs-invalid-check.json"
    invalid.write_text(json.dumps(config([bad])))
    result = subprocess.run([binary, "check", "-c", str(invalid)], capture_output=True, text=True, timeout=30)
    report["srs_full_decode"] = {"valid_bundled_accepted": True, "valid_header_invalid_body_rejected": result.returncode != 0,
                                 "invalid_body_stderr": result.stderr.strip()}
    if result.returncode == 0:
        raise AssertionError("Native core accepted an SRS with a valid header/zlib stream but invalid rule body")


def tls_hello(host):
    incoming, outgoing = ssl.MemoryBIO(), ssl.MemoryBIO()
    connection = ssl.create_default_context().wrap_bio(incoming, outgoing, server_hostname=host)
    try:
        connection.do_handshake()
    except ssl.SSLWantReadError:
        return outgoing.read()
    raise AssertionError("Unexpected completed in-memory TLS handshake")


def run_variant(binary, original, meta, cases, directory, report, observers, dns_servers, report_path):
    port = available_port()
    config = instrument(original, port, observers, dns_servers)
    variant = {"name": meta["file"], "engine": meta["engine"], "rules": meta["rules"], "checks": [], "passed": False}
    report["variants"].append(variant)
    consecutive_transport_errors = 0
    transport_errors_by_kind = {}

    def verify(kind, host, expected, callback, group):
        nonlocal consecutive_transport_errors
        entry = {"kind": kind, "host": host, "group": group, "expected": expected}
        variant["checks"].append(entry)
        try:
            entry["actual"] = callback()
            entry["passed"] = entry["actual"] == expected
            consecutive_transport_errors = 0
            transport_errors_by_kind[kind] = 0
        except Exception as error:
            entry["passed"], entry["error"] = False, str(error)
            if isinstance(error, (OSError, EOFError)):
                consecutive_transport_errors += 1
                transport_errors_by_kind[kind] = transport_errors_by_kind.get(kind, 0) + 1
            else:
                consecutive_transport_errors = 0
                transport_errors_by_kind[kind] = 0
            save_report(report_path, report)
            print(json.dumps({"variant": variant["name"], **entry}, ensure_ascii=False), flush=True)
            # TCP and UDP probes alternate: successful UDP must not mask a broken
            # TCP harness (or the reverse) and cause hundreds of five-second waits.
            if consecutive_transport_errors >= 3 or transport_errors_by_kind[kind] >= 3:
                variant["aborted"] = True
                variant["error"] = f"Three consecutive transport errors overall or for {kind}; gate aborted without skipping failures"
                save_report(report_path, report)
                raise RuntimeError(f"{variant['name']}: {variant['error']}") from error

    with running_core(binary, config, directory, pathlib.Path(meta["file"]).stem):
        for group in cases["groups"]:
            for domain in group["domains"]:
                expected = expected_outbound(meta, group)
                payload = f"GET /rr-routing-test HTTP/1.1\r\nHost: {domain}\r\nConnection: close\r\n\r\n".encode()
                verify("tcp-domain", domain, expected,
                       lambda d=domain, p=payload: tcp_exchange(port, d, 80, p).decode().strip(), group["name"])
                verify("udp-domain-443", domain, expected,
                       lambda d=domain: udp_exchange(port, d, 443, b"RRBOX UDP probe").decode().strip(), group["name"])
        # Real IP-form destinations have no domain; observer outbounds never dial
        # them, so no packet goes to these public/documentation/private addresses.
        for host, china in (("223.5.5.5", True), ("1.1.1.1", False), ("240e::1", True), ("2001:4860:4860::8888", False), ("192.168.31.1", None)):
            expected = "direct" if meta["smart"] and (china is None or china and meta["rules"] == "bundled") else "proxy"
            for transport in ("tcp", "udp"):
                method = tcp_exchange if transport == "tcp" else udp_exchange
                verify(f"{transport}-literal-ip", host, expected,
                       lambda h=host, m=method: m(port, h, 3478, b"\x00\x01\x00\x00\x21\x12\xa4\x42" + b"R" * 12).decode().strip(), "literal-ip-and-stun-port")
        # A foreign hostname must win over a CN IP, including third-party TikTok
        # that has no standard package identity. Also exercise native TLS sniffing.
        for host in ("api.tiktokv.com", "v16.tiktokcdn.com", "tos-useast.ibytedtos.com"):
            for kind, payload in (("http", f"GET / HTTP/1.1\r\nHost: {host}\r\n\r\n".encode()), ("tls-client-hello", tls_hello(host))):
                verify(f"{kind}-foreign-host-cn-ip", host, "proxy",
                       lambda p=payload: tcp_exchange(port, "223.5.5.5", 443, p).decode().strip(), "tiktok-priority-over-china-ip")
        # Narrow observed-host exceptions work even when bundled SRS files are absent.
        # Observers terminate these probes locally; none contacts the named addresses.
        for host in cases["observed_mainland_ipv4_exceptions"]:
            expected = "direct" if meta["smart"] else "proxy"
            for transport in ("tcp", "udp"):
                method = tcp_exchange if transport == "tcp" else udp_exchange
                verify(f"{transport}-observed-ip-exception", host, expected,
                       lambda h=host, m=method: m(port, h, 443, b"RRBOX endpoint probe").decode().strip(),
                       "observed-mainland-host-only")
            # A known overseas hostname at the same literal IP must still win.
            for name in ("api.tiktokv.com", "chatgpt.com"):
                for kind, payload in (("http", f"GET / HTTP/1.1\r\nHost: {name}\r\n\r\n".encode()),
                                      ("tls-client-hello", tls_hello(name))):
                    verify(f"{kind}-foreign-host-observed-ip", f"{name} [{host}]", "proxy",
                           lambda h=host, p=payload: tcp_exchange(port, h, 443, p).decode().strip(),
                           "international-priority-over-observed-ip")
        # A maintained future China SRS may legitimately include these neighbors, so
        # evaluate the hard-coded exception boundary in fallback/off configurations.
        if meta["rules"] != "bundled":
            for host in cases["observed_mainland_ipv4_boundaries"]:
                for transport in ("tcp", "udp"):
                    method = tcp_exchange if transport == "tcp" else udp_exchange
                    verify(f"{transport}-observed-ip-boundary", host, "proxy",
                           lambda h=host, m=method: m(port, h, 443, b"RRBOX boundary probe").decode().strip(),
                           "unobserved-host-stays-default")
        # Query through the same native inbound and production hijack-DNS action.
        # DNS observations happen after route cases, avoiding reverse-cache effects
        # from intentionally reused fixture DNS answers on the route-only probes.
        for group in cases["groups"]:
            for domain in group["domains"]:
                expected = "223.5.5.5" if expected_outbound(meta, group) == "direct" else "1.1.1.1"
                verify("dns-udp", domain, expected,
                       lambda d=domain: parse_dns_answer(udp_exchange(port, "198.51.100.53", 53, query(d))), group["name"])
        for domain, expected in (("short.weixin.qq.com", "223.5.5.5" if meta["smart"] else "1.1.1.1"), ("api.tiktokv.com", "1.1.1.1"), ("rr-bootstrap.invalid", "223.5.5.5")):
            packet = query(domain)
            verify("dns-tcp", domain, expected,
                   lambda p=packet: parse_dns_answer(tcp_exchange(port, "198.51.100.53", 53, struct.pack("!H", len(p)) + p, dns=True)), "dns-tcp-and-bootstrap")
        # Explicitly record the current HEV mapping boundary. A domain-form
        # request is not automatically resolved just because there is an IP rule.
        # The IP-form control is what a TUN caller can provide after normal DNS.
        unknown = "rr-unlisted-cn.invalid"
        def unknown_dns():
            for server in dns_servers.values():
                with server.query_lock:
                    server.queries[:] = [item for item in server.queries if item[0] != unknown]
            answer = parse_dns_answer(udp_exchange(port, "198.51.100.53", 53, query(unknown)))
            if answer != "223.5.5.5":
                raise AssertionError("Unknown-domain fixture must resolve to a CN IP")
            used = []
            for name, server in dns_servers.items():
                with server.query_lock:
                    if any(item[0] == unknown for item in server.queries):
                        used.append(name)
            return ",".join(sorted(used))
        verify("unknown-domain-cn-answer-dns", unknown, "dns-remote", unknown_dns, "documented-no-auto-resolve-boundary")
        verify("unknown-domain-with-cn-dns-cache", unknown, "proxy",
               lambda: tcp_exchange(port, unknown, 80, b"GET / HTTP/1.1\r\nHost: rr-unlisted-cn.invalid\r\n\r\n").decode().strip(), "documented-no-auto-resolve-boundary")
        expected_ip = "direct" if meta["smart"] and meta["rules"] == "bundled" else "proxy"
        verify("same-answer-ip-form-control", "223.5.5.5", expected_ip,
               lambda: udp_exchange(port, "223.5.5.5", 443, b"RRBOX opaque IP probe").decode().strip(), "documented-no-auto-resolve-boundary")
    variant["passed"] = all(item["passed"] for item in variant["checks"])
    save_report(report_path, report)
    print(f"{variant['name']}: {sum(item['passed'] for item in variant['checks'])}/{len(variant['checks'])} passed", flush=True)


def self_test():
    """Verify mock transports only; deliberately does not claim a core pass."""
    with environment() as (observers, dns_servers):
        for name, port in observers.items():
            assert tcp_exchange(port, "mock.invalid", 80, b"hello").decode().strip() == name
            assert udp_exchange(port, "2001:db8::1", 443, b"hello").decode().strip() == name
        for server in dns_servers.values():
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                sock.settimeout(TIMEOUT)
                sock.sendto(query("fixture.invalid"), server.server_address)
                assert parse_dns_answer(sock.recv(2048)) == server.answer
    assert tls_hello("api.tiktokv.com")[:1] == b"\x16"
    print("Harness protocol self-test passed; native-core and Android tests were not run.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sing-box", type=pathlib.Path)
    parser.add_argument("--fixtures", type=pathlib.Path, default=pathlib.Path("app/build/routing-fixtures"))
    parser.add_argument("--cases", type=pathlib.Path, default=pathlib.Path(__file__).with_name("verify-routing-cases.json"))
    parser.add_argument("--report", type=pathlib.Path, default=pathlib.Path("build-reports/ROUTING-CORE-REPORT.json"))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.sing_box is None:
        parser.error("--sing-box is required unless --self-test is selected")
    binary = str(args.sing_box.resolve())
    report = {"schema": 1, "passed": False, "core_version": CORE_VERSION, "expected_core_commit": CORE_COMMIT,
              "scope": "Native route/DNS policy with loopback observers; excludes Android TUN, HEV native engine, UID, VPN lifecycle, real DoT/QUIC and app account workflows",
              "variants": [], "rule_sets": []}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    # Preserve the exact exercised configs and core logs alongside the summary.
    directory = args.report.parent / "routing-core-details"
    directory.mkdir(parents=True, exist_ok=True)
    try:
        version = subprocess.run([binary, "version"], check=True, capture_output=True, text=True, timeout=15).stdout
        if f"sing-box version {CORE_VERSION}\n" not in version:
            raise ValueError(f"Expected sing-box {CORE_VERSION}, got {version.strip()}")
        if f"Revision: {CORE_COMMIT}\n" not in version:
            raise ValueError("Native binary VCS revision does not match the pinned libbox commit")
        report["binary_sha256"], report["version_output"] = sha256(args.sing_box), version.strip()
        manifest = json.loads((args.fixtures / "manifest.json").read_text())
        cases = json.loads(args.cases.read_text())
        if manifest.get("schema") != 1 or len(manifest.get("variants", [])) != 6:
            raise ValueError("Expected all six production fixture variants")
        report["case_sources"] = cases["sources"]
        report["cases_sha256"] = sha256(args.cases)
        with environment() as (observers, dns_servers):
            for meta in manifest["variants"]:
                source = args.fixtures / meta["file"]
                original = json.loads(source.read_text())
                # Check the untouched output too: transport substitution must not
                # hide invalid production direct/TLS resolver fields or TUN schema.
                subprocess.run([binary, "check", "-c", str(source.resolve())], check=True, capture_output=True, text=True, timeout=30)
                if meta["rules"] == "bundled":
                    for rule in original["route"]["rule_set"]:
                        path = pathlib.Path(rule["path"])
                        if not path.is_file():
                            raise FileNotFoundError(f"Bundled rule missing: {path}; run ci-fetch-rules.sh before this gate")
                        report["rule_sets"].append({"variant": meta["file"], "tag": rule["tag"], "sha256": sha256(path), "bytes": path.stat().st_size})
                    if "srs_full_decode" not in report:
                        verify_srs_decode(binary, [pathlib.Path(item["path"]) for item in original["route"]["rule_set"]], directory, report)
                run_variant(binary, original, meta, cases, directory, report, observers, dns_servers, args.report)
        report["passed"] = bool(report["variants"]) and all(item["passed"] for item in report["variants"])
    except Exception as error:
        report["error"] = str(error)
        if isinstance(error, subprocess.CalledProcessError):
            report["stderr"] = error.stderr
    finally:
        save_report(args.report, report)
    if not report["passed"]:
        for variant in report["variants"]:
            for check in variant["checks"]:
                if not check["passed"]:
                    print(json.dumps({"variant": variant["name"], **check}, ensure_ascii=False), file=sys.stderr)
        if report.get("error"):
            print(report["error"], file=sys.stderr)
        if report.get("stderr"):
            print(report["stderr"], file=sys.stderr)
        return 1
    print(f"Native routing gate passed: {report['check_count']} checks. Android device acceptance remains separate.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
