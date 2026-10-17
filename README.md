# Android Radmin Remote

Единый Android APK: локальный **HTML/CSS/JS интерфейс → Kotlin bridge → существующий OpenRad engine → Direct / SOCKS5 → Radmin VPN**.

Прототип 0.1.1, Android 8.0+ (API 26), JDK 17. Основа — [OpenRad](https://github.com/gringoestrangeiro/openrad), MIT, зафиксирован commit `47eb54f6b051aadc37260d405681b2111b50b7cc`. Проект не связан с официальным Radmin.

## Что реализовано

- Шесть экранов: главная, сети, устройства, proxy, настройки, диагностика.
- Реальный OpenRad engine: получение identity и 26.x IP, публичные и приватные сети, peers, authenticated RTT.
- DIRECT или SOCKS5 CONNECT; SOCKS5 применяется ко всем исходящим TCP OpenRad, включая регистрацию, control, peer coordination и TCP relay.
- В proxy-режиме peer-данные идут через TCP relay; direct TCP/UDP отключены, обхода proxy нет.
- Один foreground VpnService, TUN 26.0.0.0/8, MTU 1500. Остальной интернет не попадает в этот TUN.
- Защищённый WebMessage bridge, зашифрованные credentials, export TXT, GitHub Actions для APK.

VLESS/Reality и AUTO fallback **не реализованы**. Это VPN transport MVP, без Radmin Viewer / удалённого рабочего стола. Работу с реальной Windows-сетью нужно подтвердить на телефоне; мок-режим launcher не использует.

## Скачать APK

После успешной сборки APK доступен напрямую в [Releases → 0.1.1 debug](https://github.com/meshcheryakovaa/android_radmin_remote/releases/tag/v0.1.1-debug).

Также откройте [Actions → Android APK](https://github.com/meshcheryakovaa/android_radmin_remote/actions/workflows/android.yml), выберите успешный запуск для `main` и скачайте artifact **android-radmin-remote-debug**. Распакуйте ZIP и установите `app-debug.apk`. Для загрузки artifacts GitHub обычно требует вход в аккаунт. Debug APK подписывается тестовым ключом; production signing хранится отдельно от репозитория.

## Собрать локально

```bash
# JDK 17; Android SDK platform 34 + build-tools 34.0.0
./scripts/setup-android-sdk.sh  # Linux: если SDK ещё нет
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Windows: откройте корень проекта в Android Studio, установите SDK 34 либо используйте `gradlew.bat` с теми же задачами. APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Первый запуск

1. Установите APK. При необходимости обновите Android System WebView.
2. В «Настройки» укажите имя устройства.
3. Для прямого подключения оставьте DIRECT; для proxy откройте «Proxy», выберите PROXY · SOCKS5, укажите сервер/порт и при необходимости логин/пароль. Нажмите «Сохранить».
4. «Проверить» проверяет TCP CONNECT к координатору OpenRad. Это ещё не успешная аутентификация Radmin.
5. На главной нажмите «Подключиться» и разрешите Android VPN. Дождитесь статуса CONNECTED, 26.x.x.x и «TUN готов».
6. В «Сети» введите имя существующей сети, для приватной отметьте флаг и укажите пароль; подключитесь. Итог операции виден в списке и диагностике, некоторые сети требуют одобрения.
7. В «Устройства» дождитесь CONNECTED и проверьте PING. Затем откройте нужный сервис узла по 26.x IP обычным Android-клиентом (например SMB/RDP-клиентом, если сервис настроен на Windows).
8. Для смены proxy сначала отключитесь. После сохранения нового режима подключитесь снова.

Прокси должен разрешать Radmin endpoints/ports и TCP relay. SOCKS5 сам по себе не гарантирует обход сетевых ограничений и не заменяет VLESS/Reality.

## Документация

- [Точная архитектура, маршрутизация и изменения](docs/ARCHITECTURE.ru.md)
- [Проверка на Android и Windows](docs/TESTING.ru.md)
- [Исходное техническое задание](docs/REQUEST.ru.txt)
- [Состояние проверок](docs/VALIDATION.md)
- [MIT LICENSE и авторство OpenRad](LICENSE)

Сохранённые Compose-экраны, тестовые mocks и upstream guides служат reference-кодом. MainActivity запускает WebView и EngineRepository; mock-данные пользователю не показываются. Upstream `AGENTS.md` описывает оригинальный монорепозиторий; в этом проекте корень соответствует его `clients/android/`.
