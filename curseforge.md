# amp-autocert

## English

Server-only NeoForge mod for Minecraft 1.21.1 that obtains and renews publicly trusted Let's Encrypt TLS certificates for AutoModpack through Cloudflare DNS-01. Designed for Pterodactyl and other managed hosts where Certbot/cron are unavailable and DNSSEC cannot be used for AutoModpack's AMP1 trust path. No OS-level ACME tooling or DNSSEC is required; Cloudflare must be authoritative for your DNS zone.

Version `0.2.0-rc.1` is a **prerelease**. Initial staging validation still requires an operator command; stable `v0.1.0` is the live-validated renewal reference.

Requires Java 21, NeoForge 21.1.248, AutoModpack 5.0.0-rc.1 or rc.2, matching public Cloudflare NS delegation, and a zone-scoped DNS Edit token. AutoModpack must terminate TLS itself. Both the Minecraft join hostname and advertised download hostname must be the zone apex or a one-label subdomain. The mod requests apex and wildcard SANs, validates the zone in Let's Encrypt staging before first production enrollment, and preserves the old certificate on failures. Initial validation requires `/ampautocert reconcile confirm`; routine renewals do not repeat staging. Staging certificates are never deployed. Existing manual client fingerprint pins may need one-time removal.

Before production use, read `docs/PRODUCTION_SETUP.md` in the [project repository](https://github.com/MihailDenisov/amp-autocert).

## Русский

Серверный мод NeoForge для Minecraft 1.21.1: получает и продлевает публично доверенные TLS-сертификаты Let's Encrypt для AutoModpack через Cloudflare DNS-01. Предназначен для Pterodactyl и другого панельного хостинга без Certbot/cron и без возможности использовать DNSSEC для механизма AMP1. Системные ACME-утилиты и DNSSEC не нужны; Cloudflare должен быть авторитетным DNS зоны.

Версия `0.2.0-rc.1` — **предрелиз**. Первая staging-проверка пока требует команды оператора; стабильная `v0.1.0` служит эталоном с живой проверкой продления.

Нужны Java 21, NeoForge 21.1.248, AutoModpack 5.0.0-rc.1 или rc.2, правильное NS-делегирование на Cloudflare и токен DNS Edit только для этой зоны. AutoModpack должен сам завершать TLS. Адрес игроков и объявленный адрес загрузки должны быть apex или односегментными поддоменами зоны. Мод запрашивает apex и wildcard SAN, проверяет зону через staging Let's Encrypt до первого production-выпуска и сохраняет старый сертификат при ошибках. Для первой проверки нужен `/ampautocert reconcile confirm`; при обычном продлении staging не повторяется. Staging-сертификаты не развёртываются. Старые ручные fingerprint-pin'ы клиентов иногда нужно удалить один раз.

Перед production прочитайте `docs/PRODUCTION_SETUP_RU.md` в [репозитории проекта](https://github.com/MihailDenisov/amp-autocert).
