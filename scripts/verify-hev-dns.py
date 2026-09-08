#!/usr/bin/env python3
"""Verify the HEV resolver endpoint with production JSON and the pinned sing-box core.

All network traffic stays on loopback: only test DNS transports and outbound observers change.
The endpoint, DNS policy and route order are read from the JVM-exported production fixtures.
This tests the SOCKS -> core -> DNS hop; it does not claim Android TUN or device validation.
"""

import argparse
import importlib.util
import json
import pathlib
import struct
import subprocess


def load_helpers():
    path = pathlib.Path(__file__).with_name("verify-routing.py")
    spec = importlib.util.spec_from_file_location("rr_routing_checks", path)
    helpers = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helpers)
    return helpers


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sing-box", required=True, type=pathlib.Path)
    parser.add_argument("--fixtures", required=True, type=pathlib.Path)
    parser.add_argument("--report", required=True, type=pathlib.Path)
    args = parser.parse_args()
    helpers = load_helpers()
    binary = str(args.sing_box.resolve())
    version = subprocess.run([binary, "version"], capture_output=True, text=True, check=True, timeout=10)
    if f"sing-box version {helpers.CORE_VERSION}\n" not in version.stdout:
        raise AssertionError(f"Wrong native core version: {version.stdout}")
    manifest = json.loads((args.fixtures / "manifest.json").read_text())
    if len(manifest["variants"]) != 8:
        raise AssertionError("Expected every smart/DNS/fast combination")
    endpoint = manifest["dns_address"]
    yaml = (args.fixtures / "hev.yaml").read_text()
    if "mapdns:" in yaml or "100.64." in yaml:
        raise AssertionError("Native HEV must not create volatile synthetic destinations")
    checks = []
    output = args.report.parent.resolve()
    output.mkdir(parents=True, exist_ok=True)
    report = {
        "schema": 1, "core_version": helpers.CORE_VERSION, "core_commit": helpers.CORE_COMMIT,
        "core_sha256": helpers.sha256(args.sing_box),
        "scope": "production resolver rules over SOCKS; loopback DNS and outbound observers",
        "android_device_test": False, "native_hev_tun_test": False,
        "resolver_endpoint": endpoint, "checks": checks,
    }

    def check(name, actual, expected):
        checks.append({"name": name, "actual": actual, "expected": expected, "passed": actual == expected})
        if actual != expected:
            raise AssertionError(f"{name}: expected {expected!r}, got {actual!r}")

    error = None
    try:
        with helpers.environment() as (observers, dns_servers):
            for meta in manifest["variants"]:
                original = json.loads((args.fixtures / meta["file"]).read_text())
                port = helpers.available_port()
                config = helpers.instrument(original, port, observers, dns_servers)
                # A second inbound proves the new /32:53 rule is also scoped to HEV.
                control_port = helpers.available_port()
                config["inbounds"].append({
                    "type": "socks", "tag": "unrelated-inbound", "listen": "127.0.0.1", "listen_port": control_port
                })
                name = pathlib.Path(meta["file"]).stem
                with helpers.running_core(binary, config, output, name):
                    for host in ("www.wechat.com", "www.tiktok.com"):
                        expected = "223.5.5.5" if meta["smart"] and host == "www.wechat.com" else "1.1.1.1"
                        request = helpers.query(host)
                        for network in ("udp", "tcp"):
                            answer = helpers.udp_exchange(port, endpoint, 53, request) if network == "udp" else helpers.tcp_exchange(
                                port, endpoint, 53, struct.pack("!H", len(request)) + request, dns=True
                            )
                            check(f"{name}/{host}/{network}", helpers.parse_dns_answer(answer), expected)
                    # Unlike mapdns, HEV now forwards real IPs. After a DNS answer, an opaque
                    # business stream/datagram must still recover its domain through the core's
                    # reverse mapping. These fixtures have no GeoIP set to accidentally pass.
                    real_destination = "223.5.5.5" if meta["smart"] else "1.1.1.1"
                    for network in ("udp", "tcp"):
                        result = helpers.udp_exchange(port, real_destination, 443, b"opaque-business") if network == "udp" else helpers.tcp_exchange(
                            port, real_destination, 443, b"opaque-business"
                        )
                        check(f"{name}/real-ip-business/{network}", result.decode().strip(), "direct" if meta["smart"] else "proxy")
                    # Neither the old mapped range nor another port on the resolver is globally
                    # redirected to DNS. Opaque bytes deliberately avoid any protocol sniff match.
                    for host, target_port in ((endpoint, 443), ("100.64.0.9", 443)):
                        for network in ("udp", "tcp"):
                            result = helpers.udp_exchange(port, host, target_port, b"opaque-data") if network == "udp" else helpers.tcp_exchange(
                                port, host, target_port, b"opaque-data"
                            )
                            check(f"{name}/untouched-{host}-{target_port}/{network}", result.decode().strip(), "proxy")
                    if not meta["dns"]:
                        request = helpers.query("scope-check.invalid")
                        for network in ("udp", "tcp"):
                            for test_port, host in ((control_port, endpoint), (port, "192.0.2.53")):
                                result = helpers.udp_exchange(test_port, host, 53, request) if network == "udp" else helpers.tcp_exchange(
                                    test_port, host, 53, struct.pack("!H", len(request)) + request
                                )
                                check(f"{name}/scope-{test_port == port}-{host}/{network}", result.decode().strip(), "proxy")
    except Exception as failure:
        error = f"{type(failure).__name__}: {failure}"
        raise
    finally:
        report["check_count"] = len(checks)
        report["failure_count"] = sum(not item["passed"] for item in checks)
        report["error"] = error
        report["completed"] = error is None and len(checks) == 96 and report["failure_count"] == 0
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    if not report["completed"]:
        raise AssertionError("DNS matrix did not complete")
    print(f"HEV DNS: {len(checks)} native-core checks passed; Android TUN remains a device gate")


if __name__ == "__main__":
    main()
