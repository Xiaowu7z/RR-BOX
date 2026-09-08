#!/usr/bin/env python3
"""Verify X stale-address recovery in the pinned core, using production fixtures.

The shared harness replaces transports only. SOCKS observers return the actual
destination they received and never dial it. DNS answers are served on loopback.
QUIC inputs are authenticated v1 Initial packets, not opaque UDP stand-ins.
This validates native route/DNS/NAT semantics, not Android lifecycle or X login.
"""

import argparse
import hashlib
import importlib.util
import ipaddress
import json
import pathlib
import socket
import subprocess
import sys


SPEC = importlib.util.spec_from_file_location("routing_harness", pathlib.Path(__file__).with_name("verify-routing.py"))
H = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(H)
OLD_IP = "174.132.167.252"
RECOVERED_IP = "1.1.1.1"
HOST = "api.twitter.com"


def tcp_observe(port, host, target_port, payload):
    with H.connect_socks(port) as sock:
        sock.sendall(b"\x05\x01\x00" + H.address(host, target_port))
        H.check_socks_reply(sock)
        sock.sendall(payload)
        data = bytearray()
        while len(data) < 4096:
            data.extend(H.receive(sock, 1))
            if data[-1:] == b"\n":
                return json.loads(data)
        raise ValueError("Observer reply exceeds fixture bound")


def udp_observe(port, host, target_port, payload):
    with H.connect_socks(port) as control:
        control.sendall(b"\x05\x03\x00" + H.address("0.0.0.0", 0))
        relay_host, relay_port = H.check_socks_reply(control)
        if relay_host in ("0.0.0.0", "::"):
            relay_host = "127.0.0.1"
        if not ipaddress.ip_address(relay_host).is_loopback:
            raise ValueError("Non-loopback relay rejected")
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
            udp.bind(("127.0.0.1", 0))
            udp.settimeout(H.TIMEOUT)
            udp.sendto(b"\x00\x00\x00" + H.address(host, target_port) + payload, (relay_host, relay_port))
            encoded, response = H.split_udp(udp.recvfrom(65535)[0])
            result = json.loads(response)
            result["reply_host"], result["reply_port"] = H.decode_address(encoded)
            return result


def quic_initial(generator, domain):
    # MemoryBIO does not open a socket. Constrain TLS to 1.3 as required by QUIC.
    hello = H.tls_hello(domain, tls13=True)
    result = subprocess.run([generator], input=hello, capture_output=True, check=True, timeout=15)
    if len(result.stdout) != 1200:
        raise ValueError("Expected a minimum-size QUIC Initial")
    return result.stdout


def dns_counts(servers, domain):
    result = {}
    for name, server in servers.items():
        with server.query_lock:
            result[name] = sum(item[0] == domain for item in server.queries)
    return result


