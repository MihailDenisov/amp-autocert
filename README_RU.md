# amp-autocert

[English version](README.md)

**Серверный** мод для Minecraft **1.21.1** на **NeoForge 21.1.248**, сборка через **Java 21**. Мод управляет публичными сертификатами Let's Encrypt для AutoModpack через Cloudflare DNS-01 — без системного Certbot и cron.

> **Статус: PRODUCTION-КОНВЕЙЕР ПРОВЕРЕН.** 29.09.2026 оператор выполнил реальный production-выпуск Let's Encrypt для настроенной пары apex/wildcard, очистку DNS-01 записей Cloudflare, аварийно-безопасную замену учётных файлов и активацию через AutoModpack 5.0.0-rc.2. После перезапуска сертификат сохранился, `/ampautocert check` показал `VALID/not_due` с ожидаемыми SAN, а клиент подключился без ручного ввода fingerprint. Свежие установки по-прежнему по умолчанию выключены и работают в staging-режиме — следуйте production-руководству.

## Сборка и открытие в VS Code

1. Установите **JDK 21**. Для VS Code также установите расширения **Extension Pack for Java** и **Gradle for Java**.
2. Клонируйте репозиторий: `git clone https://github.com/MihailDenisov/amp-autocert.git`, затем откройте папку проекта.
3. Запустите `./gradlew test` (Windows: `gradlew.bat test`). При первом запуске лаунчер скачивает **официальный Gradle 8.14.3 wrapper JAR** и проверяет его опубликованный SHA-256; Gradle скачивает зависимости NeoForge. Для первой полной сборки нужен интернет. Проект намеренно не содержит непроверенный бинарный wrapper.
4. Офлайн-проверка на чистой Java в любой момент: `sh scripts/offline-smoke-test.sh` (нужны JDK 21 и POSIX shell). Сборка JAR мода: `./gradlew build`, результат появится в `build/libs/`.
5. В репозитории публикуется только сам мод. Рабочие файлы Codex/агентов (`AGENTS.md`, `CODEX_TASK.md`, `.codex/`, `.agents/`, `docs/IMPLEMENTATION_LOG.md`) и настройки редактора (`.vscode/`) намеренно исключены через `.gitignore` и не входят в публичный репозиторий.

## Пример топологии

Все домены и порты ниже — абстрактный пример; подставьте свои значения.

| Сервис | Имя / порт |
| --- | --- |
| Адрес для игроков | `play.mydomain.com` |
| DNS A-запись | `host.mydomain.com` -> реальный публичный IP, Cloudflare в режиме DNS only |
| Minecraft SRV | `_minecraft._tcp.play.mydomain.com` -> `host.mydomain.com:25565` |
| Endpoint AutoModpack | `host.mydomain.com:25570` |
| Запрашиваемые SAN Let's Encrypt | `mydomain.com`, `*.mydomain.com` |
| DNS-валидация | Cloudflare API, DNS-01; DNSSEC не требуется |

Cloudflare **Origin CA не подходит** для прямого подключения клиентов Minecraft; используйте Let's Encrypt или другой публично доверенный CA. Wildcard `*.mydomain.com` покрывает оба имени — `play` и `host`.

## Настройка сервера

- Реальный mod ID реализации AutoModpack — `automodpack_mod`; это обязательная
  серверная зависимость NeoForge с диапазоном `[5.0.0-rc.1,5.0.0-rc.2]`. Это две
  версии, чьи пути учётных файлов и API перезапуска были проверены. NeoForge
  отклоняет отсутствующие и другие версии; при активации дополнительно
  проверяется форма host API.
