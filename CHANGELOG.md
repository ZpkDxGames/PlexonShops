# Changelog

All notable changes to PlexonShops are documented here.

## 2.2.0 - 2026-09-09

- Reworked marketplace directory reads around generation-cached indexes so category/filter ordering is rebuilt only when directory-relevant shop state changes.
- Added page-first directory retrieval so GUI page navigation materializes only the shops visible on the requested page instead of rebuilding the full marketplace list.
- Added O(1) owner shop counts and open-shop counts, plus cached per-owner creation ordering for primary/sub-shop presentation and capacity checks.
- Added a per-owner concurrent shop-creation guard to prevent double-click/concurrent creation races.
- Reduced teleport hot-path work with O(1) pending warmup gates, rotation-only movement rejection, monotonic cooldown timing, cached warmup display state, and a single shared bossbar progress coordinator.
- Decoupled successful teleport visit accounting from SQLite durability while preserving immediate in-memory visitor totals and exactly-once public visit-event publication.
- Added per-shop coalesced visit persistence with batched unique-visitor inserts, monotonic visitor-total updates, delete-time flushing, retry retention, and shutdown flushing.
- Replaced per-shop owner-login persistence fan-out with one indexed owner activity update while preserving ordered mutation chains.
- Added bounded-worker observability for queue depth/capacity, active workers, submitted/completed operations, rejected work, P95 latency, and oldest queued-task age.
- Added `/pshops diagnostics` for directory/cache state, pending teleports, mutation chains, creation guards, visit-flush pressure, worker pressure, and integration availability.
- Added short-lived permission-limit caching to reduce repeated numbered permission scans in GUI/capacity paths while preserving rank-driven shop-limit semantics.
- Added config-time sound-key validation/fallback so invalid Adventure keys do not repeatedly reach teleport runtime handling.
- Expanded regression coverage for cache counters/order, bounded-worker pressure metrics, SQLite owner batching, and targeted persistence behavior.
- Preserved existing shop UUIDs, owners, primary/sub-shop semantics, categories, ratings, visitor statistics, icons, descriptions, teleport fees, MiniMessage handling, PlaceholderAPI, Vault integration, PlexonCore API/events, and SQLite schema compatibility.
- No database schema migration is required for 2.2.0.

## 2.1.0 - 2026-09-07

- Added optional PlexonCore 1.x integration with isolated CORE/STANDALONE bridging and module lifecycle health.
- Registered Core module `shops` with shop engine, directory, API, event, ratings, visitors, SQLite, and Vault capabilities.
- Added stable Bukkit `PlexonShopsAPI` and immutable `ShopView` read models.
- Added exact `PlexonShopCreatedEvent`, `PlexonShopRatedEvent`, and `PlexonShopVisitedEvent` classes required by PlexonQuests 3.1.0.
- Added unique event IDs and transaction IDs for durable integration deduplication.
- Added centralized main-thread `ShopEventPublisher` so synchronous Bukkit events are never dispatched from persistence workers.
- Creation events now fire only after successful shop persistence and cache insertion.
- Rating events now fire only after successful rating persistence and cache replacement, with previous/current rating metadata.
- Visit recording now exposes asynchronous completion and emits a visit event only after successful teleport, visit persistence, and cache replacement.
- Preserved existing SQLite/Hikari data, GUI behavior, sub-shops, ratings, visitor statistics, teleport cooldowns, Vault charge/refund semantics, PlaceholderAPI, and MiniMessage formatting.
- Added pinned PlexonCore 1.0.0 CI provisioning, Core no-shading verification, event/API distribution verification, and public event contract tests.
- Added API, PlexonCore, and 2.1 migration documentation.

## 2.0.0 - 2026-09-01

- Updated the build, bytecode, and plugin metadata for Paper 26.2 and Java 25.
- Fixed formatted shop names in teleport, creation, deletion, rating, and GUI messages by inserting safely parsed components instead of raw MiniMessage source.
- Added a restricted player-text formatter with legacy color conversion, Bungee hex support, visible-character limits, safe fallbacks, and click/hover tag protection.
- Reworked `/pshops manage` into a persistent shop control center with primary/sub-shop labels, permission capacity, detailed cards, quick owner teleports, and richer shop statistics.
- Added `plexonshops.subshops.N` permission limits while retaining legacy `plexonshops.limit.N` nodes.
- Added configurable teleport cooldowns, owner/permission bypasses, damage cancellation, bossbar progress, sounds, particles, and arrival actionbars.
- Added in-memory fallback to bundled 2.0 messages so upgraded servers retain custom files without showing missing-message errors.
- Embedded both HikariCP and SQLite JDBC in the installable JAR and added distribution-content verification.
- Added MiniMessage regression tests and expanded the release verification pipeline.

## 1.0.0 - 2026-08-28

- Added the global player-shop directory with pagination and category filters.
- Added GUI-first shop creation, management, status, categories, icons, labels, and location editing.
- Added interactive one-to-five-star ratings and visitor statistics.
- Added cancellable teleport warmups with optional Vault economy fees.
- Added asynchronous SQLite persistence through a bounded worker and HikariCP.
- Added an internal PlaceholderAPI expansion and MiniMessage-native messages.
