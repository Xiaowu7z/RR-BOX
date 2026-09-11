#!/usr/bin/env python3
"""Verify production app-node rules and concurrent outlets in the pinned core.

The Go probe invokes native rule matching with supplied ProcessInfo. Socket
probes keep production route/DNS rules and exercise authenticated HEV inlets,
TCP/UDP outlet isolation, resolver detours, missing/offline secondary nodes,
and the stable/Root unknown-owner guard. Only transport endpoints and DNS TLS
encryption are replaced with local observers. No probe reaches a public host.
Android owner lookup, VPN lifecycle and real Telegram service acceptance are
explicitly outside this Linux gate; HEV native owner forwarding has its own gate.
"""

import argparse
import base64
import concurrent.futures
import contextlib
import copy
import hashlib
import importlib.util
import ipaddress
import json
import os
import pathlib
import shutil
import socket
import socketserver
import struct
import subprocess
import sys
import threading
import time


SPEC = importlib.util.spec_from_file_location("routing_harness", pathlib.Path(__file__).with_name("verify-routing.py"))
H = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(H)
DESTINATION = "1.1.1.2"
DNS_DESTINATION = "198.18.0.2"
ANSWERS = {"proxy": "203.0.113.11", "secondary": "203.0.113.22", "direct": "203.0.113.33"}


def dns_reply(packet, answer):
    _, qtype, qclass, end = H.dns_question(packet)
    count = int(qtype == 1 and qclass == 1)
    result = packet[:2] + struct.pack("!HHHHH", 0x8180, 1, count, 0, 0) + packet[12:end]
    if count:
        result += b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 60, 4) + socket.inet_aton(answer)
    return result


def observe(server, host, port, payload):
    with server.state["lock"]:
        server.state["events"].append({"outbound": server.label, "host": host, "port": port,
                                       "sha256": hashlib.sha256(payload).hexdigest()})
    if server.state["offline"]:
        return None
    if port == 53:
        return dns_reply(payload, ANSWERS[server.label])
    return (json.dumps({"outbound": server.label, "host": host, "port": port,
                        "sha256": hashlib.sha256(payload).hexdigest()}) + "\n").encode()


class OutletTCP(socketserver.BaseRequestHandler):
    def handle(self):
        sock = self.request
        sock.settimeout(H.TIMEOUT)
        try:
            version, count = H.receive(sock, 2)
            if version != 5 or 0 not in H.receive(sock, count):
                return
            sock.sendall(b"\x05\x00")
            version, command, reserved = H.receive(sock, 3)
            host, port = H.read_address(sock)
            if (version, reserved) != (5, 0) or command not in (1, 3):
                return
            sock.sendall(b"\x05\x00\x00" + H.address("127.0.0.1", self.server.udp_port))
            if command == 3:
                while sock.recv(1024):
                    pass
                return
            payload = sock.recv(65535)
            if not payload:
                return
            response = observe(self.server, host, port, payload)
            if response is not None:
                sock.sendall(response)
        except (OSError, EOFError, ValueError, struct.error):
            pass


class OutletUDP(socketserver.BaseRequestHandler):
    def handle(self):
        packet, sock = self.request
        try:
            encoded, payload = H.split_udp(packet)
            host, port = H.decode_address(encoded)
            response = observe(self.server, host, port, payload)
            if response is not None:
                sock.sendto(b"\x00\x00\x00" + encoded + response, self.client_address)
        except (OSError, ValueError, IndexError, struct.error):
            pass


class DirectDNS(socketserver.BaseRequestHandler):
    def handle(self):
        packet, sock = self.request
        try:
            with self.server.state["lock"]:
                self.server.state["queries"].append(H.dns_question(packet)[0])
            sock.sendto(dns_reply(packet, ANSWERS["direct"]), self.client_address)
        except (OSError, ValueError, IndexError, struct.error):
            pass


