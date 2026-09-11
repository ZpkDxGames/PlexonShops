# PlexonShops Phase 3 — Directory UX Overhaul

## Product boundary

PlexonShops is the player-owned shop discovery and directory product. It owns discovery, browse/search/filter, categories, featured shops, favorites, recent shops, shop profiles, ratings, showcased items, owner listing management, safe teleportation, and teleport fee/refund integration.

PlexonShops is **not** PlexonCraft's server-owned item economy storefront. DynamicShop remains the server-wide item buy/sell `/shop` product backed by TheosisEconomy/Vault. Phase 3 does not add item prices, stock, buy/sell modes, purchase quantities, payouts, or another economy transaction engine to PlexonShops.

## Phase 3 player journey

`DISCOVER -> FILTER/SEARCH -> REVIEW SHOP -> REVIEW SHOWCASE -> VISIT -> FAVORITE/RATE -> RETURN`

The root directory now prioritizes Featured, Browse All, Categories, Search, Favorites, Recent, My Shops, and a compact recommended-shop row. Shop cards show specialization, rating, showcase examples, availability, location/distance, and visit fee before the player opens the profile.

The Shop Profile is the central detail surface. It separates identity/description, overview, availability, location, showcase, favorite state, rating, and the primary Visit action. Owner editing is presented separately from visitor actions and still delegates to the mature owner-management authority.

## Teleport transaction boundary

Paid visits use a presentation-only confirmation screen showing destination, authoritative configured teleport fee, current Vault balance snapshot, and readiness. The GUI never withdraws or deposits money. Confirmation calls the existing `TeleportService`, which remains the only owner of charge/refund, warmup, duplicate-attempt prevention, destination revalidation, teleport completion, and visit publication.

Free visits skip the extra confirmation screen. Stale shop revisions are rejected/refreshed before a GUI action can submit obsolete shop data.

## Performance architecture

The Phase 3 GUI follows:

`authoritative cache snapshot -> immutable per-open view model -> cached static/item templates -> paginated rendering -> changed screen`

Directory/search/category/favorite/recent reads use existing cache-backed services. Search is explicit-submit and runs against the in-memory directory, including showcase labels. The renderer owns no repository, no database connection, and no repeating Bukkit task.

Static buttons/fillers and unchanged serialized shop/showcase item templates are cached. Rating/category aggregation is performed once while constructing the per-open view model rather than repeatedly per lore line.

## Compatibility

No database schema changes are introduced. Existing shop UUIDs, owners, descriptions, categories, statuses, ratings, visitors, showcased items, locations, teleport fees, favorites, recent visits, featured state, API/event contracts, and Vault transaction ownership remain unchanged.

The bundled Phase 3 configuration provides new 54-slot directory layouts. An existing Phase 2 configuration is still accepted: the renderer retains compact compatibility for the previous 27-slot discovery hub/profile and uses safe fallback layouts for new screens.
