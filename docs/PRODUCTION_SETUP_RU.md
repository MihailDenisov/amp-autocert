# Настройка production, продление и восстановление

[English guide](PRODUCTION_SETUP.md) · [Обзор проекта](../README_RU.md)

Это руководство для предрелиза `0.2.0-rc.1`; известные ограничения приведены в [CHANGELOG](../CHANGELOG.md). У стабильной `v0.1.0` отдельно проверен production-конвейер продления.

Это руководство для оператора **серверного** мода amp-autocert. Он получает и продлевает публично доверенные TLS-сертификаты, подписанные Let's Encrypt, для AutoModpack с помощью ACME DNS-01 и Cloudflare DNS. Основной сценарий — панельный Minecraft-хостинг, часто Pterodactyl, где нельзя установить Certbot, cron, systemd-таймеры или другие системные ACME-утилиты и нельзя включить DNSSEC для отдельного механизма доверия AutoModpack AMP1. Для выпуска через DNS-01 DNSSEC **не нужен**. Cloudflare **обязательно** должен быть авторитетным DNS зоны: мод изменяет TXT-записи через его API. Сертификат Cloudflare Origin CA не имеет нужного публичного доверия у прямых клиентов Minecraft.

Мод работает со встроенным TLS-хостом AutoModpack. Он не настраивает внешний reverse proxy, туннель, прокси Cloudflare и сертификат на сырой IP. На одной эталонной установке 29.09.2026 проверены production-выпуск и активация с AutoModpack 5.0.0-rc.2; собственный сервер всё равно нужно проверить отдельно.

## 1. Требования и схема адресов

| Требование | Что проверить |
| --- | --- |
| Сервер | Minecraft Java 1.21.1, NeoForge 21.1.248, Java 21, выделенный сервер. |
| AutoModpack | `automodpack_mod` версии **5.0.0-rc.1 или 5.0.0-rc.2**. Отсутствие/другая версия отклоняется; новые релизы требуют отдельной проверки совместимости. |
| DNS-зона | Активная зона Cloudflare; NS у регистратора должны совпасть с назначенными Cloudflare nameserver'ами этой зоны. Одного наличия зоны в панели недостаточно. |
| Учётные данные | API-токен с `Zone / DNS / Edit` только для нужной зоны; серверу нужен исходящий HTTPS к Cloudflare, Let's Encrypt и публичным DNS-over-HTTPS resolver'ам. |
| Хост AutoModpack | `modpack-host: true`, `disable-internal-tls: false`, поддерживаемый `connection-mode`, читаемые загруженные и дисковые настройки и безопасные записываемые пути для учётных файлов. |
| Имена | Адрес игроков (`origin.hostname`) и hostname объявленного endpoint'а AutoModpack — apex зоны либо поддомен **ровно с одним уровнем**. IP, другая зона и более глубокие поддомены не подходят. |
| Порты | Порт Minecraft и фактический порт слушателя/публичный порт AutoModpack должны быть выделены, доступны и правильно направлены панелью. Сертификат покрывает DNS-имена, **не порты**. |

Пример: игроки вводят `play.example.com`, Minecraft работает на `node.example.com:25598` (необязательная SRV-запись `_minecraft._tcp.play.example.com` может указывать туда), AutoModpack отдаёт файлы по `packs.example.com:25809`. Публичные A/AAAA-записи должны вести на действительный адрес хостинга. Для прямого игрового соединения и загрузки установите Cloudflare **DNS only**: обычный HTTP-прокси с оранжевым облаком не перенаправляет произвольные TCP-порты Minecraft/AutoModpack. Удалите неработающую AAAA-запись. В Pterodactyl выделите и откройте как порт Minecraft, так и фактический порт слушателя AutoModpack: объявление порта не создаёт слушатель или allocation в панели.

Мод запрашивает ровно `example.com` и `*.example.com`, а временные TXT для DNS-01 создаёт на `_acme-challenge.example.com`. Wildcard покрывает `play.example.com` и `packs.example.com`, но не `a.b.example.com`; apex SAN покрывает `example.com`. SRV-цель и её порт не становятся именами сертификата. Для apex и wildcard Let's Encrypt может потребовать **две одновременные TXT-записи**: не заменяйте одну другой и не создавайте постоянный challenge вручную.