@contextlib.contextmanager
def environment():
    servers, outlets, states = [], {}, {}
    try:
        for label in ("proxy", "secondary", "direct"):
            state = {"lock": threading.Lock(), "events": [], "offline": False}
            udp = H.ThreadedUDP(("127.0.0.1", 0), OutletUDP)
            tcp = H.ThreadedTCP(("127.0.0.1", 0), OutletTCP)
            udp.label = tcp.label = label
            udp.state = tcp.state = state
            tcp.udp_port = udp.server_address[1]
            servers.extend([udp, tcp])
            outlets[label], states[label] = tcp.server_address[1], state
        dns = H.ThreadedUDP(("127.0.0.1", 0), DirectDNS)
        dns.state = {"lock": threading.Lock(), "queries": []}
        servers.append(dns)
        for server in servers:
            threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
        yield outlets, states, dns
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()


def connect(inbound, timeout=H.TIMEOUT, credentials=True):
    sock = socket.create_connection(("127.0.0.1", inbound["listen_port"]), timeout)
    sock.settimeout(timeout)
    try:
        users = inbound.get("users", []) if credentials else []
        method = 2 if users else 0
        sock.sendall(bytes([5, 1, method]))
        if H.receive(sock, 2) != bytes([5, method]):
            raise ValueError("SOCKS authentication method rejected")
        if users:
            username, password = users[0]["username"].encode(), users[0]["password"].encode()
            sock.sendall(bytes([1, len(username)]) + username + bytes([len(password)]) + password)
            if H.receive(sock, 2) != b"\x01\x00":
                raise ValueError("SOCKS credentials rejected")
        return sock
    except Exception:
        sock.close()
        raise


def exchange(inbound, network, payload, host=DESTINATION, target_port=443, timeout=H.TIMEOUT):
    with connect(inbound, timeout) as control:
        if network == "tcp":
            control.sendall(b"\x05\x01\x00" + H.address(host, target_port))
            H.check_socks_reply(control)
            control.sendall(payload)
            if target_port == 53:
                return H.receive(control, struct.unpack("!H", H.receive(control, 2))[0])
            result = bytearray()
            while len(result) < 4096:
                result.extend(H.receive(control, 1))
                if result[-1:] == b"\n":
                    return json.loads(result)
            raise ValueError("Observer response too long")
        control.sendall(b"\x05\x03\x00" + H.address("0.0.0.0", 0))
        relay, port = H.check_socks_reply(control)
        relay = "127.0.0.1" if relay in ("0.0.0.0", "::") else relay
        if not ipaddress.ip_address(relay).is_loopback:
            raise ValueError("Non-loopback relay rejected")
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
            udp.bind(("127.0.0.1", 0))
            udp.settimeout(timeout)
            udp.sendto(b"\x00\x00\x00" + H.address(host, target_port) + payload, (relay, port))
            encoded, response = H.split_udp(udp.recvfrom(65535)[0])
            if target_port == 53:
                return response
            result = json.loads(response)
            result["reply_host"], result["reply_port"] = H.decode_address(encoded)
            return result


def dns_exchange(inbound, domain, network="udp", timeout=H.TIMEOUT):
    payload = H.query(domain)
    if network == "tcp":
        payload = struct.pack("!H", len(payload)) + payload
    return exchange(inbound, network, payload, DNS_DESTINATION, 53, timeout)


def blocked(callback):
    try:
        callback()
        return False
    except (OSError, EOFError):
        return True
    except ValueError as error:
        if "SOCKS request failed:" in str(error):
            return True
        raise


def event_count(state, dns=False):
    with state["lock"]:
        return sum((event["port"] == 53) == dns for event in state["events"])


