#!/usr/bin/env python3
"""Exercise RRBOX's native HEV per-app outlet patch with real IPv4 TCP/UDP.

An external socketpair TUN carries raw IP packets through the patched HEV/lwIP
shared library. Loopback SOCKS observers distinguish outlets and associations.
The host callback emulates Android socket-owner answers, including controlled
UID and route-generation changes. Android's owner API itself is not exercised.
"""

import argparse
import ctypes
import hashlib
import importlib.util
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


SPEC = importlib.util.spec_from_file_location(
    "native_dns_helpers", Path(__file__).with_name("verify-hev-native-dns.py"))
HELPERS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPERS)
require = HELPERS.require
checksum = HELPERS.checksum
receive_exact = HELPERS.receive_exact
CLIENT = HELPERS.CLIENT
TIMEOUT = 8
QUIET = 0.35
DEST_A = ("203.0.113.21", 443)
DEST_B = ("203.0.113.22", 8443)
AUTH = ("rr_fixture_user", "rr_fixture_password")


def wait_until(predicate, message, timeout=TIMEOUT):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.015)
    raise TimeoutError(message)


def route_key(protocol, port, destination):
    return "%d|%s|%d|%s|%d" % (protocol, CLIENT, port, *destination)


def token(uid, generation, port):
    return (uid << 32) | (generation << 16) | port


def packet(protocol, source_port, destination, payload=b"", sequence=0,
           acknowledgement=0, flags=0):
    address, target_port = destination
    if protocol == 17:
        segment = struct.pack("!HHHH", source_port, target_port, len(payload) + 8, 0) + payload
        offset = 6
    else:
        segment = struct.pack("!HHIIBBHHH", source_port, target_port, sequence,
                              acknowledgement, 0x50, flags, 65535, 0, 0) + payload
        offset = 16
    endpoints = socket.inet_aton(CLIENT) + socket.inet_aton(address)
    pseudo = endpoints + struct.pack("!BBH", 0, protocol, len(segment))
    value = checksum(pseudo + segment) or (0xFFFF if protocol == 17 else 0)
    segment = segment[:offset] + struct.pack("!H", value) + segment[offset + 2:]
    header = struct.pack("!BBHHHBBH", 0x45, 0, len(segment) + 20, 1, 0, 64, protocol, 0) + endpoints
    return header[:10] + struct.pack("!H", checksum(header)) + header[12:] + segment


