# Architecture

## Runtime boundaries

Paper-owned state is read or changed on the server thread: inventories, players,
locations, item serialization, Vault calls, and teleport requests. SQL, schema
setup, configuration reload reads, and shutdown persistence use a fixed-size,
bounded worker. No command or inventory event waits for storage.

Shop aggregates are immutable. `ShopCache` publishes complete replacements via
concurrent maps, allowing PlaceholderAPI and GUI readers to observe consistent
snapshots. A failed write rolls back its matching optimistic cache replacement.

## PlexonCore and event acquisition

PlexonShops isolates PlexonCore types behind `integration/core`. The always-loaded
shop domain does not expose Core runtime types in its public API.

PlexonCore 2.0.0 module registration is supported, but stable Core 2.0.0 does not
provide public player movement, damage, join, or quit subscriptions. These event
families therefore remain owned by the optimized local listeners. Runtime ownership
is selected and reported by `CoreRuntimeShopsBridge` independently of the module's
Core registration state.

The local teleport listener performs the O(1) pending UUID gate before configuration
or domain work. Local and future Core sources converge on source-independent
`TeleportService` movement/damage/quit fact processors; cancellation policy is not
duplicated in the adapters.

GUI inventory events and chat prompt events are intentionally Shops-local because
they are module-specific rather than shared ecosystem facts.

## Persistence

SQLite runs in WAL mode with foreign keys, normal synchronous mode, a busy
timeout, and HikariCP connection management. Shop, rating, unique-visitor, and
labeled-item updates are committed in one transaction. Every value is bound
through prepared statements.

Tables:

- `shops`: identity, owner, location, state, categories, icon, description, fee,
  counters, and activity timestamps
- `ratings`: one current rating per player and shop
- `visitors`: one first-visit row per unique visitor and shop
- `labeled_items`: serialized showcase items owned by a shop

The 2.3 Core runtime migration does not move SQLite, cache, visitor coalescing, owner
activity batching, or the bounded persistence worker into PlexonCore.

## GUI flow

The inventory holder stores a typed screen context and direct slot-to-UUID
targets. Click handling never depends on inventory titles or item display text.
All GUI slots and content grids are validated against their configured inventory
size before use.

Free-form shop names, descriptions, labels, and fees begin from GUI buttons and
use one-shot, expiring chat prompts. Player formatting is restricted to colors,
gradients, rainbows, and decorations; event-producing tags are never resolved.
Players without `plexonshops.format` have both MiniMessage and converted legacy
color tags escaped before storage. Display limits count visible code points,
not markup bytes, and corrupt legacy values render as literal text instead of
breaking a GUI or chat message.

The oldest owned shop is presented as the primary shop and later shops as
sub-shops. This is derived from the stable creation order, so the 2.x upgrade
requires no destructive database migration. `plexonshops.subshops.N` represents
additional capacity; legacy total-limit permissions remain accepted.

## Teleports and economy

Warmups remember the player's exact position and cancel on meaningful movement or
uncancelled damage when configured. Rotation-only move events are ignored and the
movement threshold remains `squaredDistance > 1.0E-6` relative to the warmup origin.

An Adventure bossbar reports remaining time, while configurable sounds and particles
communicate departure and arrival. One shared coordinator updates all active warmup
bossbars and stops when idle.

Successful teleports start a per-player monotonic cooldown unless an owner/configuration
or permission bypass applies. At completion, the shop and destination are revalidated,
Vault is charged on the server thread, and Paper `teleportAsync` loads and transfers
safely. A failed teleport refunds the exact amount withdrawn. Only a successful shop
teleport records a visit and publishes the public visit event.
