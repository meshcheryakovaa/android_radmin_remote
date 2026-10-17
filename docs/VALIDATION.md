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
