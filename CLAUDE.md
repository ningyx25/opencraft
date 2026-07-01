# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OpenCraft — a Fabric mod for Minecraft 1.21.11. Started from the FabricMC example-mod template and renamed to its own identity: mod id `opencraft`, base package `com.swaydy.opencraft`, display name "OpenCraft". The entrypoints and example mixins are still scaffold placeholders (the main entrypoint just logs a startup line; the mixins are no-op injections), so the first real work is replacing them with actual registrations/logic.

## Build & Run Commands

Uses the Fabric Loom Gradle plugin — specifically the `net.fabricmc.fabric-loom-remap` variant, v1.17-SNAPSHOT — via the Gradle wrapper (Gradle 9.5.1). Java 21 is the compile target; a JDK 21+ toolchain is required (CI builds with JDK 25).

| Task | Command |
|---|---|
| Full build (compile + jar + remap) | `./gradlew build` |
| Launch Minecraft client with the mod | `./gradlew runClient` |
| Launch dedicated server with the mod | `./gradlew runServer` |
| Run tests | `./gradlew test` |
| Compile only | `./gradlew compileJava compileClientJava` |
| Clean | `./gradlew clean` |

- No linter (Checkstyle/Spotless/etc.) is configured.
- No tests exist yet (`src/test/` is absent); `./gradlew test` passes vacuously.
- No custom Gradle tasks — only stock Loom tasks (`runClient`, `runServer`, `build`, `remapJar`, etc.).
- Build output jars go to `build/libs/`. CI (`.github/workflows/build.yml`) runs `./gradlew build` on push/PR and uploads `build/libs/`.

## Architecture

### Split source sets — common vs client
Loom's `splitEnvironmentSourceSets()` (in `build.gradle`) separates code into two source sets so client-only classes never ship in the dedicated-server jar:
- **Common** — `src/main/java/`, package `com.swaydy.opencraft`. Runs on both client and dedicated server. Entrypoint: `com.swaydy.opencraft.OpenCraftMod` implements `ModInitializer` (`onInitialize`).
- **Client** — `src/client/java/`, package `com.swaydy.opencraft.client`. Client-only. Entrypoint: `com.swaydy.opencraft.client.OpenCraftModClient` implements `ClientModInitializer` (`onInitializeClient`). Rendering, keybindings, client-side networking, and client mixins belong here.

Both entrypoints are registered in `src/main/resources/fabric.mod.json` under `entrypoints.main` and `entrypoints.client`.

### Mixins
Two separate mixin configs, each with `compatibilityLevel: JAVA_21` and `injectors.defaultRequire: 1`:
- **Common mixins** — `src/main/resources/opencraft.mixins.json`, package `com.swaydy.opencraft.mixin`, listed under the `mixins` array (currently `OpenCraftMixin`, a no-op `@Inject` into `MinecraftServer.loadLevel`). Add new server/common mixin classes here.
- **Client mixins** — `src/client/resources/opencraft.client.mixins.json`, package `com.swaydy.opencraft.client.mixin`, listed under the `client` array (currently `OpenCraftClientMixin`, a no-op `@Inject` into `MinecraftClient.run`), and gated to `environment: "client"` in `fabric.mod.json`. Add new client mixin classes here.

When adding a mixin class, you must also register its name in the corresponding JSON's array — an unregistered mixin will not apply. With `defaultRequire: 1`, a mixin whose target method is missing fails hard at load, so verify target method names against the current mappings before relying on one.

### Namespaced identifiers
Route every `Identifier` through the helper in `src/main/java/com/swaydy/opencraft/OpenCraftMod.java`:
```java
public static final String MOD_ID = "opencraft";
public static Identifier id(String path) {
    return Identifier.fromNamespaceAndPath(MOD_ID, path);
}
```
`MOD_ID` is the single source of truth for the namespace (the SLF4J logger is named after it too). Do not hand-build `Identifier`s with a different namespace.

### Mappings
Uses `loom.officialMojangMappings()` (official Mojang mappings, not Yarn) — set in `build.gradle`. Refer to existing source files for the exact class/import names in use (e.g. the entrypoint imports `net.minecraft.resources.Identifier`).

## Key Versions (pinned in gradle.properties)
- Minecraft: 1.21.11
- Fabric Loader: 0.19.3
- Fabric API: 0.141.4+1.21.11
- Loom: 1.17-SNAPSHOT
- Java: 21

## Conventions for New Code
- Common code → `com.swaydy.opencraft`; client-only code → `com.swaydy.opencraft.client`.
- Common mixins → `com.swaydy.opencraft.mixin` + register in `opencraft.mixins.json`; client mixins → `com.swaydy.opencraft.client.mixin` + register in `opencraft.client.mixins.json`.
- Always namespace IDs via `OpenCraftMod.id("path")`.
