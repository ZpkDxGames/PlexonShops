# PlexonCore Integration

PlexonShops 2.3.0 supports both the legacy PlexonCore 1.x module API and the stable PlexonCore 2.x Runtime API through an isolated bridge in `com.plexon.shops.integration.core`.

## Core modes

- **CORE_RUNTIME**: PlexonCore API `>=2.0 <3.0` is compatible and the Shops module owns its Core registration.
- **CORE_LEGACY**: PlexonCore API `>=1.0 <2.0` is compatible and the Shops module owns its Core registration.
- **STANDALONE**: PlexonCore is absent, disabled, unavailable, incompatible, or module registration cannot be owned safely. Shop gameplay, SQLite storage, the public Shops API, and public shop events continue to work.

PlexonCore is a compile-only/provided dependency. Its runtime classes are never shaded into the PlexonShops installable JAR.

## Player-event ownership in Core 2.0.0

The actual stable PlexonCore 2.0.0 runtime exposes a shared block-event gateway, but does not publish public subscriptions for player movement, player damage, join, or quit facts.

PlexonShops therefore keeps these event acquisitions LOCAL in 2.3.0. This is reported separately from the Core module mode so `CORE_RUNTIME` does not falsely imply that movement/damage/lifecycle are routed by Core.

The optimized local path preserves the 2.2 behavior:

- `PlayerMoveEvent` at `MONITOR`, `ignoreCancelled=true`;
- O(1) pending UUID fast gate;
- no cancellation for yaw/pitch-only changes;
- `1.0E-6` squared movement epsilon;
- cross-world movement cancellation when enabled;
- player damage at `MONITOR`, `ignoreCancelled=true`;
- silent pending-warmup cleanup on quit.

The shop domain now consumes source-independent movement/damage/quit facts so a future Core player-event API can feed the same logic without duplicating policy.

## Module registration

Core module identity:

- id: `shops`
- display name: `PlexonShops`
- legacy range: `>=1.0 <2.0`
- runtime range: `>=2.0 <3.0`

Published capabilities describe the shop engine, player/sub-shops, directory, public API/events, ratings, visitors, SQLite persistence, Vault teleport fees, teleport warmups, and local lifecycle fallback.

Normal lifecycle:

`STARTING -> READY`

Recoverable provider problems publish `DEGRADED`. Forced `CORE` player-event ownership also publishes `DEGRADED` when Core lacks the required public contracts and local fallback is allowed. Initialization/database failures publish `FAILED`. Shutdown unregisters the module.

## Runtime ownership configuration

```yaml
core-runtime:
  mode: AUTO
  player-events:
    movement: true
    damage: true
    lifecycle: true
  fallback:
    allow-local-listeners: true
  diagnostics:
    track-fallbacks: true
```

Ownership is selected at plugin enable. Changes to `core-runtime` require a restart; `/pshops reload` preserves the active ownership mode and reloads normal shop settings.

If local fallback is disabled while Core cannot provide the required player-event contracts, PlexonShops fails closed during startup instead of silently losing teleport warmup protection.

## Build provisioning

CI downloads the official `PlexonCore-2.0.0.jar`, verifies SHA-256:

`179b82ce7fd82e3095b3a6d0d893af6d7c97f91bd7ce403e96d7f3581462dfec`

and installs it into the runner-local Maven repository as `com.zpkdxgames:PlexonCore:2.0.0` before Gradle compilation.

Distribution verification fails if any `com/zpkdxgames/plexoncore/` class is found inside the installable Shops JAR.

## Diagnostics

`/pshops diagnostics` reports Core plugin/API versions, Core registration state, requested runtime mode, movement/damage/quit/join ownership, player-watch availability, runtime epoch, local fallback-family count, and teleport activity counters.

See `CORE_RUNTIME_2_3.md` and `CORE_RUNTIME_2_3_AUDIT.md` for the complete 2.3 decision and listener audit.
