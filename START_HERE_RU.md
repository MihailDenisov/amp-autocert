# amp-autocert — с чего начать

Это заготовка проекта для **VS Code + Codex**: Java 21, Minecraft 1.21.1, NeoForge **21.1.248**. Имя распространяемого мода — `amp-autocert`, технический mod ID — `amp_autocert` (дефис недопустим для mod ID).

**Статус:** production pipeline проверен оператором 29.09.2026: Let's Encrypt выдал сертификат для apex/wildcard, DNS-01 записи Cloudflare были очищены, файлы безопасно заменены, AutoModpack 5.0.0-rc.2 активировал сертификат, а после перезапуска `/ampautocert check` показал `VALID/not_due` и правильные SAN. Клиент подключился без ручного ввода fingerprint. Для новых установок сохраняются безопасные значения по умолчанию: мод выключен и выбран staging.

Единичный renewal coordinator связывает ACME/DNS-01, проверку сертификата, аварийно-восстанавливаемую замену файлов и активацию/rollback через проверенные AutoModpack 5.0.0-rc.1/rc.2. Плановая staging-проверка ничего не выдаёт. Отдельная команда `/ampautocert staging-issue` доступна только при явном `acme.allowStagingIssuance=true`: она выдаёт staging-сертификат, проверяет его структуру в памяти без доверия к staging CA, очищает свои TXT-записи и не меняет файлы AutoModpack. Повторные orders ограничены сохраняемым cooldown.

Реальный mod ID загружаемой реализации AutoModpack — `automodpack_mod`; это обязательная server-side зависимость NeoForge с диапазоном `[5.0.0-rc.1,5.0.0-rc.2]`. Проверены rc.1 и rc.2; отсутствующие и другие версии отклоняются. Перед активацией дополнительно проверяется требуемый runtime API. Новую версию нужно сначала проверить по исходникам и на сервере, затем явно добавить в диапазон.

1. Распакуй архив и открой папку `amp-autocert` в VS Code.
2. Установи JDK 21 и предложенные VS Code расширения Java/Gradle.
3. Запусти задачу `Gradle: test` или команду `./gradlew test` (`gradlew.bat test` на Windows). При первом запуске нужен Интернет: загрузятся проверенный официальный wrapper Gradle и зависимости NeoForge.
4. Открой Codex в папке проекта. Он прочитает `AGENTS.md` и обнаружит навыки в `.agents/skills/`. Параметры дешёвых субагентов хранятся в `.codex/config.toml`. При отсутствии выбранной быстрой модели замени её на доступную в своём тарифе.
5. Открой `CODEX_TASK.md` и отправь Codex задание из раздела 4. Сначала работа со staging, затем тесты и только потом production.

Твои заранее указанные домены/порты внесены в конфигурацию-пример: `play.maicraft.tech` (через SRV на `hm.maicraft.tech:25598`), AutoModpack `hm.maicraft.tech:25809`, сертификат `maicraft.tech` + `*.maicraft.tech`. Публичный конфиг мода создаётся в `automodpack/amp-autocert.properties`. DNSSEC не нужен. Cloudflare Origin CA не подходит для прямого подключения клиентов — использовать публичный Let's Encrypt.

Домены, DNS-01 owner, ACME/Cloudflare endpoints, TTL, Minecraft/SRV/AutoModpack endpoints и таймауты теперь берутся из `automodpack/amp-autocert.properties`, а не из Java-кода. После публикации TXT мод по умолчанию ждёт 15 секунд (`dns.propagationInitialDelaySeconds`), затем сверяет ответы Cloudflare и Google DNS-over-HTTPS из `dns.dohResolvers`; временная недоступность одного resolver допускается. В старом файле отсутствующие новые DNS-параметры получают эти безопасные значения автоматически. Проверки безопасности разрешают только два указанных официальных HTTPS endpoint.

`debugmode=false` оставляет краткий журнал. Временное `debugmode=true` добавляет безопасные подробности по фазам ACME, hostname/status/latency каждого DNS resolver, количеству TXT, попыткам, задержкам, consensus и cleanup/recovery. Даже в этом режиме не журналируются токен Cloudflare, значения challenge, ACME account state, сырые DNS/ACME ответы, сертификат или приватный ключ.

Параметры `host.activationTimeoutSeconds` и `host.readinessPollSeconds` ограничивают ожидание перезапуска. Готовность считается подтверждённой только когда AutoModpack сообщает, что хост работает и загрузил точный SHA-256 fingerprint нового leaf-сертификата.

`renew.failureCooldownHours=48` задаёт сохраняемую между перезапусками минимальную паузу между production-попытками. Это не позволяет ошибкам активации или частым рестартам создавать слишком много ACME orders. Для старого конфига без этого ключа безопасно применяется 48 часов.

**Никогда не добавляй токен Cloudflare в публичный конфиг или файлы сборки:** приватный файл токена должен лежать в `automodpack/credentials/` либо передаваться через переменную окружения, и вся директория credentials должна быть исключена из распространяемого модпака.

Если хостинг Pterodactyl не позволяет задать переменную окружения или изменить права файла, можно явно включить менее безопасный режим `credentials.allowInsecureTokenFilePermissions=true`. Он допускает доступ на чтение вроде `0644`, но по-прежнему отклоняет доступ на запись для группы/остальных. Токен всё равно должен лежать только в `automodpack/credentials/amp-autocert-cloudflare-token.txt`; произвольные пути и символические ссылки не разрешаются. Ограничь доступ к панели/SFTP доверенными администраторами и обязательно исключи `automodpack/credentials/**` из каждой группы AutoModpack. Во всех остальных случаях оставляй параметр `false`.

Для будущей установки сертификата на таком хостинге параметр `credentials.allowInsecureDeploymentFilePermissions=true` запрещает моду вызывать `chmod` или менять ACL файлов сертификата, ключа, резервной копии и журнала. Пути с правом записи для группы/остальных всё равно отклоняются, но чтение может остаться разрешённым согласно настройкам панели/umask. Используй этот режим только при ограниченном доступе к панели/SFTP; обычно оставляй `false`.

В поддерживаемых AutoModpack rc.1/rc.2 используются `automodpack/credentials/certificate.crt` и `automodpack/credentials/private-key.pem`. В `automodpack/server.conf` добавь `automodpack/credentials/**` и `automodpack/amp-autocert.properties` в `exclude` каждого modpack group. Проверенный runtime не читает имя `certificate.cert`.

Полная инструкция по подготовке Cloudflare, резервному копированию, переходу staging -> production, откату, fingerprint pins и диагностике находится в [`docs/PRODUCTION_SETUP.md`](docs/PRODUCTION_SETUP.md). Не включай production только по краткой памятке выше.