class Tunnel:
    def __init__(self, descriptor):
        self.socket = descriptor
        self.pending = []

    def send_udp(self, port, destination, payload):
        self.socket.send(packet(17, port, destination, payload))

    def send_tcp(self, port, destination, sequence, acknowledgement, flags, payload=b""):
        self.socket.send(packet(6, port, destination, payload, sequence, acknowledgement, flags))

    def receive(self, protocol, port, destination, timeout=TIMEOUT):
        expected = (protocol, port, destination)
        deadline = time.monotonic() + timeout
        while True:
            for index, (key, segment) in enumerate(self.pending):
                if key == expected:
                    self.pending.pop(index)
                    return segment
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("No matching native packet: %r" % (expected,))
            self.socket.settimeout(remaining)
            data = self.socket.recv(65535)
            # lwIP may emit link-local IPv6 neighbour discovery during startup,
            # even with the production IPv4-only tunnel profile.
            if len(data) >= 41 and data[0] >> 4 == 6 and data[6] == 58 and data[40] in (133, 135, 136):
                continue
            require(len(data) >= 20 and data[0] >> 4 == 4,
                    "Non-IPv4 native reply: length=%d prefix=%s" % (len(data), data[:24].hex()))
            ihl = (data[0] & 15) * 4
            require(checksum(data[:ihl]) == 0, "Invalid IPv4 checksum")
            require(struct.unpack("!H", data[2:4])[0] == len(data), "Invalid IPv4 length")
            if data[9] not in (6, 17):
                continue
            require(data[16:20] == socket.inet_aton(CLIENT), "Reply changed client IP")
            segment = data[ihl:]
            source_port, target_port = struct.unpack("!HH", segment[:4])
            pseudo = data[12:20] + struct.pack("!BBH", 0, data[9], len(segment))
            if data[9] == 6 or segment[6:8] != b"\0\0":
                require(checksum(pseudo + segment) == 0, "Invalid transport checksum")
            if data[9] == 17:
                require(struct.unpack("!H", segment[4:6])[0] == len(segment), "Invalid UDP length")
            key = (data[9], target_port, (socket.inet_ntoa(data[12:16]), source_port))
            self.pending.append((key, segment))

    def expect_udp(self, port, destination, payload):
        actual = self.receive(17, port, destination)[8:]
        require(actual == payload, "Wrong outlet response: %r != %r" % (actual, payload))

    def expect_no_udp(self, port, destination):
        try:
            self.receive(17, port, destination, QUIET)
        except (TimeoutError, socket.timeout):
            return
        raise RuntimeError("Stale or rejected UDP response reached TUN")

    def connect_tcp(self, port, destination):
        sequence = 1000 + port
        self.send_tcp(port, destination, sequence, 0, 0x02)
        segment = self.receive(6, port, destination)
        peer, acknowledged = struct.unpack("!II", segment[4:12])
        require(segment[13] & 0x12 == 0x12 and acknowledged == sequence + 1, "Invalid TCP SYN-ACK")
        self.send_tcp(port, destination, sequence + 1, peer + 1, 0x10)
        return sequence + 1, peer + 1

    def exchange_tcp(self, port, destination, payload, expected):
        sequence, peer = self.connect_tcp(port, destination)
        framed = struct.pack("!H", len(payload)) + payload
        expected = struct.pack("!H", len(expected)) + expected
        self.send_tcp(port, destination, sequence, peer, 0x18, framed)
        sequence += len(framed)
        assembled = bytearray()
        deadline = time.monotonic() + TIMEOUT
        while len(assembled) < len(expected):
            segment = self.receive(6, port, destination, max(0.01, deadline - time.monotonic()))
            require(not segment[13] & 0x04, "Native TCP connection reset")
            data = segment[(segment[12] >> 4) * 4:]
            if data:
                require(struct.unpack("!I", segment[4:8])[0] == peer, "Unexpected TCP sequence")
                assembled.extend(data)
                peer += len(data)
                self.send_tcp(port, destination, sequence, peer, 0x10)
        require(bytes(assembled) == expected, "TCP response came from the wrong outlet")


