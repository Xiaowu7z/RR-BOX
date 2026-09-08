#!/usr/bin/env python3
"""Real native Root FD + pinned sing-tun system stack in a private netns.

The namespace deliberately has no Linux main/default lookup fallback, matching
Android's terminal unreachable policy. No internet or Android device is used.
The negative control removes only the two owned TCP peer lookup rules: UDP
must still echo while TCP fails; restoring them must recover both IP families.
"""
import argparse
import array
import importlib.util
import json
import os
from pathlib import Path
import select
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
import traceback
import uuid

spec = importlib.util.spec_from_file_location("root_engine_checks", Path(__file__).with_name("verify-root-engine.py"))
root = importlib.util.module_from_spec(spec)
spec.loader.exec_module(root)
CORE_UID, CLIENT_UID = root.APP_UID, root.TEST_UID
PEERS = {"-4": "172.19.0.2/32", "-6": "fdfe:dcba:9876::2/128"}
DESTINATIONS = {"-4": "203.0.113.9", "-6": "2001:db8:200::9"}
SOURCES = {"-4": "192.0.2.2", "-6": "2001:db8:100::2"}


def emit(stream, event):
    stream.write(json.dumps(event) + "\n")
    stream.flush()


def app_endpoint(name, binary, events, requests):
    os.setgroups([])
    os.setgid(CORE_UID)
    os.setuid(CORE_UID)
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    listener.settimeout(10)
    control = None
    core = None
    fd = None
    heartbeat_stop = threading.Event()
    heartbeat_errors = []
    heartbeat = None
    try:
        listener.bind("\0" + name)
        listener.listen(1)
        emit(events, {"ready": True})
        control, _ = listener.accept()
        control.settimeout(10)
        _, uid, _ = struct.unpack("3i", control.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
        assert uid == 0, "helper peer must actually be root"
        message, ancillary, flags, _ = control.recvmsg(1, socket.CMSG_SPACE(4))
        assert message == b"F" and not flags & socket.MSG_CTRUNC
        descriptors = []
        for level, kind, data in ancillary:
            if level == socket.SOL_SOCKET and kind == socket.SCM_RIGHTS:
                values = array.array("i")
                values.frombytes(data[:len(data) - len(data) % values.itemsize])
                descriptors.extend(values)
        assert len(descriptors) == 1, descriptors
        fd = descriptors[0]
        ready = root.recv_line(control)
        assert ready.startswith("READY rr"), ready
        tun_name = ready.split()[1]
        core = subprocess.Popen([binary, "--fd", str(fd), "--name", tun_name], pass_fds=(fd,),
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        os.close(fd)
        fd = None
        assert select.select([core.stdout], [], [], 10)[0], "system stack startup timed out"
        assert core.stdout.readline().strip() == "STACK_READY", core.stderr.read() if core.poll() is not None else "stack not ready"
        root.request(control, f"CONFIG include {CLIENT_UID} - -", "CONFIGURED")
        root.request(control, "ACTIVATE", "ACTIVE")

        def keepalive():
            try:
                while not heartbeat_stop.wait(2):
                    root.request(control, "HEARTBEAT", "OK")
            except BaseException:
                heartbeat_errors.append(traceback.format_exc())

        heartbeat = threading.Thread(target=keepalive, daemon=True)
        heartbeat.start()
        emit(events, {"active": True, "tun": tun_name})
        assert requests.readline().strip() == "STOP", "parent did not request orderly stop"
        heartbeat_stop.set()
        heartbeat.join(timeout=12)
        assert not heartbeat.is_alive() and not heartbeat_errors, heartbeat_errors
        # Native cleanup is acknowledged before the handed-off descriptor closes.
        root.request(control, "STOP", "STOPPED")
        stdout, stderr = core.communicate("STOP\n", timeout=10)
        assert core.returncode == 0, (core.returncode, stderr)
        emit(events, {"stopped": True, "core": json.loads(stdout), "stderr": stderr[-2048:]})
    except BaseException:
        details = traceback.format_exc()
        if core is not None and core.poll() is not None:
            details += "\ncore stderr: " + core.stderr.read()
        emit(events, {"error": details})
    finally:
        heartbeat_stop.set()
        if core is not None and core.poll() is None:
            core.kill()
            core.wait(timeout=5)
        if fd is not None:
            os.close(fd)
        if control is not None:
            control.close()
        listener.close()


def next_event(events, timeout=20):
    assert select.select([events], [], [], timeout)[0], "app endpoint timed out"
    raw = events.readline()
    assert raw, "app endpoint closed without result"
    event = json.loads(raw)
    assert "error" not in event, event.get("error")
    return event


def exchange(family, protocol):
    """Send from an actual distinct Android-like app UID, not a root socket."""
    reader, writer = os.pipe()
    pid = os.fork()
    if pid == 0:
        os.close(reader)
        result = {"family": family, "protocol": protocol}
        try:
            os.setgroups([])
            os.setgid(CLIENT_UID)
            os.setuid(CLIENT_UID)
            kind = socket.SOCK_STREAM if protocol == "tcp" else socket.SOCK_DGRAM
            af = socket.AF_INET if family == "-4" else socket.AF_INET6
            payload = (b"RRBOX-real-system-stack-" + os.urandom(41)) * (1024 if protocol == "tcp" else 16)
            with socket.socket(af, kind) as sock:
                sock.settimeout(3)
                sock.bind((SOURCES[family], 0))
                sock.connect((DESTINATIONS[family], 23456))
                if protocol == "tcp":
                    sock.sendall(payload)
                    received = bytearray()
                    while len(received) < len(payload):
                        chunk = sock.recv(len(payload) - len(received))
                        assert chunk, "EOF before full echo"
                        received.extend(chunk)
                else:
                    sock.send(payload)
                    received = sock.recv(65535)
                assert received == payload, "bidirectional payload mismatch"
            result.update(success=True, bytes=len(payload))
        except BaseException as error:
            result.update(success=False, error=f"{type(error).__name__}: {error}")
        with os.fdopen(writer, "w") as output:
            emit(output, result)
        os._exit(0)
    os.close(writer)
    try:
        with os.fdopen(reader) as output:
            assert select.select([output], [], [], 12)[0], "UID client exceeded deadline"
            return json.loads(output.readline())
    finally:
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        os.waitpid(pid, 0)


def peer_rule(family, operation, table):
    root.command(family, "rule", operation, "pref", "9000", "to", PEERS[family], "lookup", str(table))


def namespace_setup():
    root.command("link", "set", "lo", "up")
    root.command("link", "add", "rrphys0", "type", "dummy")
    root.command("addr", "add", SOURCES["-4"] + "/24", "dev", "rrphys0")
    root.command("-6", "addr", "add", SOURCES["-6"] + "/64", "dev", "rrphys0", "nodad")
    root.command("link", "set", "rrphys0", "up")
    # Android has a local rule but no catch-all main/default lookup. Physical
    # fallback is constrained to local-origin traffic and these public prefixes,
    # so it cannot accidentally repair the missing TUN peer return path.
    for family, prefix in (("-4", "203.0.113.0/24"), ("-6", "2001:db8:200::/64")):
        for preference in (32766, 32767):
            root.command(family, "rule", "del", "pref", str(preference), check=False)
        root.command(family, "route", "add", "table", "49997", prefix, "dev", "rrphys0")
        root.command(family, "rule", "add", "pref", "31000", "iif", "lo", "lookup", "49997")
        root.command(family, "rule", "add", "pref", "32000", "unreachable")
        assert "lookup main" not in root.command(family, "rule", "show").stdout
        assert root.command(family, "route", "get", PEERS[family].split("/")[0], "uid", str(CORE_UID), check=False).returncode != 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--helper", required=True)
    parser.add_argument("--stack", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    assert os.geteuid() == 0, "requires sudo inside unshare --net"
    assert os.readlink("/proc/self/ns/net") != os.readlink("/proc/1/ns/net"), "REFUSING to modify host network namespace"
    helper, stack = str(Path(args.helper).resolve()), str(Path(args.stack).resolve())
    assert root.IP and Path(helper).is_file() and Path(stack).is_file()
    namespace_setup()
    baseline = root.rules()
    baseline_interfaces = root.interfaces()
    name = "rrbox-root-" + uuid.uuid4().hex
    app_read, parent_write = os.pipe()
    parent_read, app_write = os.pipe()
    pid = os.fork()
    if pid == 0:
        os.close(parent_read)
        os.close(parent_write)
        with os.fdopen(app_write, "w", buffering=1) as events, os.fdopen(app_read) as requests:
            app_endpoint(name, stack, events, requests)
        os._exit(0)
    os.close(app_read)
    os.close(app_write)
    native = None
    report = {"network_namespace_isolated": True, "real_device_test": False,
              "stack": "pinned sing-tun system", "android_policy_without_main_fallback": True,
              "negative_control": [], "bidirectional": [], "passed": False}
    try:
        with os.fdopen(parent_read) as events, os.fdopen(parent_write, "w", buffering=1) as requests:
            assert next_event(events).get("ready")
            native = subprocess.Popen([helper, "--socket", name, "--uid", str(CORE_UID), "--pid", str(pid),
                                       "--start", root.start_ticks(pid), "--deadline", str(root.boot_seconds() + 50)],
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            active = next_event(events)
            assert active.get("active"), active
            tun_name = active["tun"]
            # One owned table in both families. Numeric JSON avoids display aliases.
            tables = {}
            for family in PEERS:
                entries = json.loads(root.command(family, "-j", "rule", "show").stdout)
                peer_entries = [entry for entry in entries if entry.get("priority") == 9000 and
                                entry.get("dst", "").split("/")[0] == PEERS[family].split("/")[0]]
                assert len(peer_entries) == 1, peer_entries
                tables[family] = int(peer_entries[0]["table"])
                assert "uidrange" not in peer_entries[0] and "iif" not in peer_entries[0], peer_entries[0]
                core_route = root.command(family, "route", "get", DESTINATIONS[family], "uid", str(CORE_UID)).stdout
                assert "dev rrphys0" in core_route and tun_name not in core_route, core_route
                app_route = root.command(family, "route", "get", DESTINATIONS[family], "uid", str(CLIENT_UID)).stdout
                assert f"dev {tun_name}" in app_route, app_route
            assert len(set(tables.values())) == 1, tables
            removed = []
            try:
                for family in PEERS:
                    peer_rule(family, "del", tables[family])
                    removed.append(family)
                for family in PEERS:
                    assert root.command(family, "route", "get", PEERS[family].split("/")[0], "uid", str(CORE_UID), check=False).returncode != 0
                    udp = exchange(family, "udp")
                    assert udp["success"], {"negative_control_udp": udp}
                    tcp = exchange(family, "tcp")
                    assert not tcp["success"], "negative control failed to reproduce missing-peer TCP failure"
                    report["negative_control"].extend((udp, tcp))
            finally:
                for family in removed:
                    peer_rule(family, "add", tables[family])
            for family in PEERS:
                peer_route = root.command(family, "route", "get", PEERS[family].split("/")[0], "uid", str(CORE_UID)).stdout
                assert f"dev {tun_name}" in peer_route, peer_route
                for protocol in ("tcp", "udp"):
                    result = exchange(family, protocol)
                    assert result["success"], result
                    report["bidirectional"].append(result)
            requests.write("STOP\n")
            requests.flush()
            stopped = next_event(events)
            assert stopped.get("stopped"), stopped
            report["core"] = stopped["core"]
            stdout, stderr = native.communicate(timeout=8)
            assert native.returncode == 0, (native.returncode, stdout, stderr)
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline and (root.rules() != baseline or root.interfaces() != baseline_interfaces):
                time.sleep(0.1)
            assert root.rules() == baseline, "owned rules leaked or unrelated rules changed"
            assert root.interfaces() == baseline_interfaces, "TUN interface/descriptor leaked"
            for family, table in tables.items():
                listing = root.command(family, "route", "show", "table", str(table), check=False)
                assert not listing.stdout.strip(), "owned route table leaked"
                assert listing.returncode == 0 or "FIB table does not exist" in listing.stderr, listing.stderr
            report.update(passed=True, exact_cleanup=True, core_uid_bypasses_business_capture=True)
            print(json.dumps({"root_tcp_udp_bidirectional_checks": 4, "negative_control_checks": 4, "passed": True}))
    except BaseException:
        report["error"] = traceback.format_exc()
        report["rules_at_failure"] = root.rules()
        if native is not None:
            native.kill()
            stdout, stderr = native.communicate(timeout=8)
            report["helper_diagnostics"] = {"stdout": stdout[-4096:], "stderr": stderr[-4096:]}
        raise
    finally:
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        os.waitpid(pid, 0)
        output = Path(args.report)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n")
        output.chmod(0o644)


if __name__ == "__main__":
    main()
