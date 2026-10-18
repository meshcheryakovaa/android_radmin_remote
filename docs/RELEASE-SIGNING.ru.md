# Основная подпись и публикация

Основное приложение 0.4.2 продолжает использовать package `ru.meshcheryakov.androidradminremote`, versionCode 8 и отдельный постоянный release-сертификат. Старые debug/test APK имеют другие package и сертификаты: данные между установками автоматически не переносятся. Не удаляйте рабочую тестовую установку перед проверкой основной.

## Одна настройка GitHub

1. Откройте https://github.com/meshcheryakovaa/android_radmin_remote/settings/secrets/actions
2. New repository secret. Name: `ANDROID_RELEASE_SIGNING_B64`.
3. Value: всё содержимое переданного владельцу файла `ANDROID_RELEASE_SIGNING_B64.txt` (одна строка). Add secret. Этот файл содержит приватный ключ и пароль в закодированном архиве; он не должен попадать в репозиторий, issue, логи или публичные файлы.
4. Actions → Android APK → Run workflow → main. Либо следующий push в main запустит сборку.
5. После тестов и native smoke публикуется `v0.4.2`, asset `android-radmin-remote-0.4.2.apk`. CI сверяет SHA-256 сертификата с публичным файлом в исходниках и не публикует APK с другим ключом.

При отсутствии секрета CI явно пропускает публикацию основной версии. Автоматический новый release-ключ или debug-ключ вместо постоянного не генерируется.

## Резервная копия

Владелец получает отдельно `radmin-release-signing-backup.zip`: release.jks, signing.json, release-cert.der, ANDROID_RELEASE_SIGNING_B64.txt и инструкцию. Храните эту копию приватно; для будущих обновлений требуется тот же ключ. ZIP и Base64 сами по себе не шифруют входящий в них пароль. Keystore зашифрован своим паролем, который включён в резервный комплект.

Локальная сборка: создайте приватный properties-файл вне git с полями storeFile (абсолютный путь), storePassword, keyAlias, keyPassword. Задайте OPENRAD_SIGNING_PROPERTIES этим путём и запустите `./gradlew :app:assembleRelease --no-build-cache`. Результат app/build/outputs/apk/release/app-release.apk. Проверьте apksigner verify --print-certs и SHA-256 сертификата. Для release-варианта без signing properties Gradle может собрать unsigned APK; распространять его как основной нельзя.

Начиная с этой основной линии, следующие версии сохраняют package и сертификат и увеличивают versionCode. Не меняйте ключ для обычного обновления. Настройка подписи не добавляет Play Store, AUTO-балансировщик или Radmin Viewer.

Официальные справки: [GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets), [подпись Android-приложений](https://developer.android.com/studio/publish/app-signing).
