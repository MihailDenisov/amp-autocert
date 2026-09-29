---
name: neoforge-build
description: Use for NeoForge 21.1.248 Minecraft 1.21.1 Java 21 build, server lifecycle, Gradle packaging, or VS Code run configuration work.
---
# NeoForge development workflow
1. Confirm `gradle.properties`: Minecraft `1.21.1`, NeoForge `21.1.248`, Java 21, `mod_id=amp_autocert` (a hyphen is NOT a valid NeoForge ID).
2. Favor the checked-in NeoForged ModDevGradle project; confirm APIs from the official 1.21.1 docs or resolved artifacts. Do not mix 1.21.4/1.21.5 sample signatures into this project.
3. The mod is dedicated-server only. Register server lifecycle handlers on the correct event bus; never call blocking HTTP on the game thread.
4. Use the supported NeoForge Jar-in-Jar mechanism for runtime acme4j and transitives if bundled; test class loading in a dedicated NeoForge runtime. Do not assume Gradle `implementation` embeds dependencies.
5. Run `sh scripts/offline-smoke-test.sh`, then `./gradlew test build` with a networked JDK 21 environment. Finally run the dedicated server. Provide concise results and blockers.
References: https://docs.neoforged.net/docs/1.21.1/gettingstarted/ and https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle
