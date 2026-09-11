#!/usr/bin/env python3
"""Exercise the real Root helper only inside an isolated Linux network namespace.

CI: sudo unshare --net -- python3 scripts/verify-root-engine.py --helper /tmp/rrbox-root-engine --report build-reports/ROOT-ENGINE-REPORT.json
No Android device or host networking is modified by this harness.
"""
import argparse
import array
import errno
import json
import os
from pathlib import Path
import select
import shutil
import shlex
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import time
import traceback
import uuid

APP_UID = 10001
TEST_UID = 10002
IP = shutil.which("ip")


def command(*args, check=True):
    return subprocess.run([IP, *args], capture_output=True, text=True, timeout=5, check=check)


def start_ticks(pid):
    return Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[19]


def boot_seconds():
    return int(time.clock_gettime(time.CLOCK_BOOTTIME))


def rules():
    return {family: command(family, "rule", "show").stdout for family in ("-4", "-6")}


def interfaces():
    # sysfs may belong to the mount namespace's original network namespace.
    return {name for _, name in socket.if_nameindex()}


def recv_line(sock):
    data = bytearray()
    while len(data) < 16384:
        value = sock.recv(1)
        if not value:
            raise RuntimeError("helper closed control socket: " + data.decode(errors="replace"))
        if value == b"\n":
            return data.decode("ascii")
        data.extend(value)
    raise RuntimeError("unbounded helper response")


def request(sock, line, expected=None):
    sock.sendall((line + "\n").encode("ascii"))
    answer = recv_line(sock)
    if expected is not None:
        assert answer == expected, (line, answer, expected)
    return answer


