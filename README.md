# Obhod - обход блокировок для телефона

Требования:
- Android 11+
- Желательно выключеный Phantom Process Killer

---

Obhod - гибрид [tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android) и [zapret](https://github.com/flowseal/zapret-discord-youtube) для телефона.

Для обхода блокировки телеграм через MTProxy используеться Rust подход из андроид версии tg-ws-proxy указаной выше. Когда как для обход других блокировок проходит через систему fake TLS от Zapret.

## Важная информация

Обход не обещает полного обхода всех блокировок стабильно, но он способен обойти большинство блокировок в зависимости от доступности.
Также обход не работает на UDP запросы (как пример - звонки в Discord).

## Информация о проекте

- У проекта из официальных ресурсов только [Телеграм Канал](https://t.me/obhodDev) и [репозиторий](https://github.com/skytr1x/Obhod);
- Официальный APK файл есть только в [Releases](https://github.com/skytr1x/Obhod/releases/latest);
- У разработчика есть только [Телеграм канал](https://t.me/skytr1xDeveloper) о разработке как этого, так и других проектов.