Настройки endpoint'а принадлежат AutoModpack в `automodpack/server.conf`. `advertised-endpoint-host` — публичный hostname загрузки; при пустом значении мод использует `origin.hostname`. `advertised-endpoint-port` — публичный порт загрузки; при `-1` используется порт подключения/Minecraft-сервера. Режим `connection-mode: HOLEPUNCH` использует порт Minecraft, `MAGIC` — его же или заданный `bind-port`, а `HTTP` требует отдельный `bind-port`. Внутренний bind-порт и объявленный публичный порт могут различаться только при правильном перенаправлении на хостинге; проверьте подключение клиента. Мод сравнивает загруженные и дисковые значения и блокирует работу при значимом расхождении. См. [конфигурацию сервера AutoModpack](https://moddedmc.wiki/en/project/automodpack/latest/docs/configuration/server-config).

## 2. Подготовка Cloudflare и токена

1. Добавьте в Cloudflare реальную apex-зону (например, `example.com`). Скопируйте **Zone ID** со страницы обзора зоны: это 32-символьный ID, не ID аккаунта и не API-токен. У регистратора назначьте NS, выданные этой зоне Cloudflare. Дождитесь статуса **Active** и согласия публичных NS. Суффикс имени nameserver'а не заменяет проверку: мод сравнивает точный назначенный набор с ответами независимых публичных DNS.
2. В Cloudflare создайте **custom API token** (Profile → API Tokens → Create Token). Разрешите `Zone / DNS / Edit` **только для этой зоны**; не используйте Global API Key или доступ ко всему аккаунту. Ограничение по IP имеет смысл лишь при стабильном исходящем IP сервера. Скопируйте токен при создании: Cloudflare показывает его один раз. Preflight проверяет активность токена и доступ к зоне; staging DNS-01 подтверждает фактическое право создавать и удалять TXT.
3. Передайте токен ровно одним способом: предпочтительно через приватную переменную окружения контейнера `AMP_AUTOCERT_CF_API_TOKEN` либо UTF-8 файл `<корень-сервера>/automodpack/credentials/amp-autocert-cloudflare-token.txt`, содержащий **только текст токена** (перевод строки в конце допустим). Если заданы оба источника, переменная окружения имеет приоритет. Используйте приватный механизм панели, а не публичное поле конфига или видимую команду запуска.
4. На POSIX-хосте файл токена должен читаться только серверным процессом (например, режим `0600`), а другие пользователи не должны иметь права записи в директории credentials. Симлинки отклоняются. Если панель не даёт приватной переменной и не позволяет ограничить права чтения, используйте `credentials.allowInsecureTokenFilePermissions=true` только после ограничения доступа к панели, SFTP, бэкапам и shell доверенными администраторами. Этот режим допускает права чтения вроде `0644`, но не разрешает запись группой/остальными и симлинки. В остальных случаях оставьте `false`.