def client(sock_name, events, replies, scenario):
    """The authenticated app endpoint genuinely has a different, non-root UID."""
    os.setgroups([])
    os.setgid(APP_UID)
    os.setuid(APP_UID)
    checks = []
    control = None
    tun_fd = None
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    listener.settimeout(8)

    def passed(name):
        checks.append(name)

    def parent_action(name):
        events.write(json.dumps({"action": name}) + "\n")
        events.flush()
        response = json.loads(replies.readline())
        assert response.get("ok"), response

    try:
        listener.bind("\0" + sock_name)
        listener.listen(1)
        before = rules()
        events.write(json.dumps({"ready": True}) + "\n")
        events.flush()
        control, _ = listener.accept()
        control.settimeout(8)
        pid, uid, gid = struct.unpack("3i", control.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
        assert uid == 0, (pid, uid, gid)
        message, ancillary, flags, _ = control.recvmsg(1, socket.CMSG_SPACE(4))
        assert message == b"F" and not flags & socket.MSG_CTRUNC, (message, flags)
        received = []
        for level, kind, data in ancillary:
            if level == socket.SOL_SOCKET and kind == socket.SCM_RIGHTS:
                values = array.array("i")
                values.frombytes(data[:len(data) - len(data) % values.itemsize])
                received.extend(values)
        assert len(received) == 1, received
        tun_fd = received[0]
        ready = recv_line(control)
        assert ready.startswith("READY rr"), ready
        tun_name = ready.split()[1]
        assert tun_name in interfaces(), tun_name
        passed("authenticated_root_peer_and_real_tun_fd")
        assert rules() == before
        passed("no_policy_rules_before_activation")

        if scenario == "malformed":
            answer = request(control, "CONFIG include 0;echo_bad - -")
            assert answer.startswith("ERROR "), answer
            passed("malformed_policy_rejected")
        else:
            request(control, f"CONFIG include 0,{APP_UID},{TEST_UID} - 192.168.50.1,1.1.1.1,2001:db8::53", "CONFIGURED")
            assert rules() == before
            passed("configuration_does_not_activate_routes")
            if scenario == "activation_killed":
                control.sendall(b"ACTIVATE\n")
                parent_action("kill_during_activation")
                passed("controller_killed_after_first_policy_mutation")
            elif scenario == "dns_rule_kernel_rejected":
                answer = request(control, "ACTIVATE")
                assert answer.startswith("ERROR activation_failed "), answer
                # Netlink attribute policy reports EINVAL or ERANGE for this
                # deliberately short payload, depending on the kernel version.
                fields = set(answer.split())
                expected_errors = {f"netlink_errno={code}" for code in (errno.EINVAL, errno.ERANGE)}
                assert "stage=install_ipv4_dns_port_rule" in fields and fields & expected_errors, answer
                passed("kernel_dns_rule_rejection_fails_explicitly_without_whole_ip_fallback")
            else:
                request(control, "ACTIVATE", "ACTIVE")
                passed("dual_stack_activation_acknowledged")
                request(control, "HEARTBEAT", "OK")

            if scenario == "normal":
                def route(family, address, uid, protocol=None, port=None):
                    selectors = ["ipproto", protocol, "dport", str(port)] if protocol else []
                    return command(family, "route", "get", address, "uid", str(uid), *selectors, check=False)

                assert f"dev {tun_name}" in route("-4", "8.8.8.8", TEST_UID).stdout
                passed("selected_uid_ipv4_enters_tun")
                assert "dev rrtest0" in route("-4", "8.8.8.8", APP_UID).stdout
                passed("core_uid_bypasses_tun_even_if_explicitly_included")
                assert "dev rrtest0" in route("-4", "8.8.8.8", TEST_UID + 1).stdout
                passed("unselected_uid_keeps_physical_route")
                assert f"dev {tun_name}" in route("-6", "2001:4860:4860::8888", TEST_UID).stdout
                passed("selected_uid_ipv6_works_without_physical_ipv6_default")
                assert route("-6", "2001:4860:4860::8888", APP_UID).returncode != 0
                passed("core_uid_ipv6_route_not_hijacked")
                assert "dev rrtest0" in route("-4", "192.168.50.2", TEST_UID).stdout
                passed("private_destination_bypasses_tun")
                assert f"dev {tun_name}" in route("-4", "192.168.50.1", TEST_UID).stdout
                passed("explicit_private_dns_destination_enters_tun")
                for family, address in (("-4", "192.168.50.1"), ("-4", "1.1.1.1"), ("-6", "2001:db8::53")):
                    for protocol in ("tcp", "udp"):
                        assert f"dev {tun_name}" in route(family, address, TEST_UID + 1, protocol, 53).stdout
                        assert f"dev {tun_name}" in route(family, address, TEST_UID, protocol, 53).stdout
                        core_route = route(family, address, APP_UID, protocol, 53)
                        assert f"dev {tun_name}" not in core_route.stdout
                        passed(f"shared_{family}_{address}_{protocol}_dns53_without_core_loop")
                        for port in (80, 443, 853, 5228):
                            direct = route(family, address, TEST_UID + 1, protocol, port)
                            assert f"dev {tun_name}" not in direct.stdout, direct.stdout
                            assert "dev rrtest0" in direct.stdout if family == "-4" else direct.returncode != 0
                        passed(f"unselected_{family}_{address}_{protocol}_non_dns_business_bypasses_tun")
                for protocol in ("tcp", "udp"):
                    for port in (443, 5228, 5229, 5230):
                        assert f"dev {tun_name}" in route("-4", "8.8.8.8", TEST_UID, protocol, port).stdout
                        marked = command("-4", "route", "get", "8.8.8.8", "mark", "0x10065",
                                         "uid", str(TEST_UID), "ipproto", protocol, "dport", str(port)).stdout
                        assert f"dev {tun_name}" in marked, marked
                        unselected = command("-4", "route", "get", "8.8.8.8", "mark", "0x10065",
                                             "uid", str(TEST_UID + 1), "ipproto", protocol, "dport", str(port)).stdout
                        assert "dev rrtest0" in unselected, unselected
                    passed(f"selected_app_{protocol}_https_and_push_ports_enter_tun")
                    passed(f"selected_app_{protocol}_physical_fwmark_cannot_bypass_uid_capture")

                for protocol, kind in ((6, socket.SOCK_STREAM), (17, socket.SOCK_DGRAM)):
                    with socket.socket(socket.AF_INET, kind) as owner_socket:
                        owner_socket.bind(("127.0.0.1", 0))
                        if kind == socket.SOCK_STREAM:
                            owner_socket.listen(1)
                        port = owner_socket.getsockname()[1]
                        answer = request(control, f"OWNER {protocol} 127.0.0.1 {port} 127.0.0.1 23456")
                        # TCP LISTEN is not a connected flow; conservative UNKNOWN is valid.
                        if protocol == 17:
                            assert answer == f"UID {APP_UID}", answer
                        else:
                            assert answer in (f"UID {APP_UID}", "UNKNOWN"), answer
                    passed(f"socket_owner_{protocol}_safe_result")

                parent_action("send_udp")
                deadline = time.monotonic() + 5
                packet_seen = False
                while time.monotonic() < deadline:
                    if not select.select([tun_fd], [], [], 0.5)[0]:
                        continue
                    packet = os.read(tun_fd, 65535)
                    if len(packet) >= 28 and packet[0] >> 4 == 4 and packet[9] == 17:
                        if socket.inet_ntoa(packet[16:20]) == "8.8.8.8" and b"rrbox-root-host-check" in packet:
                            packet_seen = True
                            break
                assert packet_seen, "selected root UID's real UDP packet did not reach handed-off TUN fd"
                passed("real_udp_packet_reaches_app_tun_fd")
                request(control, "STOP", "STOPPED")
                passed("normal_stop_acknowledged_after_cleanup")
            elif scenario == "helper_killed":
                parent_action("kill_helper")
                # App releases its copy on native death; guardian must remove policy rules.
                passed("helper_sigkill_requested")
            elif scenario == "client_lost":
                passed("app_control_socket_disconnected")
            elif scenario in ("activation_killed", "dns_rule_kernel_rejected"):
                pass
            else:
                raise AssertionError(scenario)

        if tun_fd is not None:
            os.close(tun_fd)
            tun_fd = None
        control.close()
        control = None
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if rules() == before and tun_name not in interfaces():
                break
            time.sleep(0.1)
        assert rules() == before, rules()
        assert tun_name not in interfaces(), interfaces()
        passed("exact_rule_rollback_and_tun_disappearance")
        events.write(json.dumps({"result": checks}) + "\n")
        events.flush()
    except BaseException:
        events.write(json.dumps({"error": traceback.format_exc(), "checks": checks}) + "\n")
        events.flush()
    finally:
        if tun_fd is not None:
            os.close(tun_fd)
        if control is not None:
            control.close()
        listener.close()


def run_scenario(helper, scenario, profile="host_ip"):
    name = "rrbox-root-" + uuid.uuid4().hex
    child_read, parent_write = os.pipe()
    parent_read, child_write = os.pipe()
    pid = os.fork()
    if pid == 0:
        os.close(parent_read)
        os.close(parent_write)
        with os.fdopen(child_write, "w", buffering=1) as events, os.fdopen(child_read) as replies:
            client(name, events, replies, scenario)
        os._exit(0)
    os.close(child_read)
    os.close(child_write)
    native = None
    result = None
    try:
        with os.fdopen(parent_read) as events, os.fdopen(parent_write, "w", buffering=1) as replies:
            assert select.select([events], [], [], 10)[0], "app endpoint did not start"
            assert json.loads(events.readline()).get("ready")
            native = subprocess.Popen([helper, "--socket", name, "--uid", str(APP_UID),
                                       "--pid", str(pid), "--start", start_ticks(pid),
                                       "--deadline", str(boot_seconds() + 50)],
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            deadline = time.monotonic() + 55
            while time.monotonic() < deadline:
                if not select.select([events], [], [], 1)[0]:
                    continue
                raw = events.readline()
                assert raw, "app endpoint exited without report"
                event = json.loads(raw)
                if event.get("action") == "send_udp":
                    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sender:
                        sender.sendto(b"rrbox-root-host-check", ("8.8.8.8", 23456))
                    replies.write('{"ok": true}\n')
                elif event.get("action") == "kill_helper":
                    native.kill()
                    replies.write('{"ok": true}\n')
                elif event.get("action") == "kill_during_activation":
                    mutation_deadline = time.monotonic() + 8
                    while time.monotonic() < mutation_deadline:
                        if "9000:" in command("-4", "rule", "show").stdout:
                            break
                        time.sleep(0.005)
                    else:
                        raise AssertionError("activation never installed a policy rule")
                    native.kill()
                    replies.write('{"ok": true}\n')
                elif "error" in event:
                    raise AssertionError(event["error"])
                elif "result" in event:
                    result = event["result"]
                    break
            assert result is not None, "scenario deadline exceeded"
        output, error = native.communicate(timeout=8)
        if scenario == "normal":
            assert native.returncode == 0, (native.returncode, output, error)
        elif scenario not in ("helper_killed", "activation_killed"):
            assert native.returncode == 3, (native.returncode, output, error)
        if scenario == "dns_rule_kernel_rejected":
            assert "RRBOX_CI_REJECT_DNS_ADD" in error, error
            result.append("real_kernel_rejected_injected_malformed_dns_attribute")
        return {"scenario": scenario if profile == "host_ip" else f"{profile}_{scenario}",
                "ip_compatibility": profile, "checks": result, "helper_exit": native.returncode,
                "helper_output": output[-2048:], "helper_error": error[-2048:]}
    except BaseException:
        if native is not None:
            native.kill()
            try:
                output, error = native.communicate(timeout=5)
                print("native diagnostics:", output[-4096:], error[-4096:], file=sys.stderr)
            except subprocess.TimeoutExpired:
                pass
        raise
    finally:
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        os.waitpid(pid, 0)


def compile_test_helper(source, destination, ip_path, extra_sources=(), extra_flags=()):
    compiler = shutil.which("gcc")
    assert compiler, "gcc is required to verify Android ip compatibility and kernel DNS-rule rollback"
    subprocess.run([compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                    "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2",
                    f'-DRRBOX_IP_PATH="{ip_path}"', str(source),
                    *map(str, extra_sources), *extra_flags, "-o", str(destination)], check=True)


# Link-time injection is used only in a temporary CI executable. It changes the
# DNS destination-port attribute on RTM_NEWRULE into an invalid short attribute;
# the actual kernel returns EINVAL over netlink. All RTM_DELRULE cleanup requests
# remain untouched, and the production helper has no test-only behavior.
KERNEL_DNS_REJECTION_WRAPPER = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <linux/fib_rules.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

ssize_t __real_sendto(int fd, const void *buffer, size_t length, int flags,
                      const struct sockaddr *address, socklen_t address_length);

ssize_t __wrap_sendto(int fd, const void *buffer, size_t length, int flags,
                     const struct sockaddr *address, socklen_t address_length)
{
    if (address == NULL || address_length < sizeof(struct sockaddr_nl) ||
        address->sa_family != AF_NETLINK || length < NLMSG_LENGTH(sizeof(struct fib_rule_hdr)))
        return __real_sendto(fd, buffer, length, flags, address, address_length);
    const struct nlmsghdr *original = buffer;
    if (original->nlmsg_type != RTM_NEWRULE || original->nlmsg_len != length)
        return __real_sendto(fd, buffer, length, flags, address, address_length);
    unsigned char *copy = malloc(length);
    if (copy == NULL) { errno = ENOMEM; return -1; }
    memcpy(copy, buffer, length);
    const size_t offset = NLMSG_LENGTH(sizeof(struct fib_rule_hdr));
    int remaining = (int)(length - offset);
    struct rtattr *attribute = (struct rtattr *)(copy + offset);
    int injected = 0;
    for (; RTA_OK(attribute, remaining); attribute = RTA_NEXT(attribute, remaining)) {
        if (attribute->rta_type == FRA_DPORT_RANGE) {
            /* Kernel policy requires all four bytes of fib_rule_port_range.
             * One byte keeps the same aligned footprint but is invalid. */
            attribute->rta_len = RTA_LENGTH(1);
            injected = 1;
            fputs("RRBOX_CI_REJECT_DNS_ADD\n", stderr);
            break;
        }
    }
    ssize_t result = __real_sendto(fd, injected ? copy : buffer, length, flags, address, address_length);
    int saved_errno = errno;
    free(copy);
    errno = saved_errno;
    return result;
}
"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--helper", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    assert os.geteuid() == 0, "run with sudo inside unshare --net"
    assert os.readlink("/proc/self/ns/net") != os.readlink("/proc/1/ns/net"), "REFUSING to modify host network namespace"
    assert IP and Path(args.helper).is_file()
    # A private dummy network gives deterministic fallback without external connectivity.
    command("link", "set", "lo", "up")
    command("link", "add", "rrtest0", "type", "dummy")
    command("addr", "add", "192.0.2.2/24", "dev", "rrtest0")
    command("link", "set", "rrtest0", "up")
    command("-4", "route", "add", "default", "via", "192.0.2.1", "dev", "rrtest0")
    # Explicitly bound physical-network sockets carry an Android-like mark.
    # UID capture at 9000 must precede this ordinary physical-network lookup.
    command("-4", "rule", "add", "pref", "13000", "fwmark", "0x10065", "lookup", "main")
    # Unrelated policy state must survive every rollback.
    command("-4", "rule", "add", "pref", "8998", "uidrange", "50000-50000", "lookup", "49999")
    baseline = rules()
    baseline_interfaces = interfaces()
    report = {"network_namespace_isolated": True, "real_device_test": False, "scenarios": []}
    lifecycle_scenarios = ("normal", "malformed", "client_lost", "helper_killed", "activation_killed")
    for scenario in lifecycle_scenarios:
        report["scenarios"].append(run_scenario(str(Path(args.helper).resolve()), scenario))
        assert rules() == baseline, "unrelated rule changed or own rules leaked"
        assert interfaces() == baseline_interfaces, "interface leaked"
    # Android may ship an older ip parser even when its kernel supports DNS-port
    # routing. Reject every unsupported argv token, including numeric output (-N),
    # and require the full real routing/STOP/guardian suite to keep working.
    with tempfile.TemporaryDirectory(prefix="rrbox-root-old-ip-") as temporary:
        work = Path(temporary)
        shim = work / "ip"
        shim.write_text("#!/bin/sh\n" +
                        'for argument in "$@"; do\n' +
                        '  case "$argument" in\n' +
                        '    -N|ipproto|dport) echo "unsupported ip argument: $argument" >&2; exit 2 ;;\n' +
                        '  esac\ndone\n' +
                        f'exec {shlex.quote(IP)} "$@"\n')
        shim.chmod(0o755)
        source = Path(__file__).resolve().parent.parent / "native" / "root_engine.c"
        old_ip_helper = work / "root-engine-old-ip"
        compile_test_helper(source, old_ip_helper, shim)
        for scenario in lifecycle_scenarios:
            report["scenarios"].append(run_scenario(str(old_ip_helper), scenario, "android_old_ip"))
            assert rules() == baseline, "old ip compatibility left own rules or changed unrelated policy"
            assert interfaces() == baseline_interfaces, "old ip compatibility leaked TUN interface"

        # Distinguish a userspace parser limitation from an actual kernel ACK
        # error. Verify that a kernel-rejected DNS selector never broadens to
        # whole-IP capture and that partial state is removed precisely.
        wrapper = work / "reject-dns-rule.c"
        wrapper.write_text(KERNEL_DNS_REJECTION_WRAPPER)
        rejected_helper = work / "root-engine-rejected-dns"
        compile_test_helper(source, rejected_helper, shim, (wrapper,), ("-Wl,--wrap=sendto",))
        report["scenarios"].append(run_scenario(str(rejected_helper), "dns_rule_kernel_rejected", "android_old_ip"))
        assert rules() == baseline, "kernel-rejected DNS selector left own rules or changed unrelated policy"
        assert interfaces() == baseline_interfaces, "kernel-rejected DNS selector leaked TUN interface"
    expired = subprocess.run([str(Path(args.helper).resolve()), "--socket", "rrbox-root-" + uuid.uuid4().hex,
                              "--uid", str(APP_UID), "--pid", str(os.getpid()), "--start", start_ticks(os.getpid()),
                              "--deadline", str(boot_seconds() - 1)], capture_output=True, timeout=5)
    assert expired.returncode != 0
    assert rules() == baseline and interfaces() == baseline_interfaces
    report["expired_start_rejected"] = True
    report["passed"] = True
    report["checks"] = sum(len(item["checks"]) for item in report["scenarios"]) + 1
    output = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    output.chmod(0o644)
    print(json.dumps({"root_engine_host_checks": report["checks"], "passed": True}))


if __name__ == "__main__":
    main()
