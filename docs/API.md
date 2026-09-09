# PlexonShops Public API

PlexonShops 2.3.0 preserves the stable Bukkit service API introduced in 2.1 under `com.plexon.shops.api`. The Core 2 runtime migration does not add PlexonCore types to public method signatures or change the public event contract.

## Service lookup

```java
var registration = Bukkit.getServicesManager().getRegistration(PlexonShopsAPI.class);
if (registration == null) return;
PlexonShopsAPI shops = registration.getProvider();
```

The service is registered in Core Runtime, Core Legacy, and standalone operation after the existing SQLite shop cache has loaded successfully. It is unregistered during plugin shutdown.

## Read API

`PlexonShopsAPI` provides:

- `Optional<ShopView> shop(UUID shopId)`
- `List<ShopView> shopsOwnedBy(UUID ownerId)`
- `List<ShopView> directory()`
- `int totalShops()`
- `int openShops()`

`ShopView` is an immutable view. The public API does not expose mutable `Shop`, `ShopCache`, repository, HikariCP, SQLite, or PlexonCore runtime implementation objects.

## Public Bukkit events

The following exact event classes remain part of the 2.3 public contract:

- `com.plexon.shops.event.PlexonShopCreatedEvent`
- `com.plexon.shops.event.PlexonShopRatedEvent`
- `com.plexon.shops.event.PlexonShopVisitedEvent`

Each event extends `PlayerEvent`, has standard Bukkit handler methods, and exposes Quests-compatible accessors for player, shop ID, shop type, event ID and transaction ID. Rated events also expose the submitted rating and previous rating.

The stable shop type remains `player`.

## Event semantics

Events describe successful shop-domain state, not Core activity facts, requests, or GUI previews.

- Created: fires only after the new shop has been saved and inserted into the live cache.
- Rated: fires only after rating persistence succeeds and the cache contains the updated rating.
- Visited: fires only after the Paper teleport succeeds and the Shops visit state is accepted by the domain pipeline.

A Core/local movement callback never means that a shop was visited. Cancelled warmups, failed teleports, economy failures, and refund paths emit no visit event.

## Threading

SQLite work remains asynchronous. `ShopEventPublisher` schedules synchronous Bukkit event dispatch onto the primary server thread after the relevant domain state transition. Consumers may use normal Bukkit listeners without depending on the Shops I/O executor.

Each successful public event carries a non-empty transaction ID and a unique event ID derived from that transaction, for example `<uuid>:visited`. These IDs are intended for durable integration deduplication.

## 2.3 compatibility statement

PlexonShops 2.3.0 does not intentionally break `PlexonShopsAPI`, `ShopView`, or the Created/Rated/Visited event classes. Player movement/damage/lifecycle acquisition is an internal implementation detail and is not exposed through this API.
