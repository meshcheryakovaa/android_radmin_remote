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
