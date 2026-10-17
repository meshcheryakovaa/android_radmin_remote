# Android Radmin Remote

**Версия 0.2.0:** единый Android APK с локальным HTML-интерфейсом, OpenRad engine и встроенным Xray для **VLESS + Reality по TCP**.

Android 8.0+ (API 26). DIRECT, внешний SOCKS5 или импорт серверов из Xray/Happ JSON. Единственный VpnService создаёт TUN с маршрутом `26.0.0.0/8`, MTU 1500. Остальной интернет телефона через этот TUN не направляется.

## Скачать

[Релиз 0.2.0 debug](https://github.com/meshcheryakovaa/android_radmin_remote/releases/tag/v0.2.0-debug) · [app-debug.apk](https://github.com/meshcheryakovaa/android_radmin_remote/releases/download/v0.2.0-debug/app-debug.apk)

Это тестовая APK со встроенными библиотеками для arm64-v8a, armeabi-v7a, x86_64 и x86. При несовместимой подписи предыдущей debug-сборки Android потребует удалить старую установку; это удалит локальную identity и настройки. Production signing настраивается отдельно от исходников.

## Первый запуск VLESS / Reality

1. Установите APK. Отключите Happ и другие VPN-приложения.
2. Откройте **Proxy → Импорт из Happ / Xray**.
3. Вставьте экспортированный JSON и нажмите **Импортировать JSON**. Либо нажмите **Выбрать файл** и откройте `.json` / `.txt` до 512 КБ.
4. Выберите режим **VLESS + Reality · внутри APK**, затем нужный сервер из списка. Нажмите **Сохранить**.
5. На Главной нажмите **Подключиться**, разрешите Android VPN. Сначала запускается Xray и проверяется его внутренний SOCKS5-вход, затем OpenRad.
6. Дождитесь `CONNECTED`, адреса `26.x.x.x`, «TUN готов» и транспорта **VLESS / Reality**. В Proxy / диагностике Xray должен быть `READY`.
7. В **Сети** подключитесь к существующей Radmin VPN network. В **Устройствах** дождитесь peer `CONNECTED` и нажмите **PING**. В этом режиме ожидается `RELAY` с измеренным RTT.

Поддерживаются только outbounds `vless` + `reality` + `tcp` / `raw`. `gRPC`, балансировщик и правила маршрутизации Happ пока не импортируются. Приложение показывает количество импортированных TCP-профилей и пропущенных остальных записей. Повторный импорт заменяет список proxy-профилей, не затрагивая Radmin-сети и identity. Для изменения профиля сначала отключите VPN.

Импорт не выполняет исходную конфигурацию целиком: используются только проверенные поля выбранного TCP-профиля. Приложение создаёт собственный loopback SOCKS5 с непостоянным паролем, без дополнительного TUN и без отдельного Happ.

## DIRECT и внешний SOCKS5

Для DIRECT выберите соответствующий режим и подключитесь. Для внешнего SOCKS5 укажите сервер/порт и при необходимости логин/пароль. В SOCKS5 и Xray режимах OpenRad использует TCP relay; прямые peer TCP/UDP отключены. DIRECT сохраняет upstream-политику OpenRad: direct TCP/UDP по Wi-Fi, relay на остальных подключениях.

Кнопка «Проверить» для Xray оценивает уже установленную аутентифицированную control-сессию OpenRad. Она не считает один ответ локального SOCKS5 доказательством доступности VLESS-сервера. Полный критерий — `CONNECTED`, готовый TUN и PING до ПК. Для внешнего SOCKS5 проверяется CONNECT к координатору; сама проверка не выполняет вход Radmin.

## Сборка

JDK 17, Python 3, curl, Android SDK 34 / build-tools 34.0.0:

```bash
./scripts/setup-android-sdk.sh  # Linux, если SDK ещё нет
python3 scripts/setup-xray.py  # официальный AAR, SHA-256 проверяется
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-build-cache
```

Загрузка AAR также автоматически выполняется Gradle task `setupXray`. AAR не хранится в git; URL, версия и SHA-256 зафиксированы в скрипте. Android Studio открывает корень проекта; Windows использует Python 3, curl и `gradlew.bat`. Результат: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions собирает APK, запускает JVM-тесты и native smoke test на loopback, публикует versioned debug release и artifacts. DEX build cache отключён: при смене native classpath в AGP 8.5 локально наблюдались устаревшие архивы R-классов. Incremental/up-to-date проверки остаются доступны.

## Проверки и ограничения

Регистрация, вход в сеть, прямой TCP-канал и RTT уже были подтверждены пользователем на версии 0.1.1. Новая интеграция Xray проверяется синтетическими JVM-тестами, сборкой Android, проверкой HTML и запуском native Linux core с конфигурацией, сгенерированной тем же Kotlin-компилятором профилей. Это **не заменяет проверку VLESS/Reality на реальном Android и сервере провайдера**. Пользовательские серверы и данные доступа не используются в автоматических тестах.

Radmin Viewer / удалённый рабочий стол, VLESS gRPC, AUTO fallback и обновление подписки по URL в 0.2 не реализованы. Пароли proxy, UUID и параметры профилей сохраняются через Android Keystore / EncryptedSharedPreferences, не возвращаются в HTML и не попадают в экспорт диагностики. Поле исходного JSON очищается после успешного импорта.

## Документация и исходная база

- [Xray 0.2: архитектура, импорт, защита сокетов, приёмка](docs/XRAY-0.2.ru.md)
- [Историческая архитектура 0.1](docs/ARCHITECTURE.ru.md)
- [Ручная проверка Android ↔ Windows](docs/TESTING.ru.md)
- [Результаты автоматических проверок](docs/VALIDATION.md)
- [Нативные зависимости, версии и лицензии](docs/NATIVE-DEPENDENCIES.md)

Основа: [OpenRad](https://github.com/gringoestrangeiro/openrad) @ `47eb54f6b051aadc37260d405681b2111b50b7cc`, MIT. Протокол/криптография/identity/membership/L3Bridge сохранены. Встроенное ядро: [XTLS/libXray](https://github.com/XTLS/libXray) v26.10.10, API 3; Xray-core v26.10.10. Проект не связан с официальным Radmin или Happ.
