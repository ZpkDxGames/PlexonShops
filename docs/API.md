# PlexonShops Public API

PlexonShops 2.1.0 exposes a stable Bukkit service API from `com.plexon.shops.api`.

## Service lookup

```java
var registration = Bukkit.getServicesManager().getRegistration(PlexonShopsAPI.class);
if (registration == null) return;
PlexonShopsAPI shops = registration.getProvider();
```

The service is registered in both PlexonCore and standalone modes after the existing SQLite shop cache has loaded successfully. It is unregistered during plugin shutdown.

## Read API

`PlexonShopsAPI` provides:

- `Optional<ShopView> shop(UUID shopId)`
- `List<ShopView> shopsOwnedBy(UUID ownerId)`
- `List<ShopView> directory()`
- `int totalShops()`
- `int openShops()`

`ShopView` is an immutable view. The public API does not expose mutable `Shop`, `ShopCache`, repository, HikariCP, or SQLite implementation objects.

## Public Bukkit events

The following exact event classes are part of the 2.1 public contract:

- `com.plexon.shops.event.PlexonShopCreatedEvent`
- `com.plexon.shops.event.PlexonShopRatedEvent`
- `com.plexon.shops.event.PlexonShopVisitedEvent`

Each event extends `PlayerEvent`, has standard Bukkit handler methods, and exposes Quests-compatible accessors for player, shop ID, shop type, event ID and transaction ID. Rated events also expose the submitted rating and previous rating.

All events use the stable shop type `player` in 2.1.0.

## Event semantics

Events describe committed gameplay state, not requests or GUI previews.

- Created: fires only after the new shop has been saved and inserted into the live cache.
- Rated: fires only after rating persistence succeeds and the cache contains the updated rating.
- Visited: fires only after the Paper teleport succeeds and visit persistence/cache update succeeds.

Failed persistence, invalid ratings, self-ratings, cancelled warmups, failed teleports, economy failures and refund paths do not emit their corresponding public shop event.

## Threading

SQLite work remains asynchronous. `ShopEventPublisher` schedules Bukkit event dispatch onto the primary server thread after persistence completion. Consumers may use normal synchronous Bukkit listeners without depending on the Shops I/O executor.

Each successful public event carries a non-empty transaction ID and a unique event ID derived from that transaction, for example `<uuid>:visited`. These IDs are intended for durable integration deduplication.
