# Migration to PlexonShops 2.1.0

PlexonShops 2.1.0 is a conservative Core-integration release built on the existing 2.0.0 production architecture.

## Upgrade

1. Stop the Paper server.
2. Back up `plugins/PlexonShops/`.
3. Replace `PlexonShops-2.0.0.jar` with `PlexonShops-2.1.0.jar`.
4. Keep the existing `plugins/PlexonShops/` directory unchanged.
5. Start the server and validate `/pshops`, `/plexon modules`, and `/quests diagnostics` where those integrations are installed.

No database schema migration is required by 2.1.0. Existing shop UUIDs, owners, locations, categories, statuses, ratings, visitors, icons, descriptions, labeled items, teleport fees and timestamps are preserved.

## What changed

- Optional PlexonCore 1.x module registration was added.
- A stable Bukkit `PlexonShopsAPI` and immutable `ShopView` were added.
- Created, Rated and Visited Bukkit events were added using the exact class names expected by PlexonQuests 3.1.0.
- Event publication now occurs only after the authoritative persistence/cache boundary.
- Visit recording exposes asynchronous completion internally so a successful teleport cannot progress a Shops quest until visit persistence succeeds.
- CI verifies Java 25/Paper 26.2, the pinned Core API artifact, event/API classes, and absence of shaded Core classes.

## What did not change

The 2.0.0 shop GUI, shop/sub-shop model, limits, ratings behavior, visitor statistics, teleport warmups, cooldowns, Vault charges/refunds, PlaceholderAPI expansion, MiniMessage formatting, SQLite/Hikari storage and existing configuration files remain in place.

## Quests interoperability

With PlexonQuests 3.1.0 installed, the expected diagnostic changes from `PLEXON_SHOPS UNAVAILABLE_MISSING_API` to `PLEXON_SHOPS AVAILABLE` because these classes now exist:

- `com.plexon.shops.event.PlexonShopCreatedEvent`
- `com.plexon.shops.event.PlexonShopRatedEvent`
- `com.plexon.shops.event.PlexonShopVisitedEvent`

Create/rate/visit actions should contribute exactly once only after successful committed gameplay state.

## Rollback

Because 2.1.0 does not require an irreversible schema migration, rollback is straightforward: stop the server, restore the 2.0.0 plugin JAR, restore the data backup only if independently necessary, and start the server.
