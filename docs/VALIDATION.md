# Validation

Upstream baseline: gringoestrangeiro/openrad @ 47eb54f6b051aadc37260d405681b2111b50b7cc.

Local build/test results are recorded here before publication. Real Android/Windows interoperability, VPN consent UI, actual SOCKS5 endpoint and carrier networks require the manual checklist in TESTING.ru.md. No real identity provisioning is performed by the synthetic test suite.

## Local results

- JDK 17.0.20, Gradle wrapper 8.7, Android SDK platform 34 / build-tools 34.0.0.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug`: **BUILD SUCCESSFUL**.
- 18 JUnit suites, 135 tests: **134 passed, 1 upstream skipped, 0 failures/errors**. Includes 8 SOCKS5 loopback regressions.
- `node --check app/src/main/assets/www/app.js`: passed.
- All Android resource/manifest XML parsed successfully.
- `apksigner verify app/build/outputs/apk/debug/app-debug.apk`: passed.
- Local APK SHA-256: `77fd094e09a9f27e0e0b342ff0acd9bcb3678a36a5310d42e004170f191f1c92`.
- Browser UI smoke check, Chromium headless at 390×844: six screens opened, no JavaScript page errors, no horizontal overflow. An HTML-like peer name rendered as text, without creating an image element.
- Screenshots in `docs/screenshots/` use an explicitly synthetic bridge fixture for layout inspection. They are not evidence of real VPN connectivity; production launcher only uses the native bridge.

The local APK is debug-signed with a local development key. CI signs with its own debug key, so a CI APK can have a different checksum/signature. Remove a locally signed test install before installing an APK from a different build host if Android reports a signing mismatch.

Not executed: real Android install, on-device WebView/VPN consent, Windows interoperability, real proxy endpoint, Wi-Fi/mobile handover or network restriction tests. These remain the manual acceptance checklist, not claimed passes.

## 0.1.1: Android socket descriptor fix

TCP now binds an ephemeral local port before VpnService.protect(), so Android has a native socket descriptor. Protection still occurs before connect, for both DIRECT and SOCKS5. Regressions check bound/unconnected state at protect and closure after rejection. Real on-device confirmation is pending.

0.1.1 local validation: testDebugUnitTest + assembleDebug succeeded; 135 passed, 1 skipped, zero failures/errors.

## 0.2.0: embedded Xray

- Official libXray Android v26.10.10 ZIP and extracted AAR SHA-256 verified. Removed the local AAR and verified Gradle automatically downloaded/verified the dependency again.
- `testDebugUnitTest` + `assembleDebug --no-build-cache`: **BUILD SUCCESSFUL**. 143 tests total: **142 passed, 1 skipped, zero failures/errors**. Includes 7 new synthetic profile-import/credential-projection/runtime-config tests.
- Official native Linux libXray from the same release: validated the exact Kotlin-generated synthetic Reality/TCP configuration; authenticated localhost SOCKS, rejected anonymous access, stopped/restarted twice and verified listener closure. No VLESS server connection was attempted. Both explicit-library and `--download` CI entry paths passed (verified ZIP cache for the latter).
- Browser smoke test, synthetic bridge: six screens; paste/import/select/save; source JSON field cleared; selection contains no auth UUID; import controls disabled during active session; 320px and 390px layouts without horizontal overflow or JavaScript page errors. Screenshots `v02-*-synthetic.png` are layout fixtures, not proof of real Xray connectivity.
- APK versionCode 3 / versionName 0.2.0, all four Go JNI ABIs present, a single VpnService declaration, MIT/MPL license texts packaged. `apksigner verify`: passed. Native libraries are compressed/extracted for a smaller universal APK.
- Local APK SHA-256: `daa24b5f5f29ee39fdd86964b75aa712fdedd1a62e1aea0a1af699c108e433ea`. CI debug signing can produce a different checksum/signature.

An AGP 8.5 DEX/R-class cache conflict occurred when adding the AAR to the existing classpath. A clean build without build cache passed; task build cache is now disabled for this project while ordinary up-to-date/incremental checks remain available.

Not executed: native Android JNI loading, on-device protect(fd)/DNS callbacks, document picker, user-provider Reality handshake, real Radmin relay through Xray and restricted mobile networks. These require the device checklist in XRAY-0.2.ru.md. No user subscription, credentials or real provider configuration is included in the source tree or automatic tests.


## 0.4.0 — Veyon classroom client

- JVM suite: 156 tests, 155 passed, 1 skipped; includes 7 Veyon wire/auth/frame/input tests and 3 native input-gate tests.
- Independent Qt_5_5 QVariant fixtures generated with PySide6 / Qt 6.12.0 (not bundled in the APK).
- Synthetic loopback key/logon peer verifies SHA-512 signature, RSA-OAEP password, raw/copyrect/streaming-zlib pixels, Veyon feature framing, pointer/key messages, malformed lengths/rectangles and no authentication downgrade.
- Playwright 1280/390/320px: network/search/online filters; 12 synthetic peers; 4 selected synthetic live images; view-only sends no input; explicit control; letterbox coordinates and keyboard; password clearing; no page errors or horizontal overflow.
- Signed release build and release lint passed locally. Real Windows Veyon interoperability still requires manual acceptance on a user-controlled PC; the Veyon screenshots are explicitly synthetic.


## 0.4.1 — sequential 30-PC thumbnail grid

- JVM loopback suite: 158 tests, 157 passed, 1 upstream skipped, zero failures/errors. New regressions verify RGB332 with streaming zlib, a single snapshot without a second framebuffer request, and Veyon ScreenLock start/stop without framebuffer negotiation. Feature commands match independent Qt_5_5 QDataStream fixtures.
- `scripts/test-classroom-ui.cjs`: 30 synthetic online PCs, every thumbnail obtained, maximum one concurrent thumbnail request, all 30 fit at 1280×800. No toolbar/headings/per-PC actions. Minimum refresh age respected. Viewing/controlling suspends the queue, returning closes the desktop before resuming. Slow in-flight image cancellation, view-only input gate, lock/unlock actions and persisted refresh/width/quality verified. No JS errors or horizontal overflow at 1280/1536/390/320px. Screenshots `v041-*-synthetic.png` are synthetic fixtures.
- Native snapshots have a 15s total deadline, a try-lock preventing a native backlog, epoch/engine/peer checks, and RGB332/zlib transport. Credentials remain encrypted; original frames are disposed after one JPEG. The width/quality settings affect the locally encoded thumbnail, not the original RFB server framebuffer resolution.
- User screenshot from 0.4.0 confirms a real Veyon image. The new 30-PC sweep and ScreenLock feature still require classroom acceptance on Windows. No real classroom connection is used by automated tests.


## 0.4.2 — 16:9 rows, classroom commands and lock refresh

- 163 JVM tests: 162 passed, 1 upstream skipped, zero failures/errors. Five new command regressions: independent Qt_5_5 power/website/file wire fixtures, binary streaming order, cancellation without finish, a bounded unknown-length stream, URL/name validation.
- Synthetic browser checks: 30 frames fit with exact 16:9 previews; 5-PC geometry grows; full-width row layout; all six batch actions target exactly the five filtered PCs, never hidden devices; per-peer failure and cancellation; single-PC lock keeps fetching actual image resources despite the ordinary refresh age. Viewer/control still pause all other snapshots. 1280/1536/390/320px, no runtime errors or horizontal overflow. `v042-*-synthetic.png` fixtures are synthetic, not real Windows action evidence.
- File selection uses Android SAF and a RAM-only URI opener, 64KiB binary chunks and a 200MiB cap; no Base64 payload in the WebView and no plaintext temporary file. Veyon provides no delivery/application acknowledgement. Status is SENT, not RECEIVED/APPLIED. No real PC is powered off, rebooted, blocked or sent data by tests.


## 1.0.0 — stable release and saved display selection

- User accepted the working 0.4.2 functionality and requested a stable release. Publish `v1.0.0` with `prerelease: false`, `make_latest: true`, permanent certificate, package unchanged, versionCode 9.
- Checkbox dropdown: select two PCs from thirty, search, select none/all, retain selection across document reload through the native preference boundary, exclude hidden PCs from thumbnails and a website batch. Existing 30-PC sequencing, 16:9 rows, 5-PC resize, per-target failure/cancellation, lock refresh and view/control pause checks pass at 1280/1536/390/320px without JS errors or overflow. Screenshots `v100-*-synthetic.png` are synthetic fixtures.
- `Remote.saveDisplay` stores only a bounded list of numeric RIDs and all/custom mode in encrypted preferences. It does not update credentials or the captured recipient list of a running batch. Existing JVM/wire tests are unchanged.
