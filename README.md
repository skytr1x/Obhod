# Telegram Proxy для Android

Приложение запускает локальный MTProto-прокси на `127.0.0.1:1443`. Rust-библиотека
передаёт Telegram MTProto-трафик через WebSocket и резервные маршруты. VPN,
общесистемная фильтрация и Python runtime не используются.

В интерфейсе можно запустить или остановить прокси, скопировать его данные либо
открыть ссылку настройки Telegram. Секрет генерируется и хранится локально.
Прокси доступен Telegram на этом устройстве только пока работает фоновая служба.

Android-приложение включает Rust-библиотеку для `arm64-v8a` и `armeabi-v7a`.
Исходный Rust crate находится в `native/tgwsproxy`; сведения о лицензии — в
`third_party/tg-ws-proxy/LICENSE`.

Для сборки установите Rust targets командой
`rustup target add aarch64-linux-android armv7-linux-androideabi`, а также
Android NDK версии из `app/build.gradle.kts`. Затем запустите
`./gradlew :app:assembleDebug`. Задачи `buildTelegramRustArm64` и
`buildTelegramRustArm32` собирают библиотеку через Cargo; `preBuild` запускает
обе автоматически и передаёт результаты в Android packaging.