- Скопируйте `config/amp-autocert.properties.example` в `automodpack/amp-autocert.properties` реального сервера. Это несекретный эксплуатационный файл. Держите `enabled=false`, `acme.environment=staging` и `acme.allowStagingIssuance=false`, пока не будут готовы отдельная тестовая зона и ограниченный токен. Плановый staging-режим никогда не создаёт и не устанавливает сертификат. Явная staging-проба структурно проверяет и отбрасывает недоверенный результат; production-выпуск требует `enabled=true`, `acme.environment=production` и `host.restartAfterRenew=true`.
- Файл properties — канонический источник DNS-зоны/владельца challenge, доменов сертификата, URL директорий Let's Encrypt, API root/TTL Cloudflare, публичных DNS-over-HTTPS resolver'ов, hostname/SRV-цели Minecraft, endpoint AutoModpack и ограниченных сетевых таймаутов. Перед использованием проверяются пара apex/wildcard, односегментные хосты той же зоны и официальные HTTPS-endpoint'ы провайдеров. Ожидание распространения: `dns.propagationInitialDelaySeconds=15`, затем требуется согласие TXT-значений между Cloudflare и Google DoH; временная недоступность одного resolver'а допускается.
- `debugmode=false` — краткий режим журнала по умолчанию. Значение `true` добавляет безопасную диагностику фаз ACME, hostname/status/latency resolver'ов, количество TXT, тайминги retry, consensus и cleanup/recovery. Сырые ACME/JWS/DNS-ответы сторонних библиотек, значения challenge, токены провайдера, состояние аккаунта, байты сертификата и приватные ключи подавляются в обоих режимах.
- `host.activationTimeoutSeconds` и `host.readinessPollSeconds` ограничивают проверяемое ожидание перезапуска/готовности AutoModpack; готовность требует, чтобы AutoModpack сообщил и о работающем хосте, и о точном SHA-256 fingerprint нового leaf-сертификата.
- `renew.failureCooldownHours` по умолчанию 48 и сохраняется между перезапусками перед production-попыткой. Это не даёт повторным сбоям активации или перезапускам сервера создать шторм ACME-orders. Старые конфигурации без этого ключа безопасно получают 48 часов.
- Неудачные фоновые проверки используют ограниченный экспоненциальный retry: `retry.initialMinutes`, `retry.maxHours`, `retry.jitterPercent` (по умолчанию 15 минут, 12 часов, 20%). После резервирования production-попытки повторы ждут окончания сохранённого cooldown, а не обходят его.
- Существующие конфигурации могут добавить новые ключи из примера. Если их нет, применяются миграционно-безопасные значения: `dns.propagationInitialDelaySeconds=15`, два поддерживаемых DoH-endpoint'а, `host.activationTimeoutSeconds=60`, `host.readinessPollSeconds=1` и `renew.failureCooldownHours=48`. Секретов в этом файле быть не должно.
- Токен Cloudflare настраивайте **только** через `AMP_AUTOCERT_CF_API_TOKEN` или файл `automodpack/credentials/amp-autocert-cloudflare-token.txt`, доступный на чтение только серверному процессу. Токен должен иметь ограниченное зоной разрешение `Zone / DNS / Edit`. `.gitignore` исключает этот файл.
- Если хостинг Pterodactyl не позволяет ни переменные окружения, ни смену прав, установите `credentials.allowInsecureTokenFilePermissions=true` и используйте тот же фиксированный файл. Это сознательно допускает права панели вроде `0644`; записываемые другими файлы/директории, другие пути и симлинки по-прежнему отклоняются. Ограничьте доступ к панели/SFTP доверенными администраторами и держите `automodpack/credentials/**` исключёнными из каждой группы модпака. Во всех остальных случаях оставляйте `false`.
- На таком же ограниченном хостинге `credentials.allowInsecureDeploymentFilePermissions=true` запрещает будущему deployer'у вызывать chmod или переписывать ACL для размещаемых сертификатов, ключей, бэкапов и журналов. POSIX-пути, записываемые группой/остальными, всё равно отклоняются, но приватный материал может остаться читаемым согласно настройкам панели/umask. Ограничьте доступ к панели/SFTP и оставляйте `false`, когда управление правами работает.
- Никогда не помещайте секреты в публичный файл amp-autocert properties. Явно исключите `automodpack/amp-autocert.properties` и все директории с учётными данными/состоянием из файловых правил AutoModpack; не полагайтесь на недокументированное поведение исключений.
- Поддерживаемые AutoModpack rc.1/rc.2 используют `automodpack/credentials/certificate.crt` для полной цепочки и `automodpack/credentials/private-key.pem` для незашифрованного ключа PKCS#8. Проверенный байткод не использует `certificate.cert`.
- В `automodpack/server.conf` добавьте `automodpack/credentials/**` и `automodpack/amp-autocert.properties` в список `exclude` каждой группы. Версия 5 включает только `automodpack/host-modpack/<группа>/` и пути из `from-server` этой группы; эти исключения — защита в глубину от будущих или добавленных оператором широких правил.
- Не публикуйте staging-сертификат пользователям. Игроки с ручными pin'ами сертификата должны один раз удалить старый pin при переходе на публичный CA; последующие продления публичного CA не должны требовать повторного закрепления на новых клиентах.

