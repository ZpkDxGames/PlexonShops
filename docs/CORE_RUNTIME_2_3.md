# PlexonShops 2.3.0 — PlexonCore 2 Runtime Integration

## Scope

PlexonShops 2.3.0 upgrades its compile-time/runtime compatibility to PlexonCore 2.0.0 while preserving the optimized shop engine introduced in 2.2.0.

The released Core 2.0.0 API does not yet publish movement, damage, join, or quit subscription contracts. Accordingly, 2.3.0 keeps those event acquisitions local and exposes their ownership explicitly in diagnostics. This is intentional fallback behavior, not a failed migration hidden behind a Core label.

## Operating modes

- `CORE_RUNTIME`: PlexonCore API 2.x is compatible and module registration is active.
- `CORE_LEGACY`: PlexonCore API 1.x is compatible and module registration is active.
- `STANDALONE`: Core is absent, unavailable, incompatible, or module registration cannot be owned safely.

Player-event ownership is reported separately and is LOCAL on the PlexonCore 2.0.0 stable API.

## Configuration

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

`core-runtime.mode` changes require a restart. The reload command keeps the ownership mode selected at enable time.

## Safety

PlexonShops will not disable movement/damage warmup guards merely because Core is present. If a required Core player-event contract does not exist, the plugin keeps the known 2.2 local path when fallback is permitted. If fallback is disabled, startup fails closed.

## Domain boundary

PlexonCore does not own shop destinations, pending teleport state, warmups, bossbars, cooldowns, Vault charging/refunds, teleport execution, visit recording, persistence, GUI state, or public shop events.

The local event listener and any future Core subscriber converge on source-independent methods in `TeleportService`:

- `handleMovementFacts(...)`
- `handleDamageFacts(...)`
- `handleQuit(...)`

## Diagnostics

`/pshops diagnostics` exposes:

- Core plugin/API version and module state;
- requested runtime mode;
- movement/damage/quit/join ownership;
- runtime epoch;
- player-watch availability;
- fallback-family count;
- pending teleports and cooldowns;
- movement/damage receive, fast-reject, and cancellation counters;
- quit cancellation count;
- existing persistence/worker pressure metrics.