class SocksObserver:
    """Concurrent authenticated SOCKS server that observes native association identity."""

    def __init__(self, name, auth=None, block_handshake=False, echo=True):
        self.name, self.auth, self.echo = name, auth, echo
        self.errors, self.sessions, self.datagrams = [], [], []
        self.stop = threading.Event()
        self.handshake_release = threading.Event()
        if not block_handshake:
            self.handshake_release.set()
        self.lock = threading.Lock()
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(16)
        self.listener.settimeout(0.1)
        self.port = self.listener.getsockname()[1]
        self.threads = []
        self.thread = threading.Thread(target=self.accept, daemon=True)
        self.thread.start()

    def accept(self):
        while not self.stop.is_set():
            try:
                connection, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            with self.lock:
                session = {"association": len(self.sessions) + 1, "handshake": False}
                self.sessions.append(session)
            thread = threading.Thread(target=self.serve, args=(connection, session), daemon=True)
            self.threads.append(thread)
            thread.start()

    def serve(self, connection, session):
        try:
            with connection:
                connection.settimeout(TIMEOUT)
                greeting = receive_exact(connection, 2)
                require(greeting[0] == 5, "Invalid SOCKS version")
                methods = receive_exact(connection, greeting[1])
                selected = 2 if self.auth else 0
                require(selected in methods, "Required SOCKS authentication method missing")
                # Production pipeline sends auth and CONNECT/ASSOCIATE before any server reply.
                if self.auth:
                    version, length = receive_exact(connection, 2)
                    require(version == 1, "Invalid username/password authentication version")
                    username = receive_exact(connection, length).decode()
                    password = receive_exact(connection, receive_exact(connection, 1)[0]).decode()
                    require((username, password) == self.auth, "Native SOCKS credentials were lost")
                header = receive_exact(connection, 4)
                require(header[:1] == b"\x05" and header[2:] == b"\0\x01", "Expected IPv4 SOCKS request")
                address = socket.inet_ntoa(receive_exact(connection, 4))
                port = struct.unpack("!H", receive_exact(connection, 2))[0]
                session.update(handshake=True, command=header[1], destination=[address, port],
                               pipeline=True, authenticated=bool(self.auth))
                require(self.handshake_release.wait(TIMEOUT), "Handshake release not received")
                auth_reply = bytes((5, selected)) + (b"\x01\0" if self.auth else b"")
                if header[1] == 1:
                    connection.sendall(auth_reply + b"\x05\0\0\x01\x7f\0\0\x01\0\0")
                    length = struct.unpack("!H", receive_exact(connection, 2))[0]
                    payload = receive_exact(connection, length)
                    session["payload"] = payload.decode("ascii")
                    response = self.name.encode() + b":" + payload
                    connection.sendall(struct.pack("!H", len(response)) + response)
                    self.stop.wait(TIMEOUT)
                else:
                    require(header[1] == 3, "Expected UDP ASSOCIATE")
                    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as relay:
                        relay.bind(("127.0.0.1", 0))
                        relay.settimeout(0.1)
                        connection.sendall(auth_reply + b"\x05\0\0\x01\x7f\0\0\x01" +
                                           struct.pack("!H", relay.getsockname()[1]))
                        while not self.stop.is_set():
                            try:
                                data, peer = relay.recvfrom(65535)
                            except socket.timeout:
                                continue
                            require(data[:4] == b"\0\0\0\x01", "Invalid UDP SOCKS framing")
                            entry = {"association": session["association"], "socket": relay, "peer": peer,
                                     "header": data[:10], "payload": data[10:],
                                     "destination": (socket.inet_ntoa(data[4:8]),
                                                     struct.unpack("!H", data[8:10])[0])}
                            with self.lock:
                                self.datagrams.append(entry)
                            if self.echo:
                                self.reply(entry, self.name.encode() + b":" + data[10:])
        except Exception as error:
            if not self.stop.is_set():
                self.errors.append(str(error))

    @staticmethod
    def reply(entry, payload):
        entry["socket"].sendto(entry["header"] + payload, entry["peer"])

    def received(self, payload):
        return [entry for entry in self.datagrams if entry["payload"] == payload]

    def wait_received(self, payload):
        wait_until(lambda: self.received(payload), "SOCKS outlet %s missed %r" % (self.name, payload))
        return self.received(payload)[-1]

    def close(self):
        self.stop.set()
        self.handshake_release.set()
        self.listener.close()
        self.thread.join(timeout=1)
        for thread in self.threads:
            thread.join(timeout=1)


