# Проверка прототипа

## Автоматически

```bash
node --check app/src/main/assets/www/app.js
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Новые SOCKS5-тесты используют только loopback, фиксированные искусственные данные и локальный fake proxy. Upstream сохраняет синтетические protocol/crypto/engine/relay/L3Bridge regression tests. Автотесты не регистрируют устройства на настоящем Radmin сервере.

## Ручная приёмка (не выполнена на реальном Android в среде разработки)

Нужны Android 8+, Windows с официальным Radmin VPN и тестовой сетью, рабочий SOCKS5 с разрешёнными Radmin endpoints. Перед тестом убедитесь, что Android не удерживает always-on VPN другого приложения.

1. DIRECT: разрешить VPN, получить 26.x IP, TUN ready, вступить в тестовую сеть, увидеть Windows peer.
2. PING в приложении: дождаться RTT или явного timeout. Он измеряет authenticated OpenRad keepalive, а не системный ICMP.
3. Обратный ping с Windows к Android VIP; доступ с Android к тестовому TCP-сервису Windows по 26.x IP. Windows firewall должен разрешать нужный трафик.
4. Открыть обычный HTTPS-сайт на Android; проверить, что default route не ушёл в TUN.
5. Закрыть UI и убедиться, что foreground VPN продолжает работать. Отключить через notification.
6. SOCKS5: отключиться, сохранить настройки, протестировать CONNECT, подключиться. В диагностике SOCKS5; у peers RELAY. На SOCKS-сервере должны быть видны реальные CONNECT к Radmin control/relay endpoints.
7. Ограничить direct-доступ к Radmin, сохранив доступ к proxy. Проверить получение состояния и peer traffic. Это устанавливает, что proxy действительно используется, а не только отображается.
8. Неверный пароль SOCKS5, отказ CONNECT, недоступный proxy: VPN не должен незаметно переходить в DIRECT. Секретов в диагностике нет.
9. Отказать VPN consent: engine не должен стартовать. Отозвать consent в настройках Android: service/TUN должны остановиться.
10. Wi-Fi ↔ mobile, airplane mode, поворот экрана, reconnect, 10 циклов connect/disconnect: не должно появляться второго engine/VPN.
11. Приватная сеть: пароль не появляется после перезапуска и не попадает в экспорт диагностики.
12. Экспорт TXT, копирование diagnostics и IP. Попробовать внешнюю ссылку/iframe: bridge должен оставаться доступным только bundled main document.

Зафиксируйте Android/API, WebView version, Radmin Windows version, proxy implementation и результаты каждого пункта. До этого проект следует считать непроверенным на реальной инфраструктуре прототипом.

## Визуальная проверка в среде разработки

Выполнена проверка шести экранов в headless Chromium на ширине 390 px с синтетическим bridge fixture. Скриншоты главной и proxy-формы сохранены в `docs/screenshots/`. Это проверка HTML layout и безопасного отображения имён peer, не Android networking test.

## Дополнение 0.2: Xray

Полная приёмка Xray, импорта и защиты native сокетов описана в [XRAY-0.2.ru.md](XRAY-0.2.ru.md). Локальный native smoke test проверяет Linux core той же версии на loopback и не заменяет Android JNI/VPN/провайдерскую проверку.
