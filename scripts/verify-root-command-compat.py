#!/usr/bin/env python3
"""Verify old Android ip compatibility and exact binary rtnetlink rule semantics.

No root, TUN or network mutations: reserve tests use an old-ip command fixture;
rule snapshots and expected request bytes are independently encoded from the
Linux ABI in Python, then consumed by the production C parser/encoder.
"""
import copy
import ipaddress
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent

SHIM = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
state = Path(os.environ["RRBOX_COMPAT_STATE"])
scenario = os.environ["RRBOX_COMPAT_SCENARIO"]
with (state / "commands.jsonl").open("a") as out:
    out.write(json.dumps(args) + "\n")
if any(argument in args for argument in ("-N", "ipproto", "dport")):
    print('Option "-N" is unknown, try "ip -help".', file=sys.stderr)
    sys.exit(255)
if len(args) < 3 or args[0] not in ("-4", "-6"):
    sys.exit(97)
family = args[0]
global_rules = args[1:] == ["rule", "show"]
filtered_rules = len(args) == 5 and args[1:4] == ["rule", "show", "table"]
filtered_routes = len(args) == 5 and args[1:4] == ["route", "show", "table"]
if not (global_rules or filtered_rules or filtered_routes):
    sys.exit(98)
if filtered_routes and args[4] == "99999":
    print("cleanup operation failed", file=sys.stderr)
    sys.exit(5)
if global_rules:
    if scenario == "global_query_error":
        print("RTNETLINK answers:\tPermission denied\r\n\x1b[31m", file=sys.stderr)
        sys.exit(2)
    print("0:\tfrom all lookup local")
    print("10000:\tfrom all fwmark 0xc0000/0xd0000 lookup legacy_system")
    if scenario == "foreign_priority" and family == "-6":
        print("9000:\tfrom all iif lo uidrange 20001-20001 lookup someone_else")
    sys.exit(0)
if not args[4].isdigit():
    sys.exit(99)
table = args[4]
if scenario.startswith("reserve_") or scenario in ("filtered_query_error", "route_query_error"):
    first_file = state / "first_table"
    if not first_file.exists():
        first_file.write_text(table)
    first = first_file.read_text()
    if filtered_rules:
        if scenario == "filtered_query_error":
            print("RTNETLINK answers: Permission denied", file=sys.stderr)
            sys.exit(2)
        occupied_family = "-6" if scenario == "reserve_alias_ipv6" else "-4"
        if scenario in ("reserve_alias_ipv4", "reserve_alias_ipv6") and table == first and family == occupied_family:
            print("11000:\tfrom all iif lo uidrange 20001-20001 lookup vendor_reserved")
    else:
        if scenario == "reserve_route_occupied" and table == first:
            print("default dev vendor0 scope link")
        elif scenario == "reserve_missing_fib":
            print("Error: ipv4: FIB table does not exist.", file=sys.stderr)
            sys.exit(2)
        elif scenario == "route_query_error":
            print("RTNETLINK answers: Permission denied", file=sys.stderr)
            sys.exit(2)
    sys.exit(0)
sys.exit(96)
'''

HARNESS = r'''
#define main rrbox_production_main
#include "root_engine.c"
#undef main

