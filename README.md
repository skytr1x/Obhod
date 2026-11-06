# Obhod - обход блокировок с телефона

Obhod - гибрид [tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android) и [zapret](https://github.com/flowseal/zapret-discord-youtube) для телефона.

Для обхода блокировки телеграм через MTProxy используеться Rust подход из андроид версии tg-ws-proxy указаной выше. Когда как для обход других блокировок проходит через систему fake TLS от Zapret.

В приложении можно выбрать Android-вариант профиля `General`, `ALT`, `Simple Fake`
или `Split SNI`. Профиль применяется к выбранным доменам через Android VPN;
список тактик и ограничения переноса с Windows описаны в
[`docs/zapret-android-mapping.md`](docs/zapret-android-mapping.md).