## Команды оператора

Выделенный сервер регистрирует эти команды с уровнем прав 3:

- `/ampautocert status` читает кэшированный снимок жизненного цикла и сообщает настроенную ACME-среду, срок действия/оставшиеся дни сертификата, развёрнутые DNS SAN, итог последней проверки и действующий cooldown выпуска. Никогда не читает учётные данные и не выполняет сетевой I/O.
- `/ampautocert check` ставит одну немедленную проверку в очередь существующего фонового worker'а. Повторные запросы объединяются, пока проверка в очереди или выполняется. Все обычные барьеры staging, production, cooldown, валидации, развёртывания и rollback действуют; команда не форсирует выпуск.
- `/ampautocert staging-issue` принимается только при `acme.environment=staging` и `acme.allowStagingIssuance=true`. Создаёт настроенные DNS-01 записи, получает staging-сертификат Let's Encrypt, проверяет в памяти структуру ключа/цепочки, SAN и покрытие имён, очищает свои TXT-записи и отбрасывает недоверенный результат. Сохраняемый cooldown по среде не даёт повторять orders. Никогда не записывает и не активирует учётные файлы AutoModpack.
- `/ampautocert renew` запрашивает продление, даже если развёрнутый сертификат ещё не подошёл к сроку. Отклоняется вне production-режима и не может обойти сохраняемый cooldown выпуска, preflight AutoModpack, валидацию сертификата, rollback развёртывания или барьер single-flight. Используйте только для намеренной production-ротации.

Все команды наследуют требование уровня прав 3. Предикат прав имеет детерминированное покрытие; операторам всё же стоит проверить отображение прав на своём сервере, прежде чем делегировать доступ к командам.

Полная процедура подготовки, ограниченного токена Cloudflare, резервного копирования,
перехода staging -> production, ротации, отката, fingerprint-pin и диагностики —
в [`docs/PRODUCTION_SETUP_RU.md`](docs/PRODUCTION_SETUP_RU.md)
([English version](docs/PRODUCTION_SETUP.md)). Не включайте production только по
кратким заметкам выше.

## Карта проекта

| Путь | Назначение |
| --- | --- |
| `src/main/java/.../AmpAutoCertMod.java` | точка входа жизненного цикла сервера |
| `docs/THIRD_PARTY.md` | закреплённые Jar-in-Jar артефакты, лицензии и хеши |
| `.../AutoCertService.java` | фоновый single-flight жизненный цикл продления и связывание зависимостей |
| `.../RenewalCoordinator.java` | восстановление, решение о сроке, production-барьер и оркестрация конвейера |
| `.../DohDnsTxtResolver.java` | ограниченный TXT-консенсус по двум публичным DNS-over-HTTPS провайдерам |
| `.../RenewalPolicy.java` | тестируемое решение о сроке действия |
| `.../CertificateInspector.java` | читает срок действия и DNS SAN из PEM |
| `.../CertificatePaths.java` | пути учётных файлов AutoModpack |
| `.../CertificateDeploymentService.java` | проверенная аварийно-безопасная замена двух файлов |
| `.../CertificateActivationService.java` | активация по точному fingerprint и оркестрация rollback |
| `.../AutoModpackHostControl.java` | адаптер перезапуска/готовности на серверном потоке с закреплённой версией |
| `.../CloudflareCredentialSource.java` | поиск токена в переменной окружения/приватном файле |
| `src/test/` | начальные JUnit-тесты |
| `README.md` | английская версия этого README |

## Границы валидации

В проекте есть детерминированное покрытие заглушками для связанного координатора, сохраняемого cooldown выпуска, очистки DNS, валидации сертификата, развёртывания, активации, rollback и двухфазного восстановления после аварии. Оператор дополнительно выполнил описанный выше живой production-прогон на целевом сервере NeoForge/AutoModpack. Это единичное успешное развёртывание не заменяет регрессионные тесты и не доказывает каждый сценарий сбоя провайдера или аварии в production. В репозитории и release-JAR нет пользовательских API-ключей, реальных сертификатов, состояния ACME-аккаунта или других runtime-секретов.

Ссылки: [NeoForge 1.21.1](https://docs.neoforged.net/docs/1.21.1/gettingstarted/) · [Безопасность AutoModpack](https://moddedmc.wiki/en/project/automodpack/latest/docs/security) · [acme4j](https://github.com/shred/acme4j).