int main(int argc, char **argv)
{
    if (argc != 4) return 90;
    current = (struct engine){
        .socket_fd = -1, .tun_fd = -1, .lock_fd = -1, .guardian_pipe = -1,
        .app_pid = getpid(), .app_uid = getuid(),
        .deadline_ms = boot_ms() + 60000, .lease_ms = boot_ms() + 60000
    };
    FILE *stat = fopen("/proc/self/stat", "r");
    char buffer[4096];
    if (!stat || !fgets(buffer, sizeof(buffer), stat)) return 91;
    fclose(stat);
    char *end = strrchr(buffer, ')');
    if (!end) return 92;
    /* Some host sandboxes virtualize getpid() but expose host /proc IDs. Use
     * this same process's proc identity so app_alive() runs without a stub. */
    long proc_pid = strtol(buffer, NULL, 10);
    if (proc_pid <= 0 || proc_pid > INT_MAX) return 95;
    current.app_pid = (pid_t)proc_pid;
    char *save = NULL;
    char *token = strtok_r(end + 2, " \n", &save);
    for (int field = 3; token; ++field, token = strtok_r(NULL, " \n", &save)) {
        if (field == 22) {
            if (!unsigned_number(token, UINT64_MAX, &current.app_start)) return 93;
            break;
        }
    }
    if (!app_alive()) return 94;
    snprintf(current.socket_name, sizeof(current.socket_name), "rrbox-root-0123456789abcdef");
    snprintf(current.table, sizeof(current.table), "42000");
    current.range_count = 7;
    current.ranges[0] = (struct uid_range){ .first = 10002, .last = 10002 };
    current.ranges[1] = (struct uid_range){ .first = 10003, .last = 10003, .family = 4, .dns_protocol = 6 };
    current.ranges[2] = (struct uid_range){ .first = 10003, .last = 10003, .family = 6, .dns_protocol = 6 };
    snprintf(current.ranges[1].destination, sizeof(current.ranges[1].destination), "192.168.50.1/32");
    snprintf(current.ranges[2].destination, sizeof(current.ranges[2].destination), "2001:db8::53/128");
    current.ranges[3] = (struct uid_range){ .family = 4, .internal_peer = true };
    current.ranges[4] = (struct uid_range){ .family = 6, .internal_peer = true };
    snprintf(current.ranges[3].destination, sizeof(current.ranges[3].destination), "%s", SYSTEM_PEER_IPV4);
    snprintf(current.ranges[4].destination, sizeof(current.ranges[4].destination), "%s", SYSTEM_PEER_IPV6);
    current.ranges[5] = current.ranges[1]; current.ranges[5].dns_protocol = 17;
    current.ranges[6] = current.ranges[2]; current.ranges[6].dns_protocol = 17;

    if (strcmp(argv[1], "encode") == 0) {
        int family = atoi(argv[2]);
        size_t selected = family == 4 ? 1 : 2;
        if (strcmp(argv[3], "udp") == 0) selected += 4;
        for (int remove = 0; remove <= 1; ++remove) {
            struct rr_rule_request request;
            if (!rr_rule_encode(&request, &current.ranges[selected], family, 42000, remove != 0)) return 96;
            const unsigned char *bytes = (const unsigned char *)&request;
            for (size_t i = 0; i < request.header.nlmsg_len; ++i) printf("%02x", bytes[i]);
            putchar('\n');
        }
        return 0;
    }
    if (strcmp(argv[1], "fixture_present") == 0 || strcmp(argv[1], "fixture_absent") == 0) {
        FILE *input = fopen(argv[3], "rb");
        if (input == NULL) return 97;
        union { struct nlmsghdr aligned; unsigned char bytes[65536]; } data;
        size_t size = fread(data.bytes, 1, sizeof(data.bytes), input);
        if (ferror(input) || size == sizeof(data.bytes)) { fclose(input); return 98; }
        fclose(input);
        struct rr_rule_snapshot snapshot = {
            .family = atoi(argv[2]), .table = 42000,
            .present = strcmp(argv[1], "fixture_present") == 0
        };
        bool valid = true;
        int remaining = (int)size;
        struct nlmsghdr *header = (struct nlmsghdr *)data.bytes;
        for (; remaining > 0 && NLMSG_OK(header, remaining); header = NLMSG_NEXT(header, remaining)) {
            if (!rr_rule_observe(header, &snapshot)) { valid = false; break; }
        }
        /* Fixtures independently require exactly business, peer, TCP DNS,
         * UDP DNS in this family; an empty cleanup snapshot is also valid. */
        valid = valid && remaining == 0 && snapshot.found == (snapshot.present ? 4U : 0U);
        printf("%s\n", valid ? "true" : "false");
        return 0;
    }
    bool result = reserve_table();
    printf("%s %s\n", result ? "true" : "false", current.table);
    printf("stage=%s\ndiagnostic=%s\n", failure_stage, command_failure);
    if (getenv("RRBOX_COMPAT_CHECK_CLEANUP")) {
        char saved[sizeof(command_failure)];
        memcpy(saved, command_failure, sizeof(saved));
        int cleanup_result = run_ip(true, NULL, 0, "-6", "route", "show", "table", "99999", NULL);
        printf("cleanup_preserved=%s\n", cleanup_result == 5 && strcmp(saved, command_failure) == 0 ? "true" : "false");
    }
    return 0;
}
'''

# Independent Linux UAPI fixtures. '=' keeps host endianness with exact ABI widths;
# rule port/UID ranges are native endian, IP address payloads are network order.
FRA_DST, FRA_IIFNAME, FRA_PRIORITY, FRA_FWMARK, FRA_TABLE = 1, 3, 6, 10, 15
FRA_SUPPRESS_IFGROUP, FRA_SUPPRESS_PREFIXLEN = 13, 14
FRA_OIFNAME, FRA_PAD, FRA_L3MDEV, FRA_UID_RANGE = 17, 18, 19, 20
FRA_PROTOCOL, FRA_IP_PROTO, FRA_DPORT_RANGE = 21, 22, 24
FRA_DPORT_MASK = 29


def u32(value):
    return struct.pack("=I", value)


def attribute(kind, payload):
    length = len(payload) + 4
    return struct.pack("=HH", length, kind) + payload + bytes((-length) % 4)


def encode_rule(rule, message_type=32, message_flags=2):
    header = struct.pack("=BBBBBBBBI", rule["af"], rule.get("dst_len", 0), rule.get("src_len", 0),
                         rule.get("tos", 0), rule.get("header_table", 0), rule.get("res1", 0),
                         rule.get("res2", 0), rule.get("action", 1), rule.get("flags", 0))
    attributes = b"".join(attribute(kind, payload) for kind, payload in rule["attributes"])
    payload = header + attributes + rule.get("trailing", b"")
    return struct.pack("=IHHII", 16 + len(payload), message_type, message_flags, 0, 0) + payload


def fixture_rules(family):
    af = 2 if family == 4 else 10
    destination = ipaddress.ip_address("192.168.50.1" if family == 4 else "2001:db8::53").packed
    peer = ipaddress.ip_address("172.19.0.2" if family == 4 else "fdfe:dcba:9876::2").packed
    common = [(FRA_PRIORITY, u32(9000)), (FRA_TABLE, u32(42000))]
    business = {"af": af, "attributes": common + [(FRA_IIFNAME, b"lo\0"), (FRA_UID_RANGE, struct.pack("=II", 10002, 10002))]}
    internal = {"af": af, "dst_len": 32 if family == 4 else 128,
                "attributes": common + [(FRA_DST, peer)]}
    dns = [{"af": af, "dst_len": 32 if family == 4 else 128,
            "attributes": common + [(FRA_DST, destination), (FRA_IIFNAME, b"lo\0"),
                                    (FRA_UID_RANGE, struct.pack("=II", 10003, 10003)),
                                    (FRA_IP_PROTO, bytes([protocol])), (FRA_DPORT_RANGE, struct.pack("=HH", 53, 53))]}
           for protocol in (6, 17)]
    return [business, internal, *dns]


def set_attribute(rule, kind, payload):
    rule["attributes"] = [(key, value) for key, value in rule["attributes"] if key != kind]
    if payload is not None:
        rule["attributes"].append((kind, payload))


def mutate_fixture(family, scenario):
    rules = copy.deepcopy(fixture_rules(family))
    for rule in rules:
        # Kernel dumps use the 8-bit RT_TABLE_COMPAT marker for a large table;
        # the full table ID lives in FRA_TABLE. Older kernels may leave zero.
        rule["header_table"] = 252
    business, peer, tcp, udp = rules
    dns = (tcp, udp)
    if scenario == "valid_binary_table":
        pass
    elif scenario == "valid_legacy_zero_header_table":
        for rule in rules:
            rule["header_table"] = 0
    elif scenario == "valid_dns_full_port_mask":
        for rule in dns:
            set_attribute(rule, FRA_DPORT_MASK, struct.pack("=H", 0xffff))
    elif scenario == "valid_attribute_order":
        for rule in rules:
            rule["attributes"].reverse()
        rules.reverse()
    elif scenario == "valid_kernel_metadata":
        for rule in rules:
            rule["attributes"] += [(FRA_PROTOCOL, b"\x03"), (FRA_SUPPRESS_IFGROUP, u32(0xffffffff)),
                                   (FRA_SUPPRESS_PREFIXLEN, u32(0xffffffff)), (FRA_L3MDEV, b"\0"), (FRA_PAD, b"")]
    elif scenario == "valid_foreign_table":
        foreign = copy.deepcopy(business)
        set_attribute(foreign, FRA_TABLE, u32(49999))
        foreign["attributes"].append((0x7fff, b"unrelated-selector"))
        rules.insert(0, foreign)
    elif scenario == "missing_all" or scenario == "clean":
        rules = []
    elif scenario == "missing_dns":
        rules = rules[:2]
    elif scenario == "missing_dns_udp":
        rules.remove(udp)
    elif scenario == "missing_peer":
        rules.remove(peer)
    elif scenario == "duplicate_business":
        rules.append(copy.deepcopy(business))
    elif scenario == "duplicate_peer":
        rules.append(copy.deepcopy(peer))
    elif scenario == "wrong_uid":
        set_attribute(business, FRA_UID_RANGE, struct.pack("=II", 10004, 10004))
    elif scenario == "wide_uid":
        set_attribute(business, FRA_UID_RANGE, struct.pack("=II", 10002, 10003))
    elif scenario == "missing_uid":
        set_attribute(business, FRA_UID_RANGE, None)
    elif scenario == "wrong_iif":
        set_attribute(business, FRA_IIFNAME, b"wlan0\0")
    elif scenario == "missing_iif":
        set_attribute(business, FRA_IIFNAME, None)
    elif scenario == "unterminated_iif":
        set_attribute(business, FRA_IIFNAME, b"lo")
    elif scenario in ("wrong_dns", "wrong_peer"):
        target = tcp if scenario == "wrong_dns" else peer
        previous = next(value for kind, value in target["attributes"] if kind == FRA_DST)
        set_attribute(target, FRA_DST, previous[:-1] + bytes([previous[-1] + 1]))
    elif scenario == "wide_dns":
        for rule in dns:
            rule["dst_len"] = 24 if family == 4 else 64
    elif scenario == "wide_peer":
        peer["dst_len"] = 30 if family == 4 else 126
    elif scenario in ("peer_uid", "peer_iif", "peer_oif", "peer_mark"):
        kind, value = {"peer_uid": (FRA_UID_RANGE, struct.pack("=II", 10003, 10003)),
                       "peer_iif": (FRA_IIFNAME, b"lo\0"), "peer_oif": (FRA_OIFNAME, b"rrtest\0"),
                       "peer_mark": (FRA_FWMARK, u32(0x10065))}[scenario]
        set_attribute(peer, kind, value)
    elif scenario in ("dns_wide_port", "dns_wrong_port", "dns_no_port", "dns_no_protocol", "dns_whole_ip", "dns_wrong_protocol"):
        for rule in dns:
            if scenario in ("dns_no_port", "dns_whole_ip"):
                set_attribute(rule, FRA_DPORT_RANGE, None)
            if scenario in ("dns_no_protocol", "dns_whole_ip"):
                set_attribute(rule, FRA_IP_PROTO, None)
            if scenario == "dns_wide_port":
                set_attribute(rule, FRA_DPORT_RANGE, struct.pack("=HH", 1, 65535))
            if scenario == "dns_wrong_port":
                set_attribute(rule, FRA_DPORT_RANGE, struct.pack("=HH", 443, 443))
            if scenario == "dns_wrong_protocol":
                set_attribute(rule, FRA_IP_PROTO, b"\x01")
    elif scenario == "dns_network_endian_port":
        # Linux's fib_rule_port_range is host order, not the sockaddr port ABI.
        # This mutation must be distinct on either host endianness.
        set_attribute(tcp, FRA_DPORT_RANGE, struct.pack("=HH", 53 << 8, 53 << 8))
    elif scenario == "dns_partial_port_mask":
        set_attribute(tcp, FRA_DPORT_MASK, struct.pack("=H", 0xff00))
    elif scenario == "dns_mask_without_port":
        set_attribute(tcp, FRA_DPORT_RANGE, None)
        set_attribute(tcp, FRA_DPORT_MASK, struct.pack("=H", 0xffff))
    elif scenario == "business_port_mask":
        set_attribute(business, FRA_DPORT_MASK, struct.pack("=H", 0xffff))
    elif scenario == "short_table_attribute":
        set_attribute(business, FRA_TABLE, b"\x10")
    elif scenario == "short_uid_attribute":
        set_attribute(business, FRA_UID_RANGE, u32(10002))
    elif scenario == "short_port_attribute":
        set_attribute(tcp, FRA_DPORT_RANGE, b"\x35")
    elif scenario == "duplicate_attribute":
        business["attributes"].append((FRA_TABLE, u32(42000)))
    elif scenario == "unknown_selector":
        business["attributes"].append((0x7fff, b"unknown"))
    elif scenario == "active_suppress_prefix":
        business["attributes"].append((FRA_SUPPRESS_PREFIXLEN, u32(0)))
    elif scenario == "active_l3mdev":
        business["attributes"].append((FRA_L3MDEV, b"\x01"))
    elif scenario == "wrong_priority":
        set_attribute(business, FRA_PRIORITY, u32(9001))
    elif scenario == "wrong_family":
        business["af"] = 10 if family == 4 else 2
    elif scenario in ("source_prefix", "tos", "flags", "wrong_action", "reserved_header", "conflicting_header_table"):
        key, value = {"source_prefix": ("src_len", 8), "tos": ("tos", 4), "flags": ("flags", 1),
                      "wrong_action": ("action", 6), "reserved_header": ("res1", 1),
                      "conflicting_header_table": ("header_table", 254)}[scenario]
        business[key] = value
    elif scenario == "foreign_only":
        for rule in rules:
            set_attribute(rule, FRA_TABLE, u32(49999))
    elif scenario == "trailing_attribute_byte":
        business["trailing"] = b"\x01"
    else:
        raise AssertionError(scenario)
    return b"".join(encode_rule(rule) for rule in rules)


def main():
    compiler = shutil.which("gcc")
    if compiler is None:
        raise SystemExit("gcc is required for native Root compatibility checks")
    checks = []
    with tempfile.TemporaryDirectory(prefix="rrbox-root-compat-") as temporary:
        work = Path(temporary)
        shim = work / "ip"
        shim.write_text(SHIM)
        shim.chmod(0o755)
        source = work / "compat.c"
        source.write_text(HARNESS)
        binary = work / "compat"
        subprocess.run([
            compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2",
            f'-DRRBOX_IP_PATH="{shim}"', "-I", str(ROOT / "native"),
            str(source), "-o", str(binary),
        ], check=True)

        def check_reserve(scenario, expected=True):
            state = work / scenario
            state.mkdir()
            env = dict(os.environ, RRBOX_COMPAT_STATE=str(state), RRBOX_COMPAT_SCENARIO=scenario)
            error_stages = {"global_query_error": "read_ipv4_rules",
                            "filtered_query_error": "check_ipv4_table_rules",
                            "route_query_error": "check_ipv4_table_routes"}
            if scenario in error_stages:
                env["RRBOX_COMPAT_CHECK_CLEANUP"] = "1"
            result = subprocess.run([str(binary), "reserve", "4", "present"],
                                    env=env, capture_output=True, text=True, check=True, timeout=20)
            lines = result.stdout.splitlines()
            actual, table = lines[0].split()
            assert actual == str(expected).lower(), (scenario, result.stdout, result.stderr)
            commands = [json.loads(line) for line in (state / "commands.jsonl").read_text().splitlines()]
            assert commands and all(not set(command) & {"-N", "ipproto", "dport"} for command in commands), (scenario, commands)
            if scenario in ("reserve_alias_ipv4", "reserve_alias_ipv6", "reserve_route_occupied"):
                assert table != (state / "first_table").read_text(), (scenario, table, commands)
            if scenario in error_stages:
                assert lines[1] == "stage=" + error_stages[scenario], lines
                assert "ip_exit=2 argv=-4 " in lines[2] and "Permission denied" in lines[2], lines
                assert len(lines[2]) < 1040 and all(32 <= ord(char) <= 126 for char in lines[2]), lines
                assert lines[3] == "cleanup_preserved=true", lines
            if scenario == "reserve_missing_fib":
                assert lines[2] == "diagnostic=", lines
            checks.append(scenario)

        def check_fixture(family, scenario, present=True, expected=False, payload=None):
            state = work / f"{family}-{scenario}-{'present' if present else 'absent'}"
            state.mkdir()
            fixture = state / "snapshot.bin"
            fixture.write_bytes(mutate_fixture(family, scenario) if payload is None else payload)
            env = dict(os.environ, RRBOX_COMPAT_STATE=str(state), RRBOX_COMPAT_SCENARIO=scenario)
            result = subprocess.run([str(binary), "fixture_present" if present else "fixture_absent", str(family), str(fixture)],
                                    env=env, capture_output=True, text=True, check=True, timeout=5)
            assert result.stdout.strip() == str(expected).lower(), (scenario, family, result.stdout, result.stderr)
            assert not (state / "commands.jsonl").exists(), "Binary rule validation must not depend on ip output"
            checks.append(f"{scenario}_ipv{family}_{'present' if present else 'absent'}")

        for scenario in ("reserve_empty", "reserve_alias_ipv4", "reserve_alias_ipv6",
                         "reserve_route_occupied", "reserve_missing_fib"):
            check_reserve(scenario)
        for scenario in ("foreign_priority", "global_query_error", "filtered_query_error", "route_query_error"):
            check_reserve(scenario, expected=False)
        for family in (4, 6):
            for scenario in ("valid_binary_table", "valid_legacy_zero_header_table", "valid_dns_full_port_mask", "valid_attribute_order", "valid_kernel_metadata", "valid_foreign_table"):
                check_fixture(family, scenario, expected=True)
            for scenario in (
                "wrong_uid", "wide_uid", "missing_uid", "wrong_iif", "missing_iif", "unterminated_iif",
                "wrong_dns", "missing_all", "missing_dns", "duplicate_business", "wrong_peer", "wide_peer",
                "missing_peer", "duplicate_peer", "peer_uid", "peer_iif", "peer_oif", "peer_mark", "wide_dns",
                "missing_dns_udp", "dns_wide_port", "dns_wrong_port", "dns_no_port", "dns_no_protocol",
                "dns_whole_ip", "dns_wrong_protocol", "dns_network_endian_port", "dns_partial_port_mask",
                "dns_mask_without_port", "business_port_mask", "short_table_attribute",
                "short_uid_attribute", "short_port_attribute", "duplicate_attribute", "unknown_selector",
                "active_suppress_prefix", "active_l3mdev", "wrong_priority", "wrong_family", "source_prefix",
                "tos", "flags", "wrong_action", "reserved_header", "conflicting_header_table", "foreign_only",
                "trailing_attribute_byte",
            ):
                check_fixture(family, scenario)
            check_fixture(family, "valid_binary_table", present=False)
            check_fixture(family, "clean", present=False, expected=True)
            check_fixture(family, "foreign_only", present=False, expected=True)
            baseline = mutate_fixture(family, "valid_binary_table")
            check_fixture(family, "truncated_message", payload=baseline[:-1])
            invalid_type = bytearray(baseline)
            struct.pack_into("=H", invalid_type, 4, 33)  # RTM_DELRULE is not a dump entry.
            check_fixture(family, "wrong_message_type", payload=bytes(invalid_type))
            for protocol in ("tcp", "udp"):
                result = subprocess.run([str(binary), "encode", str(family), protocol],
                                        capture_output=True, text=True, check=True, timeout=5)
                added, removed = (bytes.fromhex(line) for line in result.stdout.splitlines())
                rule = fixture_rules(family)[2 if protocol == "tcp" else 3]
                expected_add = encode_rule(rule, message_type=32, message_flags=1 | 4 | 0x200 | 0x400)
                expected_del = encode_rule(rule, message_type=33, message_flags=1 | 4)
                assert added == expected_add, (family, protocol, added.hex(), expected_add.hex())
                assert removed == expected_del, (family, protocol, removed.hex(), expected_del.hex())
                assert added[:4] + added[8:] == removed[:4] + removed[8:]
                checks.append(f"dns_encode_ipv{family}_{protocol}_independent_exact_add_delete_bytes")
        transport_binary = work / "transport"
        wrapped_functions = ("socket", "bind", "getsockname", "sendto", "recvmsg", "poll", "close")
        subprocess.run([
            compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2", "-I", str(ROOT / "native"),
            str(ROOT / "scripts" / "root-rule-check" / "transport_test.c"),
            *["-Wl,--wrap=" + name for name in wrapped_functions], "-o", str(transport_binary),
        ], check=True)
        transport_result = subprocess.run([str(transport_binary)], capture_output=True, text=True, check=True, timeout=10)
        transport = json.loads(transport_result.stdout)
        assert transport["passed"] is True and transport["network_mutations"] is False, transport
    report = {"checks": len(checks) + transport["checks"], "passed": checks, "native_transport": transport, "network_mutations": False,
              "fixture": "Old Android ip without -N/ipproto/dport; independent Linux ABI binary rule snapshots and request bytes"}
    reports = ROOT / "build-reports"
    reports.mkdir(exist_ok=True)
    (reports / "ROOT-COMMAND-COMPAT-REPORT.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