def instrument(original, outlets, dns, extra):
    config = copy.deepcopy(original)
    ports = set()
    for inbound in config["inbounds"]:
        # Native Android TUN cannot run in this host process. Preserve its tag;
        # no owner is invented, so stable/Root socket checks must reject business.
        if inbound["type"] == "tun":
            inbound.clear()
            inbound.update(type="socks", tag=original["inbounds"][0]["tag"])
        port = H.available_port()
        while port in ports:
            port = H.available_port()
        ports.add(port)
        inbound.update(listen="127.0.0.1", listen_port=port)
    expected_tags = {"proxy", "direct"} | ({extra} if any(x["tag"] == extra for x in original["outbounds"]) else set())
    if {x["tag"] for x in original["outbounds"]} != expected_tags:
        raise ValueError("Unexpected production outlet topology")
    for outbound in config["outbounds"]:
        label = "secondary" if outbound["tag"] == extra else outbound["tag"]
        tag, resolver = outbound["tag"], outbound.get("domain_resolver")
        outbound.clear()
        outbound.update(type="socks", tag=tag, server="127.0.0.1", server_port=outlets[label], version="5")
        if resolver is not None:
            outbound["domain_resolver"] = resolver
    for resolver in config["dns"]["servers"]:
        tag, detour = resolver["tag"], resolver.get("detour")
        resolver.clear()
        resolver.update(type="udp", tag=tag)
        if detour is None:
            if tag != "dns-direct":
                raise ValueError("Only the direct bootstrap resolver can lack a detour")
            resolver.update(server="127.0.0.1", server_port=dns.server_address[1])
        else:
            resolver.update(server="1.1.1.1", server_port=53, detour=detour)
    config["log"] = {"level": "debug", "timestamp": False}
    config["route"]["auto_detect_interface"] = False
    for section in ("dns", "route"):
        for field in ("rules", "final", "rule_set"):
            if config[section].get(field) != original[section].get(field):
                raise AssertionError(f"Instrumentation changed {section}.{field}")
    return config


@contextlib.contextmanager
def running_core(binary, config, directory, name):
    path = directory / (name + ".json")
    path.write_text(json.dumps(config, indent=2))
    subprocess.run([binary, "check", "-c", str(path)], capture_output=True, text=True, check=True, timeout=30)
    with (directory / (name + ".log")).open("w") as log:
        process = subprocess.Popen([binary, "run", "-c", str(path)], stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 10
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f"Core exited before readiness: {process.returncode}")
                try:
                    with connect(config["inbounds"][0]):
                        break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Core did not open its inlet")
                    time.sleep(0.025)
            yield
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


