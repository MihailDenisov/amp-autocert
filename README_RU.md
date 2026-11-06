# amp-autocert

[English](README.md) · [Полное руководство](docs/PRODUCTION_SETUP_RU.md) · [Описание для CurseForge](curseforge.md)

amp-autocert — **серверный** мод NeoForge для Minecraft **1.21.1**, NeoForge **21.1.248**, Java **21** и AutoModpack **5.0.0-rc.1–rc.7** (`automodpack_mod`). Он получает и продлевает публично доверенные сертификаты Let's Encrypt для встроенного TLS-хоста AutoModpack через Cloudflare DNS-01. Предназначен для Pterodactyl и похожих панельных хостингов без Certbot/cron и без возможности использовать DNSSEC для отдельного метода доверия AMP1. Для выпуска сертификата DNSSEC не нужен. Cloudflare должен быть авторитетным DNS зоны; сертификат Cloudflare Origin CA не обеспечивает публичное доверие прямых клиентов Minecraft.

Версия `0.2.1` включает обнаружение/reconciliation и совместимый режим прав файлов на панельном хостинге; ограничения перечислены в [CHANGELOG](CHANGELOG.md).

Версия `0.2.1` поддерживает проверенные AutoModpack rc.1–rc.7. Оператор сообщил об успешном production-продлении на rc.7. Более поздние версии AutoModpack потребуют отдельной проверки.

> На одном эталонном сервере 29.09.2026 проверены реальный production-выпуск, очистка DNS-01, активация AutoModpack 5.0.0-rc.2 и подключение клиента. Каждый другой сервер требует собственной проверки. Новая установка выключена по умолчанию и требует подтверждённой оператором staging-валидации до production-выпуска.

## Адреса и ограничения

| Назначение | Пример | Где задаётся |
| --- | --- | --- |
| Адрес, который вводят игроки | `play.example.com` | `origin.hostname` в amp-autocert |
| SRV-цель Minecraft, если нужна | `node.example.com:25598` | DNS/Minecraft; не является именем сертификата |
| Endpoint AutoModpack | `packs.example.com:25809` | `advertised-endpoint-*` AutoModpack; мод обнаруживает сам |
| DNS SAN сертификата | `example.com`, `*.example.com` | Выводятся из `cloudflare.zoneName` |
| Имя DNS-01 записи | `_acme-challenge.example.com` | Выводится автоматически |

Пара apex/wildcard покрывает apex и ровно один уровень поддомена. Адрес игроков и hostname endpoint'а AutoModpack должны попадать под SAN; IP, более глубокие имена и внешнее завершение TLS не поддерживаются. Порты определяют маршрут, но не имя сертификата. AutoModpack должен иметь `modpack-host: true` и `disable-internal-tls: false`; выделите и откройте фактические порты Minecraft и AutoModpack в панели. A/AAAA-записи для прямого подключения держите в Cloudflare в режиме **DNS only**. См. [сетевые настройки AutoModpack](https://moddedmc.wiki/en/project/automodpack/latest/docs/configuration/server-config).

## Краткая настройка

1. Установите поддерживаемый AutoModpack и JAR amp-autocert на выделенный сервер. Активируйте зону Cloudflare и настройте NS у регистратора на назначенные ей nameserver'ы. Создайте токен с `Zone / DNS / Edit` только для этой зоны.
2. Скопируйте [пример properties](config/amp-autocert.properties.example) в `<корень-сервера>/automodpack/amp-autocert.properties`. Заполните `acme.email`, `origin.hostname`, `cloudflare.zoneName`, `cloudflare.zoneId`. Токен держите только в `AMP_AUTOCERT_CF_API_TOKEN` или закрытом файле `automodpack/credentials/amp-autocert-cloudflare-token.txt`.
3. Исключите `automodpack/credentials/**`, `automodpack/amp-autocert.properties` и `automodpack/amp-autocert-state/**` из правил выдачи клиентам **каждой** группы AutoModpack. Перед production-операциями приватно скопируйте целиком директории credentials и enrollment-state.
4. Запустите с `enabled=false` и выполните `/ampautocert doctor`. Он читает локальные и Cloudflare/DNS данные, но не создаёт ACME order или DNS-записи. Отсутствие baseline до первого enrollment ожидаемо.
5. Когда будете готовы, установите `enabled=true`, `acme.environment=production`, `host.restartAfterRenew=true` и перезапустите сервер. Выполните `/ampautocert reconcile confirm`: полный preflight и реальная **staging** DNS-01 проверка Let's Encrypt. Команда очищает свои TXT, отбрасывает staging-материал и принимает baseline только при успехе. Сама она не выпускает production-сертификат.
6. Выполните `/ampautocert check` для обычного решения о сроке; `/ampautocert renew` используйте лишь для намеренной досрочной production-ротации. Проверьте `/ampautocert status`, SAN сертификата и реальное подключение клиента.

При обычном продлении staging не повторяется. Изменение origin, зоны, endpoint'а, режима/порта или владельца TLS блокирует выпуск и развёртывание до новой успешной reconciliation. При ошибках проверок действующая пара сохраняется; работающий сертификат не вращается лишь из-за отсутствия нового baseline. Старый ручной fingerprint-pin клиента иногда требуется удалить один раз. [Полное руководство](docs/PRODUCTION_SETUP_RU.md) объясняет все поля, секреты, миграцию, резервное копирование, восстановление и диагностику ([English](docs/PRODUCTION_SETUP.md)).

## Команды

Для команд нужен уровень прав 3; сетевые и сертификатные операции идут на фоновом worker'е.

| Команда | Действие |
| --- | --- |
| `/ampautocert help` или `/ampautocert` | Краткий список команд. |
| `/ampautocert status` | Кэшированные среда, срок/SAN, результат, cooldown и безопасные имена полей drift. |
| `/ampautocert doctor` | Диагностика только чтением; не подтверждает право записи DNS и доступность endpoint'а клиенту. |
| `/ampautocert reconcile` | Только пояснение. |
| `/ampautocert reconcile confirm` | Полный preflight, staging-выпуск/очистка, затем принятие baseline; staging не развёртывается. |
| `/ampautocert check` | Немедленная обычная проверка без форсирования досрочного выпуска. |
| `/ampautocert renew` | Намеренное досрочное production-продление с соблюдением всех барьеров и cooldown. |
| `/ampautocert staging-issue` | Необязательная отдельная staging-проба только при `enabled=true`, среде `staging` и принятом baseline. |

## Сборка

Установите JDK 21 и выполните `./gradlew test` и `./gradlew build` (Windows: `gradlew.bat`); JAR появится в `build/libs/`. Первой сборке нужен интернет для Gradle/NeoForge. Java-проверка в POSIX shell: `sh scripts/offline-smoke-test.sh`. В репозитории нет production-секретов, состояния аккаунта или сертификатов. См. [лицензии зависимостей](docs/THIRD_PARTY.md).

Источники: [безопасность AutoModpack](https://moddedmc.wiki/en/project/automodpack/latest/docs/security), [конфигурация AutoModpack](https://moddedmc.wiki/en/project/automodpack/latest/docs/configuration/server-config), [DNS-01 Let's Encrypt](https://letsencrypt.org/docs/challenge-types/), [staging Let's Encrypt](https://letsencrypt.org/docs/staging-environment/), [токены Cloudflare](https://developers.cloudflare.com/fundamentals/api/get-started/create-token/).
