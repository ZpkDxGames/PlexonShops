# Changelog

All notable changes to PlexonShops are documented here.

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