class NativeCase:
    def __init__(self, library, yaml, enabled=True, auth=None, observer_options=None):
        self.temporary = tempfile.TemporaryDirectory(prefix="rrbox-hev-app-routing-")
        self.directory = Path(self.temporary.name)
        self.observers = {
            name: SocksObserver(name, auth, **(observer_options or {}).get(name, {}))
            for name in ("main", "hk", "la")}
        self.routes = {}
        self.route_file = self.directory / "routes.json"
        self.audit_file = self.directory / "owners.jsonl"
        self.write_routes()
        self.log = (self.directory / "native.log").open("w+")
        yaml, replacements = re.subn(r"(?m)^  port: \d+$", "  port: %d" % self.observers["main"].port, yaml)
        require(replacements == 1, "Expected exactly one production SOCKS port")
        if auth:
            yaml = yaml.replace("socks5:\n", "socks5:\n  username: '%s'\n  password: '%s'\n" % auth)
        config = self.directory / "hev.yaml"
        config.write_text(yaml)
        descriptor, native_descriptor = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
        self.tun = Tunnel(descriptor)
        self.process = subprocess.Popen(
            [sys.executable, str(Path(__file__).resolve()), "--worker", str(library), str(config),
             str(native_descriptor.fileno()), str(self.route_file), str(self.audit_file), str(int(enabled))],
            pass_fds=(native_descriptor.fileno(),), env=HELPERS.library_environment(library),
            stdout=self.log, stderr=subprocess.STDOUT)
        native_descriptor.close()

    def write_routes(self):
        pending = self.directory / "routes.next"
        pending.write_text(json.dumps(self.routes))
        pending.replace(self.route_file)

    def set_route(self, protocol, port, destination, outlet, uid=10001, generation=1):
        value = token(uid, generation, self.observers[outlet].port) if outlet else -1
        self.routes[route_key(protocol, port, destination)] = value
        self.write_routes()
        return value

    def audit(self):
        if not self.audit_file.exists():
            return []
        return [json.loads(line) for line in self.audit_file.read_text().splitlines() if line]

    def wait_lookup(self, value, count=1):
        wait_until(lambda: sum(row["token"] == value for row in self.audit()) >= count,
                   "Native worker did not revalidate changed route token")

    def check(self):
        require(self.process.poll() is None, "Native HEV worker exited unexpectedly")
        for observer in self.observers.values():
            require(not observer.errors, "%s SOCKS observer: %r" % (observer.name, observer.errors))

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=2)
        self.tun.socket.close()
        for observer in self.observers.values():
            observer.close()
        self.log.seek(0)
        log = self.log.read()[-4000:]
        self.log.close()
        self.temporary.cleanup()
        return log


def multi_outlet(case):
    for protocol in (6, 17):
        case.set_route(protocol, 43101, DEST_A, "hk", uid=10101)
        case.set_route(protocol, 43101, DEST_B, "la", uid=10101)
    for destination, outlet in ((DEST_A, "hk"), (DEST_B, "la")):
        case.tun.exchange_tcp(43101, destination, b"tcp-original", outlet.encode() + b":tcp-original")
    # The same UDP source socket sends to two destinations, concurrently alive.
    case.tun.send_udp(43101, DEST_A, b"udp-a")
    case.tun.send_udp(43101, DEST_B, b"udp-b")
    case.tun.expect_udp(43101, DEST_A, b"hk:udp-a")
    case.tun.expect_udp(43101, DEST_B, b"la:udp-b")
    for destination, outlet, payload in ((DEST_B, "la", b"second-b"), (DEST_A, "hk", b"second-a")):
        case.tun.send_udp(43101, destination, payload)
        case.tun.expect_udp(43101, destination, outlet.encode() + b":" + payload)
        observer = case.observers[outlet]
        datagrams = observer.datagrams
        require(len(datagrams) == 2 and len({row["association"] for row in datagrams}) == 1,
                "UDP association was not stable for one full tuple")
        require(all(row["destination"] == destination for row in datagrams), "UDP crossed outlet destination")
        require(all(row["pipeline"] and row["authenticated"] for row in observer.sessions),
                "Routed SOCKS session did not preserve authenticated pipelining")
    require(not case.observers["main"].sessions, "Explicit app outlet leaked to main")
    return {"tcp_outlets": ["hk", "la"], "udp_outlets": ["hk", "la"],
            "same_udp_source_socket": True, "separate_destination_associations": True,
            "username_password": True, "pipeline_observed": True}


