# PlexonShops

[![Build](https://github.com/ZpkDxGames/PlexonShops/actions/workflows/build.yml/badge.svg)](https://github.com/ZpkDxGames/PlexonShops/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ZpkDxGames/PlexonShops)](https://github.com/ZpkDxGames/PlexonShops/releases/latest)
[![License](https://img.shields.io/github/license/ZpkDxGames/PlexonShops)](LICENSE)

PlexonShops **3.0.0** is PlexonCraft's player-owned shop discovery and directory plugin for Paper 26.2. It owns player-shop browsing, search/filtering, categories, featured shops, favorites, recent shops, profiles, ratings, showcased items, owner listing management, safe shop visits, and teleport fee/refund orchestration.

PlexonShops is **not** the server-owned item economy storefront. DynamicShop remains the authoritative `/shop` buy/sell product backed by TheosisEconomy/Vault; PlexonShops does not duplicate item pricing, stock, purchase quantities, payouts, or server-commerce transactions.

## Runtime baseline

| Component | Version / role |
| --- | --- |
| Paper | `26.2.build.121-stable` |
| Java | 25 |
| PlexonCore | Optional runtime integration; build API pinned to `2.0.4` |
| Vault + economy provider | Optional; required when paid shop visits are enabled unless explicitly configured fail-open |
| PlaceholderAPI | Optional |
| SQLite schema | 3 |

PlexonCore, Vault, PlaceholderAPI, Paper/Bukkit, Adventure, and LuckPerms APIs are provided externally and are not shaded. HikariCP and SQLite JDBC are bundled in the installable JAR.

## Player experience

`/pshops` opens the player-shop directory. The Phase 3 experience includes:

- Featured, Browse All, Categories, Search, Favorites, Recent, and My Shops surfaces;
- paginated directory cards with status, specialization, rating, showcase examples, location/distance, and visit fee;
- detailed shop profiles with favorite/rating/showcase/visit actions;
- OPEN, CLOSED, and MAINTENANCE availability states;
- primary shops plus permission-controlled sub-shops;
- one-to-five-star ratings and total/unique visitor statistics;
- cached in-memory discovery reads and explicit stale-state rejection.

## Teleport and economy safety

`TeleportService` is the sole authority for visit warmup, final availability revalidation, Vault charge/refund, Paper asynchronous teleport submission, cooldown installation, and successful-visit publication.

The transaction boundary is intentionally strict:

- one accepted warmup or in-flight visit per player;
- movement/damage/logout can cancel a still-pending warmup;
- the destination and shop state are revalidated immediately before charge;
- the fee is charged only after the in-flight reservation is acquired;
- once `teleportAsync` starts, logout does **not** revoke the attempt or refund it;
- failed/exceptional async completion owns the refund;
- successful completion owns visit/cooldown settlement;
- stale callbacks cannot settle a newer attempt;
- player-facing success/failure effects are skipped if the player is already offline.

This avoids the refunded-successful-teleport race fixed during the final 3.0 stable audit.

## Persistence and performance

- SQLite WAL persistence through a bounded background executor;
- fail-closed startup when the database schema is newer than supported;
- migration backup before older schemas advance to schema 3;
- serialized per-shop mutation chains;
- coalesced visit persistence and shutdown flushing;
- generation-guarded asynchronous discovery loads;
- cached directory snapshots and page-first materialization;
- O(1) owner/open-shop counters and pending-teleport gates;
- one shared warmup/bossbar coordinator rather than one repeating task per teleport;
- bounded diagnostics for worker queue pressure and visit-persistence pressure.

## Installation / upgrade

1. Back up `plugins/PlexonShops/`, especially the SQLite database.
2. Download `PlexonShops-3.0.0.jar` and `SHA256SUMS.txt` from the matching stable GitHub release.
3. Verify the release JAR with `sha256sum --check SHA256SUMS.txt`.
4. Replace the previous PlexonShops JAR.
5. Start Paper 26.2 on Java 25.
6. Run `/pshops diagnostics` and validate shop discovery plus representative free/paid visits before reopening normal traffic.

For `2.2.1 -> 3.0.0`, see `docs/MIGRATION_2.2.1_TO_3.0.0.md` and `docs/RUNTIME_CERTIFICATION_3.0.0.md`.

Rollback baseline for this stable closure is `v2.2.1` at `81c77e936194de2d46fd40221ea57a1f65c07b34`.

## Commands

| Command | Purpose |
| --- | --- |
| `/pshops` | Open the player-shop directory |
| `/pshops manage` | Create or manage owned shops |
| `/pshops diagnostics` | Show runtime/cache/worker/persistence/integration health |
| `/pshops reload` | Reload runtime configuration and messages |

Aliases: `/pshop`, `/playershops`.

## Permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `plexonshops.use` | Everyone | Browse, visit, favorite, and rate |
| `plexonshops.create` | Nobody | Create a primary shop and manage owned shops |
| `plexonshops.admin` | Operators | Administrative capabilities |
| `plexonshops.reload` | Operators | Reload runtime configuration/messages |
| `plexonshops.format` | Operators | Use allowed MiniMessage formatting in owned shop text |
| `plexonshops.subshops.<number>` | Nobody | Grant additional sub-shops |
| `plexonshops.subshops.unlimited` | Nobody | Bypass the sub-shop limit |
| `plexonshops.limit.<number>` | Nobody | Legacy total-shop limit |
| `plexonshops.limit.unlimited` | Nobody | Legacy unlimited total-shop limit |
| `plexonshops.teleport.cooldown.bypass` | Operators | Bypass visit cooldowns |
| `plexonshops.teleport.fee.bypass` | Nobody | Bypass visit fees |

## Public API and events

PlexonShops registers `com.plexon.shops.api.PlexonShopsAPI` through Bukkit's `ServicesManager` in Core and standalone modes. It exposes immutable shop views and read-only directory/count access without leaking repository/cache internals.

Public integration events include:

- `com.plexon.shops.event.PlexonShopCreatedEvent`
- `com.plexon.shops.event.PlexonShopRatedEvent`
- `com.plexon.shops.event.PlexonShopVisitedEvent`

The visit event is emitted only after a successful shop teleport has been accepted as the authoritative terminal result.

See `docs/API.md`, `docs/ARCHITECTURE.md`, and `docs/PLEXONCORE.md` for detailed contracts.

## PlaceholderAPI

The internal expansion identifier is `plexonshops`, including values such as:

- `%plexonshops_total%`
- `%plexonshops_open%`
- `%plexonshops_owned%`
- `%plexonshops_subshops%`
- `%plexonshops_name%`
- `%plexonshops_primary_name%`
- `%plexonshops_status%`
- `%plexonshops_rating%`
- `%plexonshops_visitors%`
- `%plexonshops_unique_visitors%`

## Build and stable-release verification

Local build:

```bash
./gradlew --no-daemon clean test check javadoc shadowJar verifyDistribution
```

The installable artifact is written to:

```text
build/libs/PlexonShops-3.0.0.jar
```

Canonical CI verifies accepted Phase 3 and RC2 ancestry, Java 25/class-major 69, Paper 26.2, checksum-pinned PlexonCore 2.0.4, non-empty all-green tests, required JAR contents, plugin/manifest version parity, no shaded provided API trees, SHA-256, and exact-source provenance.

Stable publication runs only from `release/stable` when that branch points to exact current `main`. It rebuilds and retests that source, publishes the JAR plus `SHA256SUMS.txt`, `TEST_SUMMARY.txt`, and `PROVENANCE.txt`, downloads those published assets, and verifies their checksum and source provenance before the workflow can succeed.

Live PlexonCraft migration/teleport/economy/Spark/soak certification is a deployment follow-up and may be recorded as `runtime_certification=NOT_EXECUTED` in GitHub release provenance.

## Data and privacy

Shop data is stored locally under `plugins/PlexonShops/`. PlexonShops opens no network listener and sends no player/shop data to an external service. Back up the plugin data directory before upgrades.

## License

PlexonShops is released under the [MIT License](LICENSE).

Created by [Tonim / ZpkDxGames](https://github.com/ZpkDxGames) for the Plexon ecosystem.