def run_variant(binary, source, meta, generator, directory, report, report_path):
    variant = {"name": meta["file"], "engine": meta["engine"], "rules": meta["rules"], "checks": [], "passed": False}
    report["variants"].append(variant)

    def verify(name, expected, callback):
        check = {"kind": name, "expected": expected}
        variant["checks"].append(check)
        try:
            check["actual"] = callback()
            check["passed"] = check["actual"] == expected
        except Exception as error:
            check["passed"], check["error"] = False, str(error)
        H.save_report(report_path, report)
        if not check["passed"]:
            print(json.dumps({"variant": meta["file"], **check}), flush=True)

    with H.environment(detailed=True) as (observers, dns_servers):
        port = H.available_port()
        config = H.instrument(json.loads(source.read_text()), port, observers, dns_servers)
        tls = H.tls_hello(HOST)
        quic = quic_initial(generator, HOST)
        expected_host = RECOVERED_IP if meta["smart"] else OLD_IP

        def tcp_case(original, payload, target_port=443):
            result = tcp_observe(port, original, target_port, payload)
            return {key: result[key] for key in ("outbound", "host", "port")}

        def udp_case(original, payload, target_port=443):
            result = udp_observe(port, original, target_port, payload)
            result["payload_unchanged"] = result.pop("payload_sha256") == hashlib.sha256(payload).hexdigest()
            return result

        def tcp_expected(target, target_port=443, outbound="proxy"):
            return {"outbound": outbound, "host": target, "port": target_port}

        def udp_expected(original, target, target_port=443, outbound="proxy"):
            return {**tcp_expected(target, target_port, outbound), "reply_host": original,
                    "reply_port": target_port, "payload_unchanged": True}

        with H.running_core(binary, config, directory, pathlib.Path(meta["file"]).stem):
            verify("tls-stale-public-ip-to-trusted-dns", tcp_expected(expected_host),
                   lambda: tcp_case(OLD_IP, tls))
            first_counts = dns_counts(dns_servers, HOST)
            verify("resolve-uses-remote-only", True, lambda: (
                first_counts["dns-direct"] == 0 and
                (first_counts["dns-remote"] > 0 if meta["smart"] else first_counts["dns-remote"] == 0)))
            verify("http-stale-ip-preserves-nonstandard-port", tcp_expected(expected_host, 8443),
                   lambda: tcp_case(OLD_IP, f"GET / HTTP/1.1\r\nHost: {HOST}\r\n\r\n".encode(), 8443))
            # Changed original public IP must reuse the trusted hostname cache, not
            # the old address; this catches an implementation that only fixes one IP.
            other_ip = "69.63.180.173"
            verify("tls-second-stale-ip-reuses-remote-answer", tcp_expected(RECOVERED_IP if meta["smart"] else other_ip),
                   lambda: tcp_case(other_ip, tls))
            verify("remote-dns-cache-reused", first_counts, lambda: dns_counts(dns_servers, HOST))
            verify("quic-stale-ip-recovered-with-original-reply-address", udp_expected(OLD_IP, expected_host),
                   lambda: udp_case(OLD_IP, quic))
            old_v6 = "2606:4700:4700::1111"
            recovered_v6 = RECOVERED_IP if meta["smart"] else old_v6
            verify("tls-public-ipv6-can-recover-to-ipv4", tcp_expected(recovered_v6),
                   lambda: tcp_case(old_v6, tls))
            verify("quic-ipv6-original-reply-address-survives-ipv4-recovery", udp_expected(old_v6, recovered_v6),
                   lambda: udp_case(old_v6, quic))
            # HEV can already supply a domain. Same production rule resolves it;
            # the observer must see an IP instead of relying on remote SOCKS DNS.
            domain_target = RECOVERED_IP if meta["smart"] else HOST
            verify("tls-domain-form-known-x-host", tcp_expected(domain_target),
                   lambda: tcp_case(HOST, tls))
            verify("quic-domain-form-preserves-domain-reply-address", udp_expected(HOST, domain_target),
                   lambda: udp_case(HOST, quic))
            # An available domain by itself must not rewrite opaque TCP or UDP.
            for transport, callback, expectation in (("tcp", tcp_case, tcp_expected),
                                                      ("udp", udp_case, lambda target: udp_expected(HOST, target))):
                verify(f"{transport}-opaque-domain-not-recovered", expectation(HOST),
                       lambda call=callback: call(HOST, b"RRBOX opaque transport probe"))
            for transport, callback, expectation in (("tcp", tcp_case, tcp_expected),
                                                      ("udp", udp_case, lambda target: udp_expected(OLD_IP, target))):
                verify(f"{transport}-opaque-old-ip-not-recovered", expectation(OLD_IP),
                       lambda call=callback: call(OLD_IP, b"RRBOX opaque transport probe"))
            # Preserve existing domain routing but never rewrite private/synthetic
            # destinations, even if TLS advertises an allow-listed X hostname.
            for original in ("192.168.31.10", "100.64.1.21", "198.18.0.1", "192.0.2.10",
                             "127.0.0.2", "169.254.1.2", "2001:db8::10", "fd00::10", "64:ff9b::808:808"):
                verify("tls-excluded-destination-" + original, tcp_expected(original),
                       lambda old=original: tcp_case(old, tls))
            # Neither a domestic hostname nor a lookalike gets address recovery.
            for domain in ("gateway.kugou.com", "api.twitter.com.evil.invalid", "unlisted.twitter.com", "cloudflare-ech.com"):
                expected_outbound = "direct" if meta["smart"] and domain == "gateway.kugou.com" else "proxy"
                verify("tls-unreviewed-host-" + domain, tcp_expected(OLD_IP, outbound=expected_outbound),
                       lambda name=domain: tcp_case(OLD_IP, H.tls_hello(name)))
            verify("tls-without-sni-does-not-rewrite-old-ip", tcp_expected(OLD_IP),
                   lambda: tcp_case(OLD_IP, H.tls_hello(None)))
            # QUIC parser must also recognize a domestic SNI without rewriting it.
            domestic = quic_initial(generator, "gateway.kugou.com")
            outbound = "direct" if meta["smart"] else "proxy"
            verify("quic-domestic-stays-original", udp_expected(OLD_IP, OLD_IP, outbound=outbound),
                   lambda: udp_case(OLD_IP, domestic))
            verify("negative-cases-did-not-add-x-dns-queries", first_counts, lambda: dns_counts(dns_servers, HOST))
        variant["passed"] = all(check["passed"] for check in variant["checks"])
        H.save_report(report_path, report)
        print(f"{meta['file']}: {sum(check['passed'] for check in variant['checks'])}/{len(variant['checks'])} destination checks passed", flush=True)