def stale_association(case, change, trigger):
    observer = case.observers["hk"]
    case.set_route(17, 43201, DEST_A, "hk")
    case.tun.send_udp(43201, DEST_A, b"old-owner")
    old = observer.wait_received(b"old-owner")
    next_uid, next_generation = (10002, 1) if change == "uid" else (10001, 2)
    current = case.set_route(17, 43201, DEST_A, "hk", next_uid, next_generation)
    if trigger == "request":
        case.tun.send_udp(43201, DEST_A, b"invalidating-request")
        case.wait_lookup(current)
        time.sleep(QUIET)
        require(not observer.received(b"invalidating-request"), "Changed token reused old UDP request association")
    observer.reply(old, b"stale-old-owner-reply")
    case.wait_lookup(current)
    case.tun.expect_no_udp(43201, DEST_A)
    case.tun.send_udp(43201, DEST_A, b"new-owner")
    fresh = observer.wait_received(b"new-owner")
    require(fresh["association"] != old["association"], "Changed owner/generation reused old SOCKS association")
    observer.reply(fresh, b"fresh-owner-reply")
    case.tun.expect_udp(43201, DEST_A, b"fresh-owner-reply")
    require(not case.observers["main"].sessions, "Invalidated flow leaked to main")
    return {"change": change, "invalidation_trigger": trigger, "same_tuple_and_outlet_port": True,
            "old_reply_dropped": True, "new_association": True,
            "invalidating_request_dropped": trigger == "request"}


def full_tuple_isolation(case):
    # Vary destination IP and port independently. A table keyed by only source,
    # source+destination IP, or source+destination port must fail this case.
    destinations = [(DEST_A, "hk"), ((DEST_A[0], DEST_B[1]), "la"),
                    ((DEST_B[0], DEST_A[1]), "la")]
    for destination, outlet in destinations:
        case.set_route(17, 43251, destination, outlet)
    associations = {}
    for iteration in range(2):
        for index, (destination, outlet) in enumerate(destinations):
            payload = ("tuple-%d-round-%d" % (index, iteration)).encode()
            case.tun.send_udp(43251, destination, payload)
            case.tun.expect_udp(43251, destination, outlet.encode() + b":" + payload)
            entry = case.observers[outlet].wait_received(payload)
            identity = (outlet, entry["association"])
            if iteration == 0:
                associations[destination] = identity
            else:
                require(associations[destination] == identity, "Full UDP tuple did not retain its association")
    require(len(set(associations.values())) == 3, "Different UDP destinations shared a SOCKS association")
    require(not case.observers["main"].sessions, "Full-tuple UDP route leaked to main")
    return {"same_source_socket": True, "destination_ip_and_port_varied_independently": True,
            "distinct_associations": 3, "repeated_packets_reuse_correct_association": True}


def queued_before_handshake(case, change):
    observer = case.observers["hk"]
    case.set_route(17, 43301, DEST_A, "hk")
    case.tun.send_udp(43301, DEST_A, b"queued-old-owner")
    wait_until(lambda: observer.sessions and observer.sessions[0]["handshake"], "Native SOCKS handshake not pipelined")
    next_uid, next_generation = (10002, 1) if change == "uid" else (10001, 2)
    current = case.set_route(17, 43301, DEST_A, "hk", next_uid, next_generation)
    observer.handshake_release.set()
    case.wait_lookup(current)
    time.sleep(QUIET)
    require(not observer.datagrams, "Old queued UDP request escaped after ownership changed during handshake")
    case.tun.send_udp(43301, DEST_A, b"new-after-handshake")
    entry = observer.wait_received(b"new-after-handshake")
    require(entry["association"] != 1, "Invalidated queued flow retained its old SOCKS association")
    case.tun.expect_udp(43301, DEST_A, b"hk:new-after-handshake")
    return {"change": change, "same_tuple_and_outlet_port": True, "queued_request_dropped": True,
            "new_association": True, "pipeline_observed": True}


