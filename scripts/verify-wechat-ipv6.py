#!/usr/bin/env python3
"""Exercise WeChat IPv6 recovery with the production direct dialer, over loopback.

Production candidate route/DNS rules and the direct outbound are kept intact.
Only the inbound, proxy transport and upstream DNS transports are substituted.
Real TCP listeners prove that an original public IPv6 destination is re-resolved
and reaches IPv4, and that healthy IPv6-only and dual-stack TCP fallback work.
QUIC/opaque UDP must keep their original destination without address recovery.
Separate observer probes test boundaries without contacting public addresses.
This is a native-core gate, not Android UID/TUN/lifecycle or WeChat acceptance.
"""

import argparse
import contextlib
import copy
import errno
import hashlib
import importlib.util
import ipaddress
import json
import pathlib
import socket
import socketserver
import struct
import subprocess
import sys
import threading


SPEC = importlib.util.spec_from_file_location("routing_harness", pathlib.Path(__file__).with_name("verify-routing.py"))
H = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(H)
DEST_SPEC = importlib.util.spec_from_file_location("destination_harness", pathlib.Path(__file__).with_name("verify-destination-recovery.py"))
D = importlib.util.module_from_spec(DEST_SPEC)
DEST_SPEC.loader.exec_module(D)

# A globally routable address is needed to exercise the production public-IPv6
# condition. The mandatory no-route preflight ensures it cannot leave this host.
OLD_IPV6 = "2606:4700:4700::1111"
IPV4 = "127.0.0.2"
UNLISTENED_IPV4 = "127.0.0.3"
HOST = "szextshort.weixin.qq.com"
IPV6_ONLY_HOST = "szshort.weixin.qq.com"
DUAL_STACK_HOST = "wx.qlogo.cn"
DNS_PROFILES = {
    HOST: {1: IPV4, 28: OLD_IPV6},
    IPV6_ONLY_HOST: {28: "::1"},
    DUAL_STACK_HOST: {1: UNLISTENED_IPV4, 28: "::1"},
}


def assert_public_ipv6_has_no_route():
    # UDP connect performs a local route lookup without transmitting a packet.
    # Refuse to run instead of accidentally probing public infrastructure on a
    # runner that has IPv6 egress. The actual core then sees the same no-route
    # failure when testing a broken IPv6 answer or an unrecovered destination.
    with socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as sock:
        try:
            sock.connect((OLD_IPV6, 9))
        except OSError as error:
            if error.errno in (errno.ENETUNREACH, errno.EHOSTUNREACH):
                return {"address": OLD_IPV6, "errno": error.errno, "packet_sent": False}
            raise
    raise RuntimeError("This loopback-only gate requires no route to its public IPv6 fixture; refusing to send probes")


def echo_reply(server, payload):
    return (json.dumps({"outbound": "direct", "host": server.server_address[0],
                        "port": server.server_address[1],
                        "payload_sha256": hashlib.sha256(payload).hexdigest()}) + "\n").encode()


