# Architecture

## Runtime boundaries

Paper-owned state is read or changed on the server thread: inventories, players,
locations, item serialization, Vault calls, and teleport requests. SQL, schema
setup, configuration reload reads, and shutdown persistence use a fixed-size,
bounded worker. No command or inventory event waits for storage.

Shop aggregates are immutable. `ShopCache` publishes complete replacements via
concurrent maps, allowing PlaceholderAPI and GUI readers to observe consistent
snapshots. A failed write rolls back its matching optimistic cache replacement.

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

## GUI flow

The inventory holder stores a typed screen context and direct slot-to-UUID
targets. Click handling never depends on inventory titles or item display text.
All GUI slots and content grids are validated against their configured inventory
size before use.

Free-form shop names, descriptions, labels, and fees begin from GUI buttons and
use one-shot, expiring chat prompts. Players without `plexonshops.format` have
MiniMessage tags escaped before storage.

## Teleports and economy

Warmups remember the player's block position and cancel on block movement.
At completion, the shop and destination are revalidated, Vault is charged on
the server thread, and Paper `teleportAsync` loads and transfers safely. A failed
teleport refunds the exact amount withdrawn.