def unknown_owner(case):
    case.tun.send_udp(43401, DEST_A, b"unknown-owner-udp")
    sequence, peer = case.tun.connect_tcp(43402, DEST_B)
    case.tun.send_tcp(43402, DEST_B, sequence, peer, 0x18, b"unknown-owner-tcp")
    wait_until(lambda: {row["protocol"] for row in case.audit() if row["token"] == -1} == {6, 17},
               "Unknown owner was not queried for both transports")
    case.tun.expect_no_udp(43401, DEST_A)
    require(all(not observer.sessions for observer in case.observers.values()), "Unknown owner reached a SOCKS outlet")
    return {"tcp_rejected": True, "udp_rejected": True, "main_fallback": False}


def disabled_legacy(case):
    case.tun.exchange_tcp(43501, DEST_A, b"legacy-tcp", b"main:legacy-tcp")
    case.tun.send_udp(43502, DEST_B, b"legacy-udp")
    case.tun.expect_udp(43502, DEST_B, b"main:legacy-udp")
    require(not case.audit(), "Disabled app routing invoked owner callback")
    require(all(not case.observers[name].sessions for name in ("hk", "la")), "Legacy traffic used app outlet")
    return {"tcp_main": True, "udp_main": True, "owner_callback_count": 0}


def production_yaml(fixtures):
    if fixtures is not None:
        manifest = json.loads((fixtures / "manifest.json").read_text())
        yaml = (fixtures / "hev.yaml").read_text()
        return yaml, manifest["producer"]
    # A standalone host invocation can use the exact defaults without Gradle.
    # CI passes exported HevTunnelConfig fixtures and therefore catches drift.
    return """tunnel:
  mtu: 8500
  ipv4: 198.18.0.1
  icmp: 'off'

socks5:
  port: 1080
  address: 127.0.0.1
  udp: 'udp'
  pipeline: true
  tcp-fastopen: true

misc:
  task-stack-size: 86016
  tcp-buffer-size: 131072
  udp-recv-buffer-size: 1048576
  udp-copy-buffer-nums: 32
  max-session-count: 0
  connect-timeout: 10000
  tcp-read-write-timeout: 300000
  udp-read-write-timeout: 60000
  log-level: error
""", "standalone production-default fixture; pass --fixtures to validate Kotlin export"


