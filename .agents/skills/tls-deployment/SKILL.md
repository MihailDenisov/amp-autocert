---
name: tls-deployment
description: Use for validating, writing, rotating, backing up, or activating AutoModpack HTTPS certificate and PKCS#8 private-key files.
---
# Safe AutoModpack certificate deployment
1. Current wiki path (verify for installed AutoModpack build): `automodpack/credentials/certificate.crt` = full PEM chain, `automodpack/credentials/private-key.pem` = unencrypted PKCS#8 PEM. Keep private ACME state under credentials, not `config/`.
2. Verify both SANs for typed Minecraft origin `play.maicraft.tech` and advertised endpoint `hm.maicraft.tech`; a `*.maicraft.tech` SAN covers both. Verify validity and CA trust through normal PKIX; check key matches the leaf certificate.
3. Do not rotate while validation is incomplete. Write separate temporary files in the destination filesystem, force sync where practical, set restrictive permissions, retain a known-good backup, and replace carefully with a rollback plan (two-file updates are not automatically atomic as a pair).
4. Activation: confirm the exact AutoModpack version/command, dispatch host restart on the Minecraft server thread, observe success and readiness. If the API is not stable, expose a manual restart requirement rather than guessing.
5. Saved player fingerprint pins override CA trust and may require one-time removal on an operator-initiated migration. Never imply certificate renewal bypasses existing pins.
References: https://moddedmc.wiki/en/project/automodpack/latest/docs/security and https://moddedmc.wiki/en/project/automodpack/latest/docs/quick-start