def native_variant(binary, original, meta, extra, details, report, report_path):
    variant = {"name": meta["file"], "engine": meta["engine"], "kind": "native-sockets", "checks": []}
    report["variants"].append(variant)

    def verify(name, expected, callback):
        entry = {"kind": name, "expected": expected}
        variant["checks"].append(entry)
        try:
            entry["actual"] = callback()
            entry["passed"] = entry["actual"] == expected
        except Exception as error:
            entry.update(passed=False, error=str(error))
        H.save_report(report_path, report)
        if not entry["passed"]:
            print(json.dumps({"variant": meta["file"], **entry}), flush=True)

    with environment() as (outlets, states, dns):
        config = instrument(original, outlets, dns, extra)
        main = config["inbounds"][0]
        with running_core(binary, config, details, pathlib.Path(meta["file"]).stem):
            verify("default-dns-detour-main", ANSWERS["proxy"],
                   lambda: H.parse_dns_answer(dns_exchange(main, "shared-app-cache.invalid")))
            verify("default-dns-observed-main-only", True,
                   lambda: event_count(states["proxy"], True) > 0 and event_count(states["secondary"], True) == 0)
            if meta["engine"] != "hev":
                for network in ("tcp", "udp"):
                    verify(network + "-unknown-owner-rejected", True,
                           lambda n=network: blocked(lambda: exchange(main, n, b"RRBOX unknown-owner", timeout=0.7)))
                verify("unknown-owner-never-reaches-any-business-outlet", 0,
                       lambda: sum(event_count(state) for state in states.values()))
                verify("shared-dns-still-works-after-business-rejection", ANSWERS["proxy"],
                       lambda: H.parse_dns_answer(dns_exchange(main, "shared-after-rejection.invalid")))
            else:
                if len(config["inbounds"]) != 2:
                    raise ValueError("HEV fixture must have main plus one bound inlet")
                bound = config["inbounds"][1]
                for inlet in (main, bound):
                    def no_auth(value=inlet):
                        try:
                            with connect(value, credentials=False):
                                return False
                        except ValueError as error:
                            return "authentication method rejected" in str(error)
                    verify(inlet["tag"] + "-rejects-unauthenticated-access", True, no_auth)

                def probe(inlet, network, label, number=0):
                    payload = f"RRBOX outlet {label} {network} {number}".encode()
                    actual = exchange(inlet, network, payload)
                    want = {"outbound": label, "host": DESTINATION, "port": 443,
                            "sha256": hashlib.sha256(payload).hexdigest()}
                    if network == "udp":
                        want.update(reply_host=DESTINATION, reply_port=443)
                    return actual == want

                for network in ("tcp", "udp"):
                    verify(network + "-main-outlet", True, lambda n=network: probe(main, n, "proxy"))
                if meta["secondary_available"]:
                    for network in ("tcp", "udp"):
                        verify(network + "-bound-outlet", True, lambda n=network: probe(bound, n, "secondary"))
                        verify(network + "-bound-dns-same-name-independent-cache", ANSWERS["secondary"],
                               lambda n=network: H.parse_dns_answer(dns_exchange(bound, "shared-app-cache.invalid", n)))
                    verify("bound-dns-detour-reached-secondary", True, lambda: event_count(states["secondary"], True) > 0)
                    verify("main-dns-cache-not-contaminated-by-secondary", ANSWERS["proxy"],
                           lambda: H.parse_dns_answer(dns_exchange(main, "shared-app-cache.invalid")))

                    def concurrent_outlets():
                        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                            futures = [pool.submit(probe, inlet, network, label, index)
                                       for index in range(3)
                                       for inlet, label in ((main, "proxy"), (bound, "secondary"))
                                       for network in ("tcp", "udp")]
                            return all(future.result(timeout=15) for future in futures)
                    verify("twelve-concurrent-tcp-udp-flows-never-mix-outlets", True, concurrent_outlets)
                    # Simulate a node endpoint that becomes unavailable after successful
                    # use. It must not silently fall through to the still-running main.
                    states["secondary"]["offline"] = True
                    before = event_count(states["proxy"])
                    for network in ("tcp", "udp"):
                        verify(network + "-offline-secondary-does-not-fall-back", True,
                               lambda n=network: blocked(lambda: exchange(bound, n, b"RRBOX unavailable-node", timeout=0.7)))
                    verify("offline-secondary-added-no-main-business-connections", before,
                           lambda: event_count(states["proxy"]))
                    verify("main-remains-working-with-secondary-offline", True, lambda: probe(main, "tcp", "proxy"))
                else:
                    before = event_count(states["proxy"])
                    for network in ("tcp", "udp"):
                        verify(network + "-missing-binding-rejected", True,
                               lambda n=network: blocked(lambda: exchange(bound, n, b"RRBOX missing-node", timeout=0.7)))

                    def rejected_dns():
                        try:
                            answer = dns_exchange(bound, "bound-missing.invalid", timeout=0.7)
                            return len(answer) >= 12 and bool(struct.unpack("!H", answer[2:4])[0] & 15)
                        except (OSError, EOFError):
                            return True
                    verify("missing-binding-dns-rejected", True, rejected_dns)
                    verify("missing-binding-added-no-main-business-connections", before,
                           lambda: event_count(states["proxy"]))
                    verify("main-still-works-with-missing-binding", True, lambda: probe(main, "tcp", "proxy"))
        variant["passed"] = all(check["passed"] for check in variant["checks"])
        H.save_report(report_path, report)
        print(f"{meta['file']}: {sum(c['passed'] for c in variant['checks'])}/{len(variant['checks'])} native app-node checks", flush=True)


def owner_rule_probe(core_source, fixtures, details, report, report_path, go):
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=core_source,
                              capture_output=True, text=True, check=True, timeout=15).stdout.strip()
    if revision != H.CORE_COMMIT:
        raise ValueError("Rule-test source differs from the pinned production core")
    source = pathlib.Path(__file__).with_name("app-node-rule-check") / "rrbox_app_node_routes_test.go"
    target = core_source / "route/rule/rrbox_app_node_routes_test.go"
    if target.exists():
        raise ValueError("Refusing to overwrite an existing source-tree rule test")
    output = details / "owner-rule-results.json"
    env = {**os.environ, "RRBOX_APP_NODE_FIXTURES": str(fixtures.resolve()), "RRBOX_APP_NODE_RULE_REPORT": str(output.resolve())}
    try:
        shutil.copyfile(source, target)
        result = subprocess.run([go, "test", "./route/rule", "-run", "^TestRRBOXAppNodeRoutingFixtures$", "-count=1", "-v"],
                                cwd=core_source, env=env, capture_output=True, text=True, timeout=600)
        (details / "owner-rule-tests.log").write_text(result.stdout + result.stderr)
        if output.is_file():
            for item in json.loads(output.read_text()):
                variant = {"name": item["file"], "kind": "native-rules-with-supplied-owner", "checks": item["checks"]}
                variant["passed"] = bool(item["checks"]) and all(check["passed"] for check in item["checks"])
                report["variants"].append(variant)
            H.save_report(report_path, report)
        if result.returncode:
            raise RuntimeError("Native owner rule tests failed:\n" + (result.stdout + result.stderr)[-8000:])
        if not output.is_file() or len(json.loads(output.read_text())) != 12:
            raise ValueError("Native owner rule report must include all 12 variants")
    finally:
        target.unlink(missing_ok=True)


