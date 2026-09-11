# PlexonShops 3.0.0 Phase 2 hardening

## Teleport transaction lifecycle

One player owns at most one accepted shop teleport at a time. Warmup is mutually exclusive with another warmup or an in-flight teleport. When warmup completes, the same main-thread turn acquires an identity-bound in-flight reservation before any Vault charge.

A warmup can be cancelled by movement, damage, logout, or shutdown before `teleportAsync` starts. Once Paper's asynchronous teleport has started, its exact attempt remains authoritative until terminal completion. Logout does not revoke that attempt or refund its fee: failed/exceptional completion owns refund settlement, while successful completion publishes one visit, installs one cooldown, and then releases ownership. Stale callbacks from superseded attempts are ignored. No blocking wait is introduced around Paper `teleportAsync`.

Shutdown remains a separate fail-safe boundary because the plugin can no longer rely on a future main-thread callback after disable; outstanding attempts are drained and compensated during shutdown.

## Schema 3 compatibility

PlexonShops reads `plexonshops_schema` before migration, backup, Hikari initialization, DDL, or schema-marker writes. Versions below 3 follow the forward migration/backup path. Version 3 starts normally. Any version above 3 fails closed and is never rewritten down to 3.

## Discovery cache generations

Async player-discovery loads capture player, global invalidation, and shop-directory generations. Returned data is published only if all generations remain current. Shop mutations/reload are covered by the authoritative `ShopService.directoryGeneration`; deletion adds an explicit discovery invalidation; player unload and concurrent visit/favorite mutations advance the player generation. Stale results are discarded without retry loops.

## Runtime boundary

Automated source/release closure and live PlexonCraft certification are separate gates. Stable `v3.0.0` requires exact-source CI, tests, distribution verification, accepted Phase 3/RC2 ancestry, immutable stable publication, and downloaded-asset verification. PlexonCraft migration, Vault/Theosis behavior, Spark/MSPT comparison, and soak testing remain a deployment follow-up and may be recorded as `runtime_certification=NOT_EXECUTED` in stable release provenance.