Никогда не помещайте токен в `amp-autocert.properties`, `server.conf`, JAR, Git, логи или заявку поддержки. Приватный ключ ACME-аккаунта и ключ сертификата генерирует и хранит мод; создавать или вставлять их вручную не нужно. См. [создание токена](https://developers.cloudflare.com/fundamentals/api/get-started/create-token/), [проверку токена](https://developers.cloudflare.com/api/resources/user/subresources/tokens/methods/verify/) и [информацию о зоне](https://developers.cloudflare.com/api/resources/zones/methods/get/).

## 3. Установка и защита файлов сервера

Поместите JAR поддерживаемого AutoModpack и JAR amp-autocert в `mods/` выделенного сервера. Для сборки из исходников нужен JDK 21: `./gradlew test` и `./gradlew build` (Windows: `gradlew.bat`); мод будет в `build/libs/`. После изменения amp-autocert properties перезапустите сервер: мод читает файл при старте. Изменения AutoModpack требуют предусмотренной им процедуры reload/restart; мод блокирует работу при расхождении загруженной и дисковой конфигурации identity.

Для **каждой** группы модпака в `automodpack/server.conf` проверьте `from-server` и добавьте в `exclude` группы:

```text
automodpack/credentials/**
automodpack/amp-autocert.properties
automodpack/amp-autocert-state/**
```

Держите `automodpack/credentials/`, `automodpack/amp-autocert-state/` и резервные копии вне всех `host-modpack` и широких правил раздачи клиентам. В credentials могут находиться файл токена Cloudflare, состояние ACME, приватный ключ, действующий сертификат, журнал развёртывания и бэкапы. Файл `amp-autocert-state/enrollment.properties` содержит несекретный принятый baseline и фазу; храните его только на сервере, поскольку он управляет барьером выпуска. Проверьте каждую группу, а не только основную. Обычно AutoModpack раздаёт директорию группы и явные пути `from-server`; исключения защищают и от будущих изменений правил.

На **остановленном** сервере до включения production или миграции сделайте приватную копию **всей** директории `automodpack/credentials/` и `automodpack/amp-autocert-state/`. Храните копии вне клиентской раздачи и публичного веб-доступа. `certificate.crt` и `private-key.pem` всегда сохраняйте парой: поддерживаемые сборки AutoModpack читают `automodpack/credentials/certificate.crt` (PEM, leaf первым, полная цепочка) и `automodpack/credentials/private-key.pem` (незашифрованный PKCS#8). Не заменяйте вручную только один файл. Состояние ACME лежит под `automodpack/credentials/amp-autocert/`, а несекретный enrollment baseline — под `automodpack/amp-autocert-state/`. Не редактируйте и не удаляйте их ради обхода блокировки.

## 4. Заполнение `amp-autocert.properties`

Скопируйте `config/amp-autocert.properties.example` в `<корень-сервера>/automodpack/amp-autocert.properties`. Это **несекретный эксплуатационный** файл; он должен быть в `automodpack/` корня сервера, а не в `config/` или локальной папке IDE. Замените четыре значения identity/provider на собственные:

```properties
enabled=false
debugmode=false
acme.environment=staging
acme.email=admin@example.com
origin.hostname=play.example.com
cloudflare.zoneName=example.com
cloudflare.zoneId=0123456789abcdef0123456789abcdef

acme.networkTimeoutSeconds=30
dns.propagationTimeoutSeconds=120
dns.propagationInitialDelaySeconds=15
cloudflare.ttlSeconds=60
renew.beforeDays=30
check.intervalHours=24
drift.pollSeconds=60
renew.failureCooldownHours=48
retry.initialMinutes=15
retry.maxHours=12
retry.jitterPercent=20
host.restartAfterRenew=true
host.activationTimeoutSeconds=60
host.readinessPollSeconds=1
credentials.allowInsecureTokenFilePermissions=false
credentials.allowInsecureDeploymentFilePermissions=false
```

| Ключ | Откуда взять значение / что означает |
| --- | --- |
| `acme.email` | Доступный вам адрес для уведомлений аккаунта Let's Encrypt; не пароль Cloudflare. |
| `origin.hostname` | Точное DNS-имя, которое вводят игроки в Minecraft, включая имя с SRV-записью, если она используется. AutoModpack не позволяет надёжно вывести его автоматически. |
| `cloudflare.zoneName` | Apex авторитетной зоны Cloudflare без схемы, wildcard, точки в конце и порта. |
| `cloudflare.zoneId` | Zone ID с обзора **той же** зоны Cloudflare; не NS и не токен. |
| `enabled`, `acme.environment` | Начинайте с выключенного мода. Выбирайте `production`, когда реальная зона/токен готовы и планируется staging-проверка ниже. Среда выбирает **обычный** выпуск; reconciliation всегда использует Let's Encrypt staging. |
| `debugmode` | `false` для обычного журнала; `true` даёт дополнительные ограниченные диагностические фазы без сырых учётных данных и ответов провайдеров. После изменения нужен рестарт. |
| `acme.networkTimeoutSeconds`, `dns.propagationTimeoutSeconds`, `dns.propagationInitialDelaySeconds`, `cloudflare.ttlSeconds` | Ограничения сетевых запросов/ожидания DNS и TTL TXT. По умолчанию 30, 120, 15 и 60 секунд. Меняйте лишь после диагностики конкретной проблемы. |
| `renew.beforeDays`, `check.intervalHours`, `drift.pollSeconds` | Окно продления 30 дней, плановая проверка каждые 24 часа, локальная проверка значимой конфигурации каждые 60 секунд. Локальный poll не обращается к сети. |
| `renew.failureCooldownHours`, `retry.initialMinutes`, `retry.maxHours`, `retry.jitterPercent` | Сохраняемый cooldown попыток 48 часов и ограниченный retry: 15 минут, максимум 12 часов, jitter 20%. Команды и рестарты не сбрасывают cooldown. |
| `host.restartAfterRenew`, `host.activationTimeoutSeconds`, `host.readinessPollSeconds` | Для production нужен автоматический перезапуск хоста. По умолчанию true, 60 и 1 секунда; успех требует точного fingerprint нового сертификата у работающего хоста. |
| `credentials.allowInsecureTokenFilePermissions`, `credentials.allowInsecureDeploymentFilePermissions` | Оставьте false, если ограничения панели не требуют совместимого режима. Переключатель развёртывания отключает chmod/ACL и может оставить приватные файлы читаемыми по правилам панели; POSIX-пути с записью для группы/остальных всё равно запрещены. |

В новый файл **не добавляйте** `acme.domains`, `dns.challengeRecord`, `minecraft.srvTarget`, `automodpack.endpoint`, URL директорий ACME, API root Cloudflare и URL DoH-resolver'ов. Домены и challenge выводятся автоматически; параметры сети/TLS читаются из AutoModpack; адреса провайдеров закреплены в коде. Старый флаг `acme.allowStagingIssuance` устарел и в этой версии не управляет staging-пробой.

## 5. Проверка, staging и production-выпуск

1. С `enabled=false` запустите сервер и выполните `/ampautocert doctor` (уровень прав 3). Команда проверит поддерживаемую версию AutoModpack, загруженную **и** дисковую конфигурацию хоста, покрытие origin/endpoint, пути учётных файлов, активность токена Cloudflare, точные ID/имя/статус зоны, назначенные NS, публичное делегирование и метаданные текущего сертификата. Она читает сеть, но не создаёт ACME order, TXT или учётные файлы. На новой установке сообщение `no accepted configuration baseline exists` ожидаемо. Doctor не доказывает право **записи** DNS и фактическую доступность endpoint'а для клиента.
2. Сохраните credentials, проверьте DNS и порты в панели, затем установите `enabled=true`, `acme.environment=production`, `host.restartAfterRenew=true` и перезапустите сервер. Плановая production-проверка запускается вскоре после старта, но **отсутствие baseline блокирует её**. При обновлении сохраните прежний действующий сертификат. Отдельная команда `/ampautocert staging-issue` сама по себе не создаёт baseline.
3. Выполните `/ampautocert reconcile` для пояснения, затем `/ampautocert reconcile confirm`. Команда проводит полный preflight, создаёт временные TXT, получает настоящий **staging** сертификат Let's Encrypt для текущих apex/wildcard, проверяет его в памяти, удаляет только собственные записи Cloudflare и принимает обнаруженный baseline при полном успехе. Её можно выполнять при `acme.environment=production`; у staging есть собственный сохраняемый cooldown. Она не развёртывает staging-сертификат, не перезапускает AutoModpack и не создаёт production order. При неудаче прочитайте отчёт, исправьте причину, соблюдайте cooldown и повторите после исправления; старая пара остаётся на месте.
4. После успешной reconciliation выполните `/ampautocert status` и `/ampautocert check`. Обычная проверка получает production-сертификат, только если текущего нет или его **дата окончания** вошла в окно продления. Доверие CA к текущему сертификату не влияет на решение о сроке: даже действующий **самоподписанный** сертификат с большим сроком может дать `not_due`. Если вы намеренно заменяете его на публично доверенный, после успешной reconciliation выполните `/ampautocert renew`. Действующий старый публичный сертификат вне окна не должен вращаться лишь из-за обновления мода. `renew` форсирует **решение по сроку**, но не обходит baseline, проверки AutoModpack, cooldown, валидацию нового сертификата/ключа/цепочки и rollback. Плановая проверка тоже может начать production при наступлении срока; reconciliation не ставит автоматизацию на паузу.
5. Проверьте ожидаемые DNS SAN, срок действия, status, готовность хоста AutoModpack, реальную загрузку и вход клиента Minecraft. Сертификат покрывает имена; отдельно проверьте открытые порты панели и публичный маршрут. Успешные unit-тесты или `doctor` не доказывают доступность сервера для игрока.

`/ampautocert staging-issue` — **необязательная отдельная** проверка при `enabled=true`, `acme.environment=staging` **и уже принятом baseline**. Обычный плановый staging-режим ничего не выдаёт и не развёртывает. Ключ `acme.allowStagingIssuance=true` не требуется. Для первого enrollment и reconciliation используйте `/ampautocert reconcile confirm`. Сертификаты Let's Encrypt staging не имеют публичного доверия и никогда не ставятся в действующие credentials AutoModpack. См. [staging Let's Encrypt](https://letsencrypt.org/docs/staging-environment/) и [описание DNS-01](https://letsencrypt.org/docs/challenge-types/).

## 6. Продление, миграция, drift и восстановление

Обычное production-продление идёт по настроенному расписанию и **не повторяет staging** при неизменном принятом baseline. Перед ACME order production-попытка резервирует сохраняемый cooldown. До замены файлов мод проверяет полную цепочку, точный набор apex/wildcard SAN, покрытие origin и endpoint, срок действия, назначение server TLS, системное доверие и соответствие приватного ключа PKCS#8 сертификату. Он подготавливает оба файла, удерживает старую пару для отката, перезапускает AutoModpack и требует точный fingerprint установленного leaf-сертификата. Сбои сохраняют либо восстанавливают старую пару там, где это возможно; восстановление после аварии предшествует следующему решению о выпуске.

При обновлении с v0.1.0 `minecraft.hostname` и `dns.zoneName` временно принимаются как alias для `origin.hostname` и `cloudflare.zoneName`, если значения не конфликтуют. Совпадающие старые вычисляемые и провайдерские значения могут загрузиться с предупреждением; замените их минимальным новым файлом. Удаляйте прежние `minecraft.srvTarget` и `automodpack.endpoint` после проверки действительных настроек AutoModpack. **Текущее ограничение:** эти старые строки endpoint разбираются, но не сравниваются с обнаруженным endpoint; при миграции сравните вручную. Существующий действующий production-сертификат сохраняется при отсутствии baseline, но для последующих автоматических выпусков всё равно нужен `/ampautocert reconcile confirm`. Мод не переписывает старый properties-файл автоматически.

Изменения origin, зоны, hostname/порта endpoint'а AutoModpack, режима/bind-порта и владельца TLS проверяются локально и перед выпуском/развёртыванием. Значимое изменение, расхождение загруженного и дискового состояния, нечитаемый конфиг или сохранённая блокировка drift прекращают автоматический выпуск, развёртывание и перезапуск хоста. Изменения содержимого групп модпака не меняют baseline. Простое возвращение старого файла не снимает сохранённую блокировку. Выполните `doctor`, исправьте причину и явно примите identity через успешный `reconcile confirm`; не редактируйте приватный enrollment state.

Для ручного аварийного восстановления: задайте `enabled=false`, остановите Minecraft, сохраните проблемные/текущие директории credentials и enrollment-state приватно для диагностики и восстановите **полные** резервные копии до изменений вместе (совпадающие сертификат **и** ключ, состояние аккаунта и принятого baseline). Запустите сервер, проверьте, что AutoModpack отдаёт восстановленный fingerprint, и оставьте продление выключенным до выяснения причины. Не удаляйте DNS-записи без точного record ID и подтверждённого владения ими. Сохранённый игроком ручной fingerprint-pin имеет приоритет над доверием CA; после независимой проверки сервера попросите игрока один раз удалить старый pin через документированный интерфейс AutoModpack.

## 7. Диагностика

| Симптом | Что проверить / сделать |
| --- | --- |
| Мод не загрузился, команд нет | Проверьте Java/NeoForge, оба серверных JAR и точную зависимость AutoModpack rc.1/rc.2. |
| Ошибка загрузки конфига | Проверьте обязательные email, origin, имя/ID зоны и булевы значения; удалите конфликтующие старые alias/вычисляемые ключи; перезапустите. |
| `doctor` сообщает об отсутствии baseline | До первого enrollment это ожидаемо; изучите другие проверки, затем включите production и выполните `reconcile confirm`. До успеха обычный выпуск блокируется. |
| AutoModpack не поддерживается или загруженный конфиг не совпадает с дисковым | Проверьте версию, `server.conf`, reload/restart, `modpack-host`, режим, bind/advertised-порты и внутренний TLS. Не обходите расхождение. |
| Origin/endpoint не покрыты | Оба hostname должны быть apex либо односегментными поддоменами `cloudflare.zoneName`; осознанно исправьте маршрут и проведите reconciliation. IP/глубокие/чужие имена не поддерживаются. |
| Токен отсутствует/неактивен или зона недоступна | Проверьте приватную переменную/файл, статус токена, доступ DNS Edit к зоне, ID/имя зоны и права файлов панели. Не печатайте токен ради проверки. |
| Публичные NS не совпадают или зона не active | Исправьте NS у регистратора на назначенные Cloudflare и дождитесь распространения; `doctor` должен увидеть согласие. |
| Ошибка staging или таймаут DNS | Проверьте право записи токена, конфликт CNAME/записей `_acme-challenge`, назначенные NS, исходящий HTTPS, доступность resolver'ов и cooldown. Не устанавливайте staging и не добавляйте постоянные TXT. |
| Команда staging отклонена | `staging-issue` требует `enabled=true`, `acme.environment=staging` и принятый baseline; для первого enrollment нужна `reconcile confirm`. |
| `not_due`, хотя ожидалось продление | Сравните срок с `renew.beforeDays`; `renew` используйте лишь для намеренной досрочной production-ротации. |
| Старый самоподписанный сертификат остался активным | Обычная проверка смотрит на дату окончания и может вернуть `not_due`; после успешной staging reconciliation осознанно запустите `renew` для перехода на публичное доверие. |
| Активен production cooldown | Дождитесь указанной следующей попытки; рестарты и команды его не удаляют. |
| Ошибка активации/готовности | Проверьте внутренний TLS, реальный порт слушателя/advertised-порт в панели, запуск AutoModpack и fingerprint. Сохраните старую пару/бэкап; не заменяйте один файл отдельно. |
| Старый клиент сообщает о несовпадении fingerprint | Убедитесь в подлинности сервера и нового сертификата, затем попросите клиента удалить старый ручной pin. |

`/ampautocert status` показывает кэшированный последний результат, срок/SAN, cooldown и безопасные имена полей drift; `/ampautocert doctor` выполняет текущий ограниченный preflight. `debugmode=true` после рестарта добавляет диагностические фазы, но перед публикацией support bundle всё равно проверьте наличие информации о топологии. Никогда не публикуйте токен, приватные ключи аккаунта/сертификата, содержимое credentials, сырые ответы провайдеров и неочищенные переменные панели.

Источники: [конфигурация AutoModpack](https://moddedmc.wiki/en/project/automodpack/latest/docs/configuration/server-config), [безопасность и pin'ы AutoModpack](https://moddedmc.wiki/en/project/automodpack/latest/docs/security), [создание токена Cloudflare](https://developers.cloudflare.com/fundamentals/api/get-started/create-token/), [информация о зоне Cloudflare](https://developers.cloudflare.com/api/resources/zones/methods/get/), [DNS-01 Let's Encrypt](https://letsencrypt.org/docs/challenge-types/), [staging Let's Encrypt](https://letsencrypt.org/docs/staging-environment/).
