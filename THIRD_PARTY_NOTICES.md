# Third-party notices

RRBOX uses and/or distributes artifacts generated from the following upstream projects.

## SagerNet/sing-box

- Project: https://github.com/SagerNet/sing-box
- RRBOX build target: sing-box v1.14.0 / libbox
- Upstream license: GNU General Public License, version 3 or later, with the additional naming/association notice contained in the upstream `LICENSE` file.
- RRBOX does not claim affiliation with or endorsement by SagerNet.

## SagerNet/sing-geosite

- Project: https://github.com/SagerNet/sing-geosite
- RRBOX bundled/generated artifacts: `geosite-geolocation-cn.srs`
- Upstream license: GNU General Public License, version 3 or later.

## SagerNet/sing-geoip

- Project: https://github.com/SagerNet/sing-geoip
- RRBOX bundled/generated artifact: `geoip-cn.srs`
- Upstream license: GNU General Public License, version 3 or later.

Before publishing RRBOX as an open-source distribution, the project license and release packaging should be reviewed for compatibility with all bundled upstream code and generated rule-set artifacts. This notice is attribution and dependency documentation; it is not a substitute for the full upstream license texts.


## heiher/hev-socks5-tunnel

- Project: https://github.com/heiher/hev-socks5-tunnel
- Build commit: `64cc609f945253b0e9ebc56317d544268f3c68c1`
- License text is preserved in `app/src/main/assets/licenses/hev-socks5-tunnel-LICENSE.txt`.

## Other application dependencies

AndroidX / Jetpack Compose / Room / DataStore, Kotlin and coroutines, OkHttp, Gson, ZXing Embedded, and SnakeYAML are declared in `gradle/libs.versions.toml` and `app/build.gradle.kts`. Their respective upstream terms continue to apply. Release builds also bundle the checked-out sing-box license text; this attribution file does not grant a new license over RRBOX's own source.