def worker():
    library, config, descriptor, routes, audit, enabled = sys.argv[2:]
    allocator = Path(library).parent.parent / "third-part/hev-task-system/bin/libhev-task-system.so"
    allocator_handle = ctypes.CDLL(str(allocator), mode=ctypes.RTLD_GLOBAL)
    native = ctypes.CDLL(library)
    callback_type = ctypes.CFUNCTYPE(ctypes.c_int64, ctypes.c_int, ctypes.c_char_p,
                                    ctypes.c_int, ctypes.c_char_p, ctypes.c_int)

    def resolve(protocol, source, source_port, destination, destination_port):
        try:
            key = "%d|%s|%d|%s|%d" % (protocol, source.decode(), source_port,
                                       destination.decode(), destination_port)
            answer = int(json.loads(Path(routes).read_text()).get(key, -1))
            with Path(audit).open("a") as output:
                output.write(json.dumps({"protocol": protocol, "key": key, "token": answer}) + "\n")
            return answer
        except Exception:
            return -1

    callback = callback_type(resolve)  # Must remain alive for the entire native worker call.
    native.rr_app_route_set_callback.argtypes = [callback_type]
    native.rr_app_route_set_callback.restype = None
    native.rr_app_route_set_enabled.argtypes = [ctypes.c_int]
    native.rr_app_route_set_enabled.restype = None
    native.rr_app_route_set_callback(callback)
    native.rr_app_route_set_enabled(int(enabled))
    native.hev_socks5_tunnel_main_from_file.argtypes = [ctypes.c_char_p, ctypes.c_int]
    native.hev_socks5_tunnel_main_from_file.restype = ctypes.c_int
    # Both Python objects intentionally outlive all callbacks and allocator use.
    require(allocator_handle is not None and callback is not None, "Host callback setup failed")
    return native.hev_socks5_tunnel_main_from_file(os.fsencode(config), int(descriptor))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hev-library", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path)
    parser.add_argument("--report", type=Path, default=Path("build-reports/hev-native-app-routing-validation.json"))
    args = parser.parse_args()
    report = {"schema": 1, "status": "failed", "cases": [],
              "scope": "patched native HEV/lwIP raw IPv4 TCP/UDP socketpair TUN and loopback SOCKS",
              "limitations": ["Host callback emulates Android connection-owner UID results; Android API/JNI not exercised",
                              "Android VpnService, physical TUN and device lifecycle not exercised",
                              "No Internet; loopback SOCKS observers replace remote nodes",
                              "Production HEV IPv4-only profile; no claim of IPv6 support"]}
    try:
        library = args.hev_library.resolve()
        require(library.is_file(), "Build patched host HEV shared library first")
        yaml, producer = production_yaml(args.fixtures)
        for required in ("  mtu: 8500", "  ipv4: " + CLIENT, "  address: 127.0.0.1",
                         "  udp: 'udp'", "  pipeline: true", "  tcp-fastopen: true",
                         "  task-stack-size: 86016", "  tcp-buffer-size: 131072",
                         "  udp-recv-buffer-size: 1048576", "  udp-copy-buffer-nums: 32"):
            require(required in yaml.splitlines(), "Unexpected production HEV profile: " + required)
        require(not re.search(r"(?m)^mapdns:", yaml), "Native mapped DNS must remain disabled")
        report.update(library_sha256=hashlib.sha256(library.read_bytes()).hexdigest(),
                      production_yaml_sha256=hashlib.sha256(yaml.encode()).hexdigest(),
                      fixture_producer=producer,
                      config_overrides=["Loopback SOCKS observer ports", "Fixture credentials for auth case"])
        cases = [("multi-outlet-authenticated-tcp-udp", multi_outlet, {"auth": AUTH}),
                 ("udp-full-tuple-ip-port-isolation", full_tuple_isolation, {}),
                 ("unknown-owner-fails-closed", unknown_owner, {}),
                 ("disabled-legacy-no-owner-callback", disabled_legacy, {"enabled": False})]
        for change in ("uid", "generation"):
            for trigger in ("request", "reply"):
                cases.append(("udp-%s-change-%s" % (change, trigger),
                              lambda case, c=change, t=trigger: stale_association(case, c, t),
                              {"observer_options": {"hk": {"echo": False}}}))
            cases.append(("udp-%s-change-queued-handshake" % change,
                          lambda case, c=change: queued_before_handshake(case, c),
                          {"observer_options": {"hk": {"block_handshake": True}}}))
        for name, function, options in cases:
            result, case = {"name": name, "passed": False}, None
            try:
                case = NativeCase(library, yaml, **options)
                result.update(function(case))
                case.check()
                result.update(passed=True, owner_lookup_count=len(case.audit()))
            except Exception as error:
                result["error"] = str(error)
                if case:
                    result["worker_exit_code"] = case.process.poll()
                    result["observer_errors"] = {key: value.errors for key, value in case.observers.items()}
                    result["observer_sessions"] = {key: value.sessions for key, value in case.observers.items()}
            finally:
                if case:
                    result["native_log"] = case.close()
            report["cases"].append(result)
            print("%s %s%s" % ("PASS" if result["passed"] else "FAIL", name,
                                ": " + result["error"] if "error" in result else ""), flush=True)
        report.update(checks=len(cases), passed=sum(row["passed"] for row in report["cases"]))
        report["status"] = "passed" if report["passed"] == report["checks"] else "failed"
    except Exception as error:
        report["error"] = str(error)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print("Native HEV app routing: %s; report: %s" % (report["status"], args.report))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(worker() if len(sys.argv) > 1 and sys.argv[1] == "--worker" else main())
