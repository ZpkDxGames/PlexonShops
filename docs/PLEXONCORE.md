# PlexonCore Integration

PlexonShops 2.1.0 supports PlexonCore 1.x through an isolated bridge in `com.plexon.shops.integration.core`.

## Modes

- **CORE**: PlexonCore is enabled, its Bukkit service is available, and Core API `>=1.0 <2.0` is compatible.
- **STANDALONE**: PlexonCore is absent, disabled, unavailable, or incompatible. Shop gameplay, SQLite storage, the public Shops API, and public shop events continue to work.

PlexonCore is a compile-only/provided dependency. Its runtime classes are not shaded into `PlexonShops-2.1.0.jar`.

## Module registration

Core module identity:

- id: `shops`
- display name: `PlexonShops`
- supported Core API: `>=1.0 <2.0`

Published capabilities include the shop engine, player/sub-shops, directory, public API, created/rated/visited events, ratings, visitors, SQLite persistence, and Vault teleport fees.

The lifecycle is:

`STARTING -> READY`

Recoverable provider problems such as configured economy support without an available Vault provider publish `DEGRADED`. Initialization or database bootstrap failures publish `FAILED`. Shutdown unregisters the module so Core does not retain stale READY state.

## Build provisioning

CI downloads the official `PlexonCore-1.0.0.jar` release, verifies SHA-256 `4abce6de93293e21b31cb874734430d5bdc77de17a4c3b98fd6a9006e1f13018`, and installs it into the runner-local Maven repository as `com.zpkdxgames:PlexonCore:1.0.0` before Gradle compilation.

Distribution verification fails if any `com/zpkdxgames/plexoncore/` class is found inside the installable Shops JAR.

## Diagnostics

With compatible Core installed, `/plexon modules` should report PlexonShops as READY after the database/cache/API bootstrap completes. With Core absent, PlexonShops starts in standalone compatibility mode without linkage errors.
