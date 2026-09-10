#!/usr/bin/env python3
"""Run native policy inspection against an Android ip-compatible command fixture.

No root privileges, TUN device or host network mutations are needed. The fixture
rejects unsupported -N and deliberately prints table aliases, so an Ubuntu ip
installation cannot conceal Android command/lookup-name compatibility failures.
"""
import json
import os
from pathlib import Path
import shutil
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
if "-N" in args:
    print('Option "-N" is unknown, try "ip -help".', file=sys.stderr)
    sys.exit(255)
if len(args) < 3 or args[0] not in ("-4", "-6"):
    sys.exit(97)
family = args[0]
if scenario.startswith("dns_port_") and args[1:3] in (["rule", "add"], ["rule", "del"]):
    if scenario == "dns_port_unsupported":
        print('Error: argument "ipproto" is wrong: unsupported selector', file=sys.stderr)
        sys.exit(2)
    sys.exit(0)
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
if not filtered_rules:
    sys.exit(96)
if scenario == "verify_query_error":
    print("RTNETLINK answers: Permission denied", file=sys.stderr)
    sys.exit(2)
if scenario in ("verify_missing", "verify_clean"):
    sys.exit(0)
lookup = "42000" if scenario == "verify_numeric" else "rrbox_reserved"
peer = "172.19.0.2" if family == "-4" else "fdfe:dcba:9876::2"
peer_prefix = "/32" if family == "-4" else "/128"
if scenario == "verify_wrong_peer":
    peer = "172.19.0.3" if family == "-4" else "fdfe:dcba:9876::3"
if scenario == "verify_wide_peer":
    peer_prefix = "/30" if family == "-4" else "/126"
if scenario == "verify_peer_bare":
    peer_prefix = ""
peer_selector = {
    "verify_peer_uid": " uidrange 10003-10003",
    "verify_peer_iif": " iif lo",
    "verify_peer_oif": " oif rrtest",
    "verify_peer_mark": " fwmark 0x10065",
}.get(scenario, "")
peer_rule = f"9000:\tfrom all to {peer}{peer_prefix}{peer_selector} lookup {lookup}"
if scenario != "verify_missing_peer":
    print(peer_rule)
if scenario == "verify_duplicate_peer":
    print(peer_rule)
uid = "10004-10004" if scenario == "verify_wrong_uid" else "10002-10002"
iif = "wlan0" if scenario == "verify_wrong_iif" else "lo"
business = f"9000:\tfrom all iif {iif} uidrange {uid} lookup {lookup}"
print(business)
if scenario == "verify_duplicate":
    print(business)
if scenario == "verify_missing_dns":
    sys.exit(0)
destination = "192.168.50.1" if family == "-4" else "2001:db8::53"
if scenario == "verify_wrong_dns":
    destination = "192.168.50.2" if family == "-4" else "2001:db8::54"
prefix = "/32" if family == "-4" else "/128"
if scenario == "verify_wide_dns":
    prefix = "/24" if family == "-4" else "/64"