def self_test():
    with H.environment(detailed=True) as (observers, _):
        payload = b"destination-observer-self-test"
        for name, port in observers.items():
            tcp = tcp_observe(port, OLD_IP, 8443, payload)
            assert (tcp["outbound"], tcp["host"], tcp["port"]) == (name, OLD_IP, 8443)
            udp = udp_observe(port, "2606:4700::1111", 443, payload)
            assert udp["host"] == udp["reply_host"] == "2606:4700::1111"
            assert udp["payload_sha256"] == hashlib.sha256(payload).hexdigest()
    print("Destination observer self-test passed; native core and QUIC generator not exercised.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sing-box", type=pathlib.Path)
    parser.add_argument("--quic-generator", type=pathlib.Path)
    parser.add_argument("--fixtures", type=pathlib.Path, default=pathlib.Path("app/build/routing-fixtures"))
    parser.add_argument("--report", type=pathlib.Path, default=pathlib.Path("build-reports/DESTINATION-RECOVERY-REPORT.json"))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.sing_box is None or args.quic_generator is None:
        parser.error("--sing-box and --quic-generator are required")
    report = {"schema": 1, "passed": False, "core_version": H.CORE_VERSION,
              "scope": "Native production route/DNS and UDP reply address; loopback-only transports; excludes Android lifecycle and real app business success",
              "variants": []}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    directory = args.report.parent / "destination-recovery-details"
    directory.mkdir(exist_ok=True)
    try:
        binary, generator = str(args.sing_box.resolve()), str(args.quic_generator.resolve())
        version = subprocess.run([binary, "version"], capture_output=True, check=True, text=True, timeout=15).stdout
        if f"sing-box version {H.CORE_VERSION}\n" not in version or f"Revision: {H.CORE_COMMIT}\n" not in version:
            raise ValueError("Core version/revision differs from pinned production core")
        report["binary_sha256"], report["generator_sha256"] = H.sha256(args.sing_box), H.sha256(args.quic_generator)
        manifest = json.loads((args.fixtures / "manifest.json").read_text())
        expected = {(engine, mode) for engine in ("system", "hev", "root") for mode in ("fallback", "bundled", "off")}
        actual = {(item["engine"], item["rules"]) for item in manifest["variants"]}
        if actual != expected or len(manifest["variants"]) != 9:
            raise ValueError("All nine production fixture variants are required")
        for meta in manifest["variants"]:
            run_variant(binary, args.fixtures / meta["file"], meta, generator, directory, report, args.report)
        report["passed"] = all(item["passed"] for item in report["variants"])
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
    print(f"Native destination recovery passed: {report['check_count']} checks; Android device acceptance remains separate.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