class EchoTCP(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(H.TIMEOUT)
        try:
            payload = read_tcp_payload(self.request)
            self.request.sendall(echo_reply(self.server, payload))
        except (OSError, EOFError, ValueError):
            pass


class EchoUDP(socketserver.BaseRequestHandler):
    def handle(self):
        payload, sock = self.request
        sock.sendto(echo_reply(self.server, payload), self.client_address)


class TCP6(H.ThreadedTCP):
    address_family = socket.AF_INET6


class UDP6(H.ThreadedUDP):
    address_family = socket.AF_INET6


def read_tcp_payload(sock):
    # TCP segmentation must not change the full-payload hash, including in the
    # proxy/boundary observers. The shared route-only observer reads one recv()
    # and deliberately has no framing contract for this stronger assertion.
    payload = H.receive(sock, 5)
    if payload[:1] == b"\x16":
        return payload + H.receive(sock, struct.unpack("!H", payload[3:5])[0])
    terminator = b"\r\n\r\n" if payload.startswith(b"GET ") else b"\n"
    while not payload.endswith(terminator):
        if len(payload) >= 8192:
            raise ValueError("TCP fixture exceeded its bound")
        payload += H.receive(sock, 1)
    return payload


class CompleteObserverTCP(socketserver.BaseRequestHandler):
    def handle(self):
        sock = self.request
        sock.settimeout(H.TIMEOUT)
        try:
            version, method_count = H.receive(sock, 2)
            if version != 5 or 0 not in H.receive(sock, method_count):
                return
            sock.sendall(b"\x05\x00")
            version, command, reserved = H.receive(sock, 3)
            host, port = H.read_address(sock)
            if version != 5 or reserved != 0 or command not in (1, 3):
                return
            sock.sendall(b"\x05\x00\x00" + H.address("127.0.0.1", self.server.udp_port))
            if command == 1:
                sock.sendall(H.observer_reply(self.server, host, port, read_tcp_payload(sock)))
            else:
                while sock.recv(1024):
                    pass
        except (OSError, EOFError, ValueError):
            pass


@contextlib.contextmanager
def observer_environment():
    servers, started = [], []
    observers, dns_servers = {}, {}
    try:
        for name in ("direct", "proxy"):
            udp = H.ThreadedUDP(("127.0.0.1", 0), H.ObserverUDP)
            servers.append(udp)
            tcp = H.ThreadedTCP(("127.0.0.1", 0), CompleteObserverTCP)
            servers.append(tcp)
            udp.marker = tcp.marker = (name.ljust(6) + "\n").encode()
            udp.detailed = tcp.detailed = True
            tcp.udp_port = udp.server_address[1]
            observers[name] = tcp.server_address[1]
        for name, answer in (("dns-direct", "223.5.5.5"), ("dns-remote", "1.1.1.1")):
            server = H.ThreadedUDP(("127.0.0.1", 0), H.FixtureDNS)
            server.answer, server.queries, server.query_lock = answer, [], threading.Lock()
            servers.append(server)
            dns_servers[name] = server
        for server in servers:
            threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
            started.append(server)
        yield observers, dns_servers
    finally:
        for server in servers:
            if server in started:
                server.shutdown()
            server.server_close()


class ProfileDNS(socketserver.BaseRequestHandler):
    def handle(self):
        packet, sock = self.request
        try:
            domain, kind, qclass, end = H.dns_question(packet)
            with self.server.query_lock:
                self.server.queries.append((domain, kind))
            address = DNS_PROFILES.get(domain, {}).get(kind) if qclass == 1 else None
            data = ipaddress.ip_address(address).packed if address else None
            reply = packet[:2] + struct.pack("!HHHHH", 0x8180, 1, int(data is not None), 0, 0) + packet[12:end]
            if data is not None:
                reply += b"\xc0\x0c" + struct.pack("!HHIH", kind, 1, 30, len(data)) + data
            sock.sendto(reply, self.client_address)
        except (ValueError, IndexError, OSError, struct.error):
            pass


@contextlib.contextmanager
def native_environment():
    servers = []
    started = []
    try:
        tcp4 = H.ThreadedTCP((IPV4, 0), EchoTCP)
        servers.append(tcp4)
        port = tcp4.server_address[1]
        for server_type, host, handler in ((H.ThreadedUDP, IPV4, EchoUDP),
                                          (TCP6, "::1", EchoTCP), (UDP6, "::1", EchoUDP)):
            servers.append(server_type((host, port), handler))
        # The dual-stack TCP control must genuinely fail at its A address.
        with socket.socket() as sock:
            sock.settimeout(H.TIMEOUT)
            if sock.connect_ex((UNLISTENED_IPV4, port)) != errno.ECONNREFUSED:
                raise RuntimeError("Dual-stack fixture IPv4 address must reject TCP connections")
        dns_servers = {}
        for name in ("dns-direct", "dns-remote"):
            server = H.ThreadedUDP(("127.0.0.1", 0), ProfileDNS)
            server.queries, server.query_lock = [], threading.Lock()
            dns_servers[name] = server
            servers.append(server)
        for server in servers:
            threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
            started.append(server)
        yield port, dns_servers
    finally:
        for server in servers:
            if server in started:
                server.shutdown()
            server.server_close()


def instrument_native(original, port, proxy_port, dns_servers):
    config = copy.deepcopy(original)
    config["inbounds"] = [{"type": "socks", "tag": original["inbounds"][0]["tag"],
                           "listen": "127.0.0.1", "listen_port": port}]
    if {item["tag"] for item in original["outbounds"]} != {"direct", "proxy"}:
        raise ValueError("Production outbound topology changed")
    original_direct = next(item for item in original["outbounds"] if item["tag"] == "direct")
    if original_direct["type"] != "direct":
        raise ValueError("Native fallback test requires a production type=direct outbound")
    config["outbounds"] = [copy.deepcopy(original_direct),
                           {"type": "socks", "tag": "proxy", "server": "127.0.0.1",
                            "server_port": proxy_port, "version": "5"}]
    if {item["tag"] for item in config["dns"]["servers"]} != set(dns_servers):
        raise ValueError("Production DNS topology changed")
    config["dns"]["servers"] = [{"type": "udp", "tag": name, "server": "127.0.0.1",
                                   "server_port": server.server_address[1]}
                                  for name, server in dns_servers.items()]
    config["route"]["auto_detect_interface"] = False
    config["log"] = {"level": "debug", "timestamp": False}
    for section in ("route", "dns"):
        for field in ("rules", "final", "rule_set"):
            if config[section].get(field) != original[section].get(field):
                raise AssertionError(f"Instrumentation changed production {section}.{field}")
    if next(item for item in config["outbounds"] if item["tag"] == "direct") != original_direct:
        raise AssertionError("Instrumentation changed the production direct dialer")
    return config


def checked_observation(port, original, target_port, payload, udp=False):
    result = (D.udp_observe if udp else D.tcp_observe)(port, original, target_port, payload)
    result["payload_unchanged"] = result.pop("payload_sha256") == hashlib.sha256(payload).hexdigest()
    return result


def expectation(host, port, original=None, outbound="direct"):
    result = {"outbound": outbound, "host": host, "port": port, "payload_unchanged": True}
    if original is not None:
        result.update(reply_host=original, reply_port=port)
    return result


def dns_uses(dns_servers):
    result = {}
    for name, server in dns_servers.items():
        with server.query_lock:
            result[name] = sorted(set(server.queries))
    return result


def prime_reverse_mapping(port):
    packet = H.query(HOST)[:-4] + struct.pack("!HH", 28, 1)
    answer = H.udp_exchange(port, "198.51.100.53", 53, packet)
    domain, kind, qclass, _ = H.dns_question(answer)
    return (answer[:2] == b"RB" and struct.unpack("!H", answer[6:8])[0] == 1 and
            (domain, kind, qclass) == (HOST, 28, 1) and
            answer.endswith(ipaddress.ip_address(OLD_IPV6).packed))


def native_remains_unreachable(port, target_port, udp=False, payload=None):
    """A wrongly recovered flow would reach the echo and make this test fail."""
    if payload is None:
        payload = b"RRBOX native opaque probe\n"
    with H.connect_socks(port) as control:
        if not udp:
            control.sendall(b"\x05\x01\x00" + H.address(OLD_IPV6, target_port))
            H.check_socks_reply(control)
            control.sendall(payload)
            try:
                return control.recv(1) == b""
            except (ConnectionResetError, BrokenPipeError):
                return True
        control.sendall(b"\x05\x03\x00" + H.address("0.0.0.0", 0))
        relay_host, relay_port = H.check_socks_reply(control)
        if relay_host in ("0.0.0.0", "::"):
            relay_host = "127.0.0.1"
        if not ipaddress.ip_address(relay_host).is_loopback:
            raise ValueError("Non-loopback relay rejected")
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.bind(("127.0.0.1", 0))
            sock.settimeout(1.5)
            sock.sendto(b"\x00\x00\x00" + H.address(OLD_IPV6, target_port) + payload, (relay_host, relay_port))
            try:
                sock.recv(8192)
                return False
            except socket.timeout:
                return True


def run_variant(binary, generator, original, meta, directory, report, report_path):
    variant = {"name": meta["file"], "engine": meta["engine"], "rules": meta["rules"],
               "checks": [], "passed": False, "direct_transport": "production type=direct"}
    report["variants"].append(variant)

    def verify(name, expected, callback):
        check = {"kind": name, "expected": expected, "passed": False}
        variant["checks"].append(check)
        try:
            check["actual"] = callback()
            check["passed"] = check["actual"] == expected
        except Exception as error:
            check["error"] = str(error)
        H.save_report(report_path, report)
        if not check["passed"]:
            print(json.dumps({"variant": meta["file"], **check}), flush=True)

    smart = meta["smart"]
    direct = next(item for item in original["outbounds"] if item["tag"] == "direct")
    verify("production-direct-prefers-ipv4-without-disabling-ipv6",
           {"server": "dns-direct", "strategy": "prefer_ipv4"}, lambda: direct.get("domain_resolver"))
    stem = pathlib.Path(meta["file"]).stem
    with observer_environment() as (observers, _), native_environment() as (target_port, dns_servers):
        port = H.available_port()
        config = instrument_native(original, port, observers["proxy"], dns_servers)
        with H.running_core(binary, config, directory, stem + "-native-direct"):
            for host, healthy in ((HOST, IPV4), (IPV6_ONLY_HOST, "::1"), (DUAL_STACK_HOST, "::1")):
                expected_host = healthy if smart else OLD_IPV6
                outbound = "direct" if smart else "proxy"
                # Both protocol sniffers must trigger the exact same real dialer;
                # dynamically selected target ports also catch hard-coded :443.
                for protocol, payload in (("tls", H.tls_hello(host)),
                                          ("http", f"GET /native-direct HTTP/1.1\r\nHost: {host}\r\n\r\n".encode())):
                    verify(f"{protocol}-native-{host}", expectation(expected_host, target_port, outbound=outbound),
                           lambda p=payload: checked_observation(port, OLD_IPV6, target_port, p))
            quic = D.quic_initial(generator, HOST)
            if smart:
                # UDP recovery is deliberately excluded. If QUIC were rewritten,
                # it would reach the IPv4 echo and this no-reply check would fail.
                # The observer phase additionally proves the exact old target.
                verify("quic-native-original-ipv6-remains-unreachable", True,
                       lambda: native_remains_unreachable(port, target_port, udp=True, payload=quic))
            else:
                verify("quic-off-proxy-retains-original-ipv6-reply",
                       expectation(OLD_IPV6, target_port, OLD_IPV6, "proxy"),
                       lambda: checked_observation(port, OLD_IPV6, target_port, quic, udp=True))
            if smart:
                # Exercise actual DNS reverse mapping, not a fabricated domain
                # metadata field. Linux SOCKS has no Android package identity.
                verify("prime-production-reverse-map-with-aaaa", True, lambda: prime_reverse_mapping(port))
                for udp in (False, True):
                    verify("reverse-mapped-opaque-" + ("udp" if udp else "tcp-without-package") + "-stays-unreachable",
                           True, lambda u=udp: native_remains_unreachable(port, target_port, udp=u))
            uses = dns_uses(dns_servers)
            variant["native_dns_queries"] = uses
            verify("native-resolves-through-direct-dns-only", True,
                   lambda: not uses["dns-remote"] and (bool(uses["dns-direct"]) if smart else not uses["dns-direct"]))
            if smart:
                verify("native-ipv4-answer-was-requested", True, lambda: (HOST, 1) in uses["dns-direct"])
                verify("native-ipv6-only-answer-was-requested", True,
                       lambda: (IPV6_ONLY_HOST, 28) in uses["dns-direct"])
                verify("native-dual-stack-fallback-has-both-answer-families", True,
                       lambda: all((DUAL_STACK_HOST, qtype) in uses["dns-direct"] for qtype in (1, 28)))

    # Boundary observations replace both outbound transports and only assert
    # the route/override decision. All successful fallback claims above use
    # real direct sockets and are independent of these SOCKS observers.
    with observer_environment() as (observers, dns_servers):
        port = H.available_port()
        config = H.instrument(original, port, observers, dns_servers)
        with H.running_core(binary, config, directory, stem + "-boundaries"):
            quic = D.quic_initial(generator, HOST)
            verify("quic-known-wechat-host-retains-original-ipv6-and-reply",
                   expectation(OLD_IPV6, 8443, OLD_IPV6, "direct" if smart else "proxy"),
                   lambda: checked_observation(port, OLD_IPV6, 8443, quic, udp=True))
            for address in ("223.5.5.5", "127.0.0.2", "::1", "fd00::2", "2001:db8::2", "64:ff9b::808:808"):
                payload = H.tls_hello(HOST)
                verify("tls-excluded-destination-" + address,
                       expectation(address, 8443, outbound="direct" if smart else "proxy"),
                       lambda a=address, p=payload: checked_observation(port, a, 8443, p))
            for host, domestic in ((HOST + ".evil.invalid", False), ("unreviewed.weixin.qq.com", True),
                                   ("gateway.kugou.com", True), ("cloudflare-ech.com", False)):
                outbound = "direct" if smart and domestic else "proxy"
                payload = H.tls_hello(host)
                verify("tls-unreviewed-host-" + host, expectation(OLD_IPV6, 8443, outbound=outbound),
                       lambda p=payload: checked_observation(port, OLD_IPV6, 8443, p))
            for udp in (False, True):
                payload = b"RRBOX opaque transport probe\n"
                verify(("udp" if udp else "tcp") + "-opaque-bare-ip-remains-unrecovered",
                       expectation(OLD_IPV6, 8443, OLD_IPV6 if udp else None, "proxy"),
                       lambda p=payload, u=udp: checked_observation(port, OLD_IPV6, 8443, p, udp=u))
            verify("tls-without-sni-remains-unrecovered", expectation(OLD_IPV6, 8443, outbound="proxy"),
                   lambda: checked_observation(port, OLD_IPV6, 8443, H.tls_hello(None)))
            # A mapped domain without recognizable traffic is not evidence of an
            # Android WeChat package; Linux SOCKS provides no package identity.
            for udp in (False, True):
                verify(("udp" if udp else "tcp") + "-opaque-domain-without-package",
                       expectation(HOST, 8443, HOST if udp else None, "direct" if smart else "proxy"),
                       lambda u=udp: checked_observation(port, HOST, 8443, b"RRBOX opaque transport probe\n", udp=u))
            verify("boundary-cases-trigger-no-dns-recovery", {"dns-direct": [], "dns-remote": []},
                   lambda: dns_uses(dns_servers))
        variant["passed"] = all(check["passed"] for check in variant["checks"])
        H.save_report(report_path, report)
        print(f"{meta['file']}: {sum(check['passed'] for check in variant['checks'])}/{len(variant['checks'])} WeChat IPv6 checks passed", flush=True)


def self_test():
    payload = b"RRBOX native direct self-test\n"
    with native_environment() as (port, dns_servers):
        for family, host in ((socket.AF_INET, IPV4), (socket.AF_INET6, "::1")):
            with socket.socket(family, socket.SOCK_STREAM) as sock:
                sock.settimeout(H.TIMEOUT)
                sock.connect((host, port))
                sock.sendall(payload)
                result = json.loads(sock.makefile("rb").readline())
                assert result == {"outbound": "direct", "host": host, "port": port,
                                  "payload_sha256": hashlib.sha256(payload).hexdigest()}
            with socket.socket(family, socket.SOCK_DGRAM) as sock:
                sock.settimeout(H.TIMEOUT)
                sock.sendto(payload, (host, port))
                assert json.loads(sock.recv(8192)) == result
        for server in dns_servers.values():
            for domain, answers in DNS_PROFILES.items():
                for kind in (1, 28):
                    packet = H.query(domain)[:-4] + struct.pack("!HH", kind, 1)
                    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                        sock.settimeout(H.TIMEOUT)
                        sock.sendto(packet, server.server_address)
                        response = sock.recv(8192)
                    assert struct.unpack("!H", response[6:8])[0] == int(kind in answers)
                    if kind in answers:
                        assert response.endswith(ipaddress.ip_address(answers[kind]).packed)
    with observer_environment() as (observers, _):
        http = f"GET /segmented HTTP/1.1\r\nHost: {HOST}\r\n\r\n".encode()
        for name, port in observers.items():
            for probe in (http, H.tls_hello(HOST), payload):
                assert checked_observation(port, OLD_IPV6, 8443, probe) == expectation(OLD_IPV6, 8443, outbound=name)
            assert checked_observation(port, OLD_IPV6, 8443, payload, udp=True) == expectation(OLD_IPV6, 8443, OLD_IPV6, name)
            with H.connect_socks(port) as sock:
                sock.sendall(b"\x05\x01\x00" + H.address(OLD_IPV6, 8443))
                H.check_socks_reply(sock)
                sock.sendall(http[:5])
                sock.settimeout(0.05)
                try:
                    sock.recv(1)
                    raise AssertionError("Observer replied to a partial TCP payload")
                except socket.timeout:
                    pass
                sock.settimeout(H.TIMEOUT)
                sock.sendall(http[5:])
                result = json.loads(sock.makefile("rb").readline())
                assert result["payload_sha256"] == hashlib.sha256(http).hexdigest()
    print("WeChat native TCP/UDP and dual-family DNS harness self-test passed; core and Android were not exercised.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sing-box", type=pathlib.Path)
    parser.add_argument("--quic-generator", type=pathlib.Path)
    parser.add_argument("--fixtures", type=pathlib.Path, default=pathlib.Path("app/build/routing-fixtures"))
    parser.add_argument("--report", type=pathlib.Path, default=pathlib.Path("build-reports/WECHAT-IPV6-REPORT.json"))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.sing_box is None or args.quic_generator is None:
        parser.error("--sing-box and --quic-generator are required")
    report = {"schema": 1, "passed": False, "core_version": H.CORE_VERSION,
              "expected_core_commit": H.CORE_COMMIT,
              "scope": "Production candidate recovery rules with native direct TCP dialer, DNS and unchanged UDP destinations, using loopback endpoints; activation is separately gated by Android Root/physical IPv4 availability and missing IPv6 route; excludes Android UID/package ownership, actual TUN/HEV/Root lifecycle, Wi-Fi switching and real WeChat success; no UDP address recovery or dead-A UDP failover",
              "variants": []}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    directory = args.report.parent / "wechat-ipv6-details"
    directory.mkdir(exist_ok=True)
    try:
        report["no_public_ipv6_route"] = assert_public_ipv6_has_no_route()
        binary, generator = str(args.sing_box.resolve()), str(args.quic_generator.resolve())
        version = subprocess.run([binary, "version"], capture_output=True, check=True, text=True, timeout=15).stdout
        if f"sing-box version {H.CORE_VERSION}\n" not in version or f"Revision: {H.CORE_COMMIT}\n" not in version:
            raise ValueError("Core version/revision differs from pinned production core")
        report["binary_sha256"], report["generator_sha256"] = H.sha256(args.sing_box), H.sha256(args.quic_generator)
        manifest = json.loads((args.fixtures / "manifest.json").read_text())
        expected = {(engine, mode) for engine in ("system", "hev", "root") for mode in ("fallback", "bundled", "off")}
        actual = {(item["engine"], item["rules"]) for item in manifest["variants"]}
        if manifest.get("schema") != 1 or actual != expected or len(manifest["variants"]) != 9:
            raise ValueError("All nine production fixture variants are required")
        for meta in manifest["variants"]:
            source = args.fixtures / meta["file"]
            run_variant(binary, generator, json.loads(source.read_text()), meta, directory, report, args.report)
        report["passed"] = all(variant["passed"] for variant in report["variants"])
    except Exception as error:
        report["error"] = str(error)
        if isinstance(error, subprocess.CalledProcessError):
            stderr = error.stderr
            report["stderr"] = stderr.decode(errors="replace") if isinstance(stderr, bytes) else stderr
    finally:
        H.save_report(args.report, report)
    if not report["passed"]:
        print(json.dumps({key: report[key] for key in ("error", "stderr") if key in report}), file=sys.stderr)
        return 1
    print(f"Native WeChat IPv6 gate passed: {report['check_count']} checks; Android device acceptance remains separate.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