def self_test():
    with environment() as (outlets, states, dns):
        for label, port in outlets.items():
            inbound = {"listen_port": port}
            for network in ("tcp", "udp"):
                response = exchange(inbound, network, b"RRBOX observer self-test")
                assert response["outbound"] == label
                assert response["host"] == DESTINATION
                assert response["sha256"] == hashlib.sha256(b"RRBOX observer self-test").hexdigest()
            # Observe UDP DNS forwarded through the chosen SOCKS outlet.
            response = exchange(inbound, "udp", H.query("self-test.invalid"), "1.1.1.1", 53)
            assert H.parse_dns_answer(response) == ANSWERS[label]
        assert all(event_count(state) == 2 for state in states.values())
    print("App-node observer self-test passed; production rule/core gates not exercised.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sing-box", type=pathlib.Path)
    parser.add_argument("--core-source", type=pathlib.Path)
    parser.add_argument("--go", default="go")
    parser.add_argument("--fixtures", type=pathlib.Path, default=pathlib.Path("app/build/app-node-fixtures"))
    parser.add_argument("--report", type=pathlib.Path, default=pathlib.Path("build-reports/APP-NODE-ROUTING-REPORT.json"))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.sing_box is None or args.core_source is None:
        parser.error("--sing-box and --core-source are required")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    details = args.report.parent / "app-node-routing-details"
    details.mkdir(exist_ok=True)
    report = {"schema": 1, "passed": False, "core_version": H.CORE_VERSION, "core_commit": H.CORE_COMMIT,
              "scope": "Pinned native ProcessInfo rule matching; single-core authenticated loopback TCP/UDP and DNS detour isolation. Excludes Android UID lookup, lifecycle and real Telegram login/calls.",
              "variants": []}
    try:
        binary = str(args.sing_box.resolve())
        version = subprocess.run([binary, "version"], capture_output=True, text=True, check=True, timeout=15).stdout
        if f"sing-box version {H.CORE_VERSION}\n" not in version or f"Revision: {H.CORE_COMMIT}\n" not in version:
            raise ValueError("Socket-test binary differs from the pinned production core")
        report["binary_sha256"] = H.sha256(args.sing_box)
        manifest = json.loads((args.fixtures / "manifest.json").read_text())
        expected = {(engine, smart, available) for engine in ("system", "root", "hev")
                    for smart in (False, True) for available in (False, True)}
        actual = {(item["engine"], item["smart"], item["secondary_available"]) for item in manifest["variants"]}
        if actual != expected or len(manifest["variants"]) != 12:
            raise ValueError("All 12 production engine/smart/secondary variants are required")
        extra = "rr-app-node-" + base64.urlsafe_b64encode(manifest["secondary_node_id"].encode()).decode().rstrip("=")
        owner_rule_probe(args.core_source.resolve(), args.fixtures, details, report, args.report, args.go)
        for meta in manifest["variants"]:
            original = json.loads((args.fixtures / meta["file"]).read_text())
            native_variant(binary, original, meta, extra, details, report, args.report)
        report["passed"] = len(report["variants"]) == 24 and all(item["passed"] for item in report["variants"])
    except Exception as error:
        report["error"] = str(error)
        if isinstance(error, subprocess.CalledProcessError):
            report["stderr"] = error.stderr
    finally:
        H.save_report(args.report, report)
    if not report["passed"]:
        print(json.dumps({key: report[key] for key in ("error", "stderr") if key in report}), file=sys.stderr)
        return 1
    print(f"Native app-node routing passed: {report['check_count']} checks; Android device acceptance remains separate.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
