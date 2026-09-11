#!/usr/bin/env python3
"""Apply RRBOX original-owner routing to the fixed HEV/lwIP sources, or fail closed."""
import argparse
import hashlib
from pathlib import Path
import subprocess

PINNED = {
    ".": "64cc609f945253b0e9ebc56317d544268f3c68c1",
    "third-part/lwip": "2a11c14c7a32887af25a034e82ef18b0b12076ac",
    "src/core": "162dd996299fc2d2bff2dd63728f8a2cd71ed31a",
    "third-part/hev-task-system": "328f35d903221b51811b3d02b277d665dfbdc75f",
    "third-part/yaml": "efa36117a8646d26d12b58e05bac472d7854a70d",
}
PREIMAGES = {'src/hev-jni.c': 'e2776271f4d5f14a7493c956dc31f70a9e259f3e72c1e265c6388fb4ca9a8c0f', 'src/hev-socks5-session-udp.c': 'bb79b9e7a7b0fb5eb493728d4f7590cfa1892da9b735fc6fefb0a6c47f818b72', 'src/hev-socks5-session.c': '32bb327bb8c9f2554ce3c55abce019bdc8a570dfd26d0cb669aee5d295a165ad', 'src/hev-socks5-session.h': '8ebe9692bae3fc475b551dd825b77f823bfcca50efbd6556af10bf8afff9ce7e', 'src/hev-socks5-tunnel.c': '94e828202a3b5947734bc83aad6024f7f818453f9ff347d09ca124c82fbb12c1', 'src/rr-app-routing.c': None, 'src/rr-app-routing.h': None, 'src/rr-jni-owner-dispatch.c': None, 'src/rr-jni-owner-dispatch.h': None, 'third-part/lwip/src/core/udp.c': 'f346e7735110901304d2bd2ce2ac8077177d1048f05ce22e4f7d37e9d5910402', 'third-part/lwip/src/include/lwip/udp.h': '9d30d0e602ddcad375318ed218bdf0c11d612e8b29d8f1ba2f4438a6ace64112'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    args = parser.parse_args()
    checkout = args.checkout.resolve()
    patch = Path(__file__).resolve().parent / "patches/hev-app-routing.patch"
    for folder, expected in PINNED.items():
        actual = subprocess.check_output(["git", "-C", str(checkout / folder), "rev-parse", "HEAD"], text=True).strip()
        if actual != expected:
            raise SystemExit("HEV routing patch refused: unexpected pinned revision for " + folder)
    for name, expected in PREIMAGES.items():
        path = checkout / name
        actual = hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None
        if actual != expected:
            raise SystemExit("HEV routing patch refused: unexpected source content for " + name)
    command = ["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(patch)]
    subprocess.run(command + ["--dry-run"], cwd=checkout, check=True)
    subprocess.run(command, cwd=checkout, check=True)
    print("HEV application routing patch applied; SHA256=" + hashlib.sha256(patch.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
