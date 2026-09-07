# Changelog

All notable changes to PlexonShops are documented here.

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
