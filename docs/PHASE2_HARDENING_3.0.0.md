# PlexonShops 3.0.0 Phase 2 hardening

## Teleport transaction lifecycle

One player owns at most one accepted shop teleport at a time. Warmup is mutually exclusive with another warmup or an in-flight teleport. When warmup completes, the same main-thread turn acquires an identity-bound in-flight reservation before any Vault charge. The asynchronous completion callback may mutate state only while its exact attempt object remains authoritative. Failure/cancel/logout/shutdown compensation removes ownership once and refunds a charged amount at most once. Successful completion publishes one visit, installs one cooldown, then releases ownership. Stale callbacks are ignored. No blocking wait is introduced around Paper `teleportAsync`.

## Schema 3 compatibility

PlexonShops reads `plexonshops_schema` before migration, backup, Hikari initialization, DDL, or schema-marker writes. Versions below 3 follow the forward migration/backup path. Version 3 starts normally. Any version above 3 fails closed and is never rewritten down to 3.

## Discovery cache generations

Async player-discovery loads capture player, global invalidation, and shop-directory generations. Returned data is published only if all generations remain current. Shop mutations/reload are covered by the authoritative `ShopService.directoryGeneration`; deletion adds an explicit discovery invalidation; player unload and concurrent visit/favorite mutations advance the player generation. Stale results are discarded without retry loops.

## Runtime boundary

These changes are automated source-safety evidence only. PlexonCraft runtime certification, Spark/MSPT comparison, and the soak test remain mandatory before stable `v3.0.0`.