for protocol in ("tcp", "udp"):
    if scenario == "verify_missing_dns_udp" and protocol == "udp":
        continue
    selector = f" ipproto {protocol} dport 53"
    if scenario == "verify_dns_numeric":
        selector = f" ipproto {6 if protocol == 'tcp' else 17} dport 53-53"
    elif scenario == "verify_dns_wide_port":
        selector = f" ipproto {protocol} dport 1-65535"
    elif scenario == "verify_dns_wrong_port":
        selector = f" ipproto {protocol} dport 443"
    elif scenario == "verify_dns_no_port":
        selector = f" ipproto {protocol}"
    elif scenario == "verify_dns_no_protocol":
        selector = " dport 53"
    elif scenario == "verify_dns_whole_ip":
        selector = ""
    elif scenario == "verify_dns_wrong_protocol":
        selector = " ipproto icmp dport 53"
    print(f"9000:\tfrom all to {destination}{prefix} iif lo uidrange 10003-10003{selector} lookup {lookup}")
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
    if (strcmp(argv[1], "dns_rule") == 0) {
        int family = atoi(argv[2]);
        size_t selected = family == 4 ? 1 : 2;
        if (strcmp(argv[3], "udp") == 0) selected += 4;
        int added = change_rule(&current.ranges[selected], family, false);
        char diagnostic[sizeof(command_failure)];
        memcpy(diagnostic, command_failure, sizeof(diagnostic));
        int removed = change_rule(&current.ranges[selected], family, true);
        printf("added=%d removed=%d diagnostic=%s\n", added, removed, diagnostic);
        return 0;
    }
    bool result = strcmp(argv[1], "reserve") == 0 ? reserve_table() :
        verify_rules(atoi(argv[2]), strcmp(argv[3], "present") == 0);
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

        def check(scenario, action="verify", family=4, present=True, expected=True):
            state = work / f"{len(checks)}-{scenario}-{family}"
            state.mkdir()
            env = dict(os.environ, RRBOX_COMPAT_STATE=str(state), RRBOX_COMPAT_SCENARIO=scenario)
            error_stages = {"global_query_error": "read_ipv4_rules",
                            "filtered_query_error": "check_ipv4_table_rules",
                            "route_query_error": "check_ipv4_table_routes"}
            if scenario in error_stages:
                env["RRBOX_COMPAT_CHECK_CLEANUP"] = "1"
            result = subprocess.run([
                str(binary), action, str(family), "present" if present else "absent",
            ], env=env, capture_output=True, text=True, check=True, timeout=20)
            lines = result.stdout.splitlines()
            actual, table = lines[0].split()
            assert actual == str(expected).lower(), (scenario, family, result.stdout, result.stderr)
            commands = [json.loads(line) for line in (state / "commands.jsonl").read_text().splitlines()]
            assert commands and all("-N" not in command for command in commands), (scenario, commands)
            if action == "verify":
                assert commands == [[f"-{family}", "rule", "show", "table", "42000"]], commands
            if scenario in ("reserve_alias_ipv4", "reserve_alias_ipv6", "reserve_route_occupied"):
                assert table != (state / "first_table").read_text(), (scenario, table, commands)
            if scenario in error_stages:
                assert lines[1] == "stage=" + error_stages[scenario], lines
                assert "ip_exit=2 argv=-4 " in lines[2] and "Permission denied" in lines[2], lines
                assert len(lines[2]) < 1040 and all(32 <= ord(char) <= 126 for char in lines[2]), lines
                assert lines[3] == "cleanup_preserved=true", lines
            if scenario == "reserve_missing_fib":
                assert lines[2] == "diagnostic=", lines
            checks.append(f"{scenario}_ipv{family}_{'present' if present else 'absent'}")

        for scenario in ("reserve_empty", "reserve_alias_ipv4", "reserve_alias_ipv6",
                         "reserve_route_occupied", "reserve_missing_fib"):
            check(scenario, action="reserve")
        for scenario in ("foreign_priority", "global_query_error", "filtered_query_error", "route_query_error"):
            check(scenario, action="reserve", expected=False)
        for family in (4, 6):
            for scenario in ("verify_numeric", "verify_alias", "verify_peer_bare", "verify_dns_numeric"):
                check(scenario, family=family)
            for scenario in ("verify_wrong_uid", "verify_wrong_iif", "verify_wrong_dns",
                             "verify_missing", "verify_missing_dns", "verify_duplicate", "verify_query_error",
                             "verify_wrong_peer", "verify_wide_peer", "verify_missing_peer", "verify_duplicate_peer",
                             "verify_peer_uid", "verify_peer_iif", "verify_peer_oif", "verify_peer_mark", "verify_wide_dns",
                             "verify_missing_dns_udp", "verify_dns_wide_port", "verify_dns_wrong_port",
                             "verify_dns_no_port", "verify_dns_no_protocol", "verify_dns_whole_ip", "verify_dns_wrong_protocol"):
                check(scenario, family=family, expected=False)
            check("verify_alias", family=family, present=False, expected=False)
            check("verify_clean", family=family, present=False)
            check("verify_query_error", family=family, present=False, expected=False)
            for protocol in ("tcp", "udp"):
                for scenario in ("dns_port_supported", "dns_port_unsupported"):
                    state = work / f"{scenario}-{family}-{protocol}"
                    state.mkdir()
                    env = dict(os.environ, RRBOX_COMPAT_STATE=str(state), RRBOX_COMPAT_SCENARIO=scenario)
                    result = subprocess.run([str(binary), "dns_rule", str(family), protocol],
                                            env=env, capture_output=True, text=True, check=True, timeout=20)
                    commands = [json.loads(line) for line in (state / "commands.jsonl").read_text().splitlines()]
                    assert len(commands) == 2, (scenario, commands)
                    assert commands[0][2] == "add" and commands[1][2] == "del", commands
                    assert commands[0][:2] + commands[0][3:] == commands[1][:2] + commands[1][3:], commands
                    for command in commands:
                        assert command[command.index("ipproto") + 1] == protocol, command
                        assert command[command.index("dport") + 1] == "53", command
                    if scenario == "dns_port_supported":
                        assert "added=0 removed=0" in result.stdout, result.stdout
                    else:
                        assert "added=2 removed=2" in result.stdout and "unsupported selector" in result.stdout, result.stdout
                    checks.append(f"{scenario}_ipv{family}_{protocol}_exact_rollback_no_widening")
    report = {"checks": len(checks), "passed": checks, "network_mutations": False,
              "fixture": "Android-style ip without -N, numeric and aliased table output"}
    reports = ROOT / "build-reports"
    reports.mkdir(exist_ok=True)
    (reports / "ROOT-COMMAND-COMPAT-REPORT.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
