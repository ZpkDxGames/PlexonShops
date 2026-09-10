# PlexonShops 3.0.0 — Phase 2 Execution Specification

## Release boundary

- Published rollback baseline: `v2.2.1`
- Baseline commit: `81c77e936194de2d46fd40221ea57a1f65c07b34`
- Phase 2 branch: `phase2/3.0.0-premium-discovery`
- Target: `3.0.0`
- Candidate: `v3.0.0-rc.1`
- Stable promotion is blocked on PlexonCraft runtime certification.

## Repository audit

The published 2.2.1 source already provides Java 25, Gradle Kotlin, PlexonCore 2.0.4 compile-only integration, Vault economy, PlaceholderAPI, Hikari/SQLite, immutable UUID-backed shop aggregates, per-owner indexes, cached/paginated directory snapshots, ratings, unique visitor statistics, labeled display items, multi-shop ownership, GUI editing, a bounded DB worker, ordered per-shop mutation chains, `teleportAsync`, refund-on-failure, and visit publication only after successful teleport.

An open unmerged 2.3.0 PR exists, but it diverges from the 2.2.0 lineage and is not a valid Phase 2 base. It remains untouched.

No WorldGuard/GriefPrevention hard integration exists in 2.2.1. Protection interoperability therefore remains an extension concern rather than a new hard dependency.

## Why 3.0.0

Phase 2 intentionally changes the user-facing directory/config/API contract rather than merely adding an isolated feature. The major boundary covers a discovery-first browser/profile flow, authoritative availability semantics, persisted favorites/recent activity/featured metadata, staged destructive confirmations, strict candidate configuration validation, schema versioning/migration diagnostics, and expanded public discovery views. Existing shop IDs, ownership, ratings, locations, statistics, Vault accounting and runtime persistence architecture remain compatible.

## Product contract

Player flow:

`DISCOVER -> REVIEW -> CHECK STATUS -> TELEPORT -> VISIT/TRADE -> FAVORITE/RETURN`

Owner flow:

`CREATE -> CONFIGURE -> PRESENT -> MANAGE -> MONITOR -> OPEN/CLOSE`

The root `/pshops` GUI becomes a discovery hub with browse, categories, search, favorites, recent visits, featured shops, owner management and help. Existing `/pshops manage`, diagnostics and reload compatibility remains.

## Architecture changes

### Availability

Introduce one `ShopAvailabilityResolver` used by GUI and teleport paths. It derives an immutable availability result from current shop status, destination finiteness/world resolution and blacklist policy. Persisted 2.x statuses remain compatible. Invalid/unavailable shops fail closed.

### Discovery metadata

Add additive SQLite schema for:

- `plexonshops_schema` version marker
- `shop_features(shop_id, featured, updated_at)`
- `shop_favorites(player_uuid, shop_id, created_at)`
- `shop_recent_visits(player_uuid, shop_id, last_visited_at)`

All rows reference stable shop UUIDs. Shop deletion cascades discovery metadata. Recent history is bounded per player. Favorites are one row per player/shop. Featured is admin-controlled.

A bounded in-memory `DiscoveryService` supplies GUI/PAPI/API reads without synchronous SQLite queries. Per-player discovery data is loaded asynchronously and cached with bounded LRU eviction. Mutations update persistence asynchronously through the existing bounded worker.

### Teleport coordinator

Remove the delayed Bukkit task allocated per warmup. One shared coordinator owns warmup progress and completion deadlines. Movement/damage/logout cancellation remains O(1) by pending player UUID. Charge/refund and visit ordering remain unchanged.

### Destructive confirmation

Replace one-click-equivalent delete confirmation state with actor/action/shop/revision-bound expiring tokens. Tokens are single-use and stale-state-safe. The same service is reusable for administrative transfer/reset operations.

### Configuration

`PluginConfig` becomes fail-fast for invalid candidate values instead of silently clamping material errors. Validate database paths, worker bounds, limits, teleport values, GUI sizes, slots, duplicate slots, content slots and sound keys before swap. Reload parses/builds the entire candidate first, preserves restart-only DB/worker values, and atomically swaps only after candidate validation succeeds.

### Search and browser

Directory search uses immutable cached shop summaries and never queries SQLite from an inventory click. Search is explicit-submit rather than per-keystroke. Filtering supports name/owner/category/open/featured/favorite where relevant.

### API and PlaceholderAPI

Preserve existing API methods/placeholders. Add immutable availability/discovery views and compatibility-safe placeholders for shop count, primary shop state/visits and favorites count. Placeholder evaluation remains cache-only.

## Migration

2.2.1 -> 3.0.0 is additive. Existing `shops`, `ratings`, `visitors` and `labeled_items` rows remain authoritative and are not rewritten. On first Phase 2 startup:

1. create a timestamped database backup before schema mutation;
2. create/upgrade the schema in one idempotent transaction;
3. record schema version only after success;
4. fail closed on migration errors and keep the original database available for rollback.

No malformed legacy shop row is silently deleted.

## Tests

Expand unit/contract coverage for identity, availability, search/filter ordering, favorites, recent-history bounds, featured state, teleport success/cancellation/refund/duplicate completion, owner enforcement, staged/stale confirmation, config validation and reload rollback, migration idempotency, discovery-cache invalidation/eviction, API/PAPI compatibility, and distribution isolation.

Do not publish an exact test count until exact-candidate CI executes.

## Distribution gates

Exact candidate CI must pass:

- production/test compilation
- full tests
- Javadocs/API checks
- distribution verification
- Java 25 / class major 69
- PlexonCore non-shading
- Vault non-shading
- PlaceholderAPI non-shading
- Hikari/SQLite packaging
- whitespace checks
- candidate JAR checksum

RC publication is prerelease-only. The Phase 2 PR remains open, draft and unmerged.

## Runtime certification gates

PlexonCraft must later certify representative 2.2.1 migration, browser/profile/search/category flows, owner controls, availability parity, all warmup cancellation causes, Vault/Theosis charge/refund, post-success visit counting, favorites/featured/recent state, restart persistence, reload rollback, PAPI/API/Core interactions, Spark/MSPT comparison and a >=30 minute soak with zero HIGH/CRITICAL defects.
