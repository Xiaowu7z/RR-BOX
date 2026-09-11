#!/usr/bin/env python3
"""Run production HEV JNI and scheduler against a real JVM owner callback.

The TUN entry point is replaced by two real HEV tasks. This checks JNI registration,
actual callback stack ownership, exceptions, disabled routing, 64-bit route tokens,
and repeated native start/stop. Android Binder and ART still require device testing.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def run(command, **kwargs):
    result = subprocess.run([str(x) for x in command], capture_output=True, text=True,
                            timeout=90, **kwargs)
    if result.returncode:
        raise RuntimeError("Command failed (%d): %s\n%s\n%s" %
                           (result.returncode, " ".join(map(str, command)), result.stdout, result.stderr))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--hev-source", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    root = args.hev_source.resolve()
    java_home = args.java_home or Path(shutil.which("javac") or shutil.which("java")).resolve().parent.parent
    fixture = Path(__file__).resolve().with_name("hev-jni-test")
    task = root / "third-part/hev-task-system"
    lwip = root / "third-part/lwip"
    report = {"suite": "HEV real JVM JNI owner routing", "passed": False,
              "limitations": ["OpenJDK host, not Android ART or ConnectivityManager Binder",
                              "Real HEV scheduler; TUN entry point replaced by controlled tasks"]}
    try:
        with tempfile.TemporaryDirectory(prefix="rrbox-hev-jni-") as temp:
            build = Path(temp)
            run(["make", "-C", task, "shared"])
            run(["make", "-C", lwip, "shared"])
            sources = [root / "src/hev-jni.c", root / "src/rr-app-routing.c",
                       fixture / "owner-jni-fixture.c"]
            dispatcher = root / "src/rr-jni-owner-dispatch.c"
            if dispatcher.exists():
                sources.append(dispatcher)
            library = build / "librrbox-hev-jni-test.so"
            includes = [java_home / "include", java_home / "include/linux", root / "src",
                        root / "src/misc", root / "src/core/include", task / "include",
                        lwip / "src/include", lwip / "src/ports/include"]
            command = ["cc", "-std=gnu11", "-shared", "-fPIC", "-g", "-O1", "-DANDROID",
                       "-DPKGNAME=com/rr/client/vpn", "-DCLSNAME=HevTunnelNative"]
            command += ["-I" + str(directory) for directory in includes]
            command += sources + ["-L" + str(task / "bin"), "-lhev-task-system",
                                   "-L" + str(lwip / "bin"), "-llwip", "-pthread", "-o", library]
            run(command)
            run([java_home / "bin/javac", "-d", build, fixture / "HevTunnelNative.java"])
            env = os.environ.copy()
            env["LD_LIBRARY_PATH"] = ":".join([str(task / "bin"), str(lwip / "bin"),
                                                env.get("LD_LIBRARY_PATH", "")])
            result = run([java_home / "bin/java", "-Xcheck:jni", "-XX:ErrorFile=" + str(build / "jvm-crash.log"),
                          "-cp", build, "com.rr.client.vpn.HevTunnelNative", library], env=env, cwd=build)
            if "RRBOX_HEV_JNI_OK" not in result.stdout:
                raise RuntimeError("Missing successful JNI test marker: " + result.stdout + result.stderr)
            if "RRBOX_HEV_JNI_SLOW_OK" not in result.stdout:
                raise RuntimeError("Missing successful timeout/stop/stale-runtime test marker")
            if not dispatcher.is_file():
                raise RuntimeError("Production owner dispatcher source is missing")
            scheduler_sources = [task / "src/kern/task/hev-task.c", task / "src/kern/core/hev-task-system.c"]
            report.update(passed=True, result=result.stdout.strip(),
                          checks=["production JNI_OnLoad and native method registration",
                                  "original TCP/UDP tuple and 64-bit UID/generation/outlet token",
                                  "owner Java callback executes inside pthread stack bounds",
                                  "actual HEV tasks execute outside pthread stack bounds",
                                  "Java exceptions reject flow and preserve future callbacks",
                                  "disabled routing performs no Java owner lookup",
                                  "24 baseline start/stop cycles",
                                  "blocked owner lookup times out",
                                  "stop wakes worker without waiting for blocked owner",
                                  "restart while old owner is busy rejects without more callbacks",
                                  "late old-runtime response cannot select a new-runtime outlet",
                                  "dispatcher recovers when old callback finishes"],
                          hev_task_system_commit=run(["git", "-C", task, "rev-parse", "HEAD"]).stdout.strip(),
                          scheduler_sources={str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                                             for path in scheduler_sources},
                          fixture_sources={path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                                           for path in (fixture / "HevTunnelNative.java", fixture / "owner-jni-fixture.c")},
                          production_sources={str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                                              for path in sources if path.is_relative_to(root)})
    except Exception as error:
        report["error"] = str(error)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
