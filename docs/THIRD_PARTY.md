# Bundled third-party runtime dependencies

The following artifacts are embedded with NeoForge Jar-in-Jar. Hashes are from the files included in the current build.

| Artifact | Version | License | SHA-256 |
| --- | --- | --- | --- |
| `org.shredzone.acme4j:acme4j-client` | 5.1.0 | Apache-2.0 | `4a56511512f2e7829dc24fe2a2ca0ac4c6ed593c63b455b36fbdff3044294c2c` |
| `org.bitbucket.b_c:jose4j` | 0.9.6 | Apache-2.0 | `7314af50cde9c99e8eaf43eee617a23edcc6bb43036221064355094999d837ef` |
| `org.bouncycastle:bcprov-jdk18on` | 1.86 | Bouncy Castle License (MIT-based) | `2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f` |
| `org.bouncycastle:bcpkix-jdk18on` | 1.86 | Bouncy Castle License (MIT-based) | `8d8b41a4b149bdae8d331f059a578870954d1e3ff29f8269a9eb3ee19a7e4ef7` |
| `org.bouncycastle:bcutil-jdk18on` | 1.86 | Bouncy Castle License (MIT-based) | `1c268e15f785aafb1e4670d8d6e5f856f69475e96c162dadcd1ff9b350e9139f` |

acme4j upstream declares Bouncy Castle 1.83; this project pins the mutually compatible module set to 1.86 so current security fixes are included. Minecraft/NeoForge provides SLF4J 2.0.9, so no second SLF4J API is embedded.

Sources: <https://github.com/shred/acme4j>, <https://bitbucket.org/b_c/jose4j/>, and <https://github.com/bcgit/bc-java>.
