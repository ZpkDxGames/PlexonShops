# PlexonShops

[![Build](https://github.com/ZpkDxGames/PlexonShops/actions/workflows/build.yml/badge.svg)](https://github.com/ZpkDxGames/PlexonShops/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ZpkDxGames/PlexonShops)](https://github.com/ZpkDxGames/PlexonShops/releases/latest)
[![License](https://img.shields.io/github/license/ZpkDxGames/PlexonShops)](LICENSE)

PlexonShops is a production-oriented Paper plugin that gives players a visual,
global shop directory. Shop creation, discovery, availability, categories,
display icons, locations, showcased items, and ratings are managed through
inventory GUIs; chat is used only for free-form text entered from a GUI action.

Version 2.1.0 is PlexonCore-native while retaining full standalone compatibility.
It also exposes a stable public Shops API and the Created/Visited/Rated events
used natively by PlexonQuests 3.1.0.

## Features

- Paginated global directory with All, Blocks, Tools, Combat, Redstone, Farming,
  Miscellaneous, and Labeled Items filters
- Player-head or custom-item shop icons with safe MiniMessage names and descriptions
- OPEN, CLOSED, and MAINTENANCE states
- Primary shops and rank-controlled sub-shops through numbered permission overrides
- One-to-five-star ratings, total visits, and unique visitors
- Cancellable bossbar teleport warmups using Paper's asynchronous teleport API
- Configurable teleport cooldowns, damage/movement cancellation, sounds, particles,
  owner bypasses, and arrival actionbars
- Optional Vault economy charges with refund-on-failure behavior
- Optional internal PlaceholderAPI expansion
- Asynchronous SQLite persistence, WAL mode, prepared statements, transactions,
  immutable cached models, and a bounded background worker
- Optional PlexonCore 1.x module registration with CORE/STANDALONE operation
- Stable Bukkit `PlexonShopsAPI` with immutable `ShopView` values
- Public Created, Rated and Visited events dispatched after successful persistence
- Native PlexonQuests 3.1.0 shop objective interoperability

## Requirements

| Component | Version |
| --- | --- |
| Paper | 26.2 stable |
| Java | 25 or newer |
| PlexonCore | Optional; 1.x supported (`>=1.0 <2.0`) |
| Vault + an economy plugin | Optional; required when paid teleports are enabled |
| PlaceholderAPI | Optional |

## Installation

1. Download `PlexonShops-<version>.jar` from the latest release.
2. Place it in the Paper server's `plugins/` directory.
3. Optionally install PlexonCore 1.x for Core module registration and diagnostics.
4. Install Vault and a compatible economy provider if teleport fees are enabled.
5. Start the server and review `plugins/PlexonShops/config.yml`.
6. Grant `plexonshops.create` directly or through PlexonRanks, then use
   `/pshops manage`. New shops begin CLOSED by default.

Existing 2.0.0 installations can upgrade without regenerating configuration or
migrating the SQLite schema. See [Migration 2.1](docs/MIGRATION_2_1.md).

## Commands

| Command | Purpose |
| --- | --- |
| `/pshops` | Open the global player shops directory |
| `/pshops manage` | Create or manage owned shops |
| `/pshops reload` | Reload `config.yml` and `messages.yml` |

Aliases: `/pshop`, `/playershops`.

## Permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `plexonshops.use` | Everyone | Browse, teleport, and rate |
| `plexonshops.create` | Nobody | Create a primary shop and manage owned shops |
| `plexonshops.admin` | Operators | All administrative capabilities |
| `plexonshops.reload` | Operators | Reload runtime configuration and messages |
| `plexonshops.format` | Operators | Use MiniMessage in owned shop text |
| `plexonshops.subshops.<number>` | Nobody | Grant that many additional sub-shops |
| `plexonshops.subshops.unlimited` | Nobody | Bypass the sub-shop limit |
| `plexonshops.limit.<number>` | Nobody | Legacy total-shop limit; retained for compatibility |
| `plexonshops.limit.unlimited` | Nobody | Legacy unlimited total-shop limit |
| `plexonshops.teleport.cooldown.bypass` | Operators | Bypass teleport cooldowns |
| `plexonshops.teleport.fee.bypass` | Nobody | Bypass all teleport fees |

## Public API and events

PlexonShops 2.1.0 registers `com.plexon.shops.api.PlexonShopsAPI` through Bukkit's
`ServicesManager` in both Core and standalone modes. It exposes immutable shop
views and directory/count queries without leaking repository or cache internals.

Public integration events:

- `com.plexon.shops.event.PlexonShopCreatedEvent`
- `com.plexon.shops.event.PlexonShopRatedEvent`
- `com.plexon.shops.event.PlexonShopVisitedEvent`

These events are emitted only after successful persistence/cache updates and are
always dispatched on the primary server thread. See [API](docs/API.md) and
[PlexonCore integration](docs/PLEXONCORE.md).

## PlaceholderAPI

The internal expansion identifier is `plexonshops`:

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

Player-specific values use the player's oldest owned shop when several exist.

## Building

CI provisions the official pinned PlexonCore 1.0.0 API artifact into the local
Maven repository before compiling. Locally, provide that artifact through
`mavenLocal()` and run:

```bash
./gradlew clean test check javadoc shadowJar verifyDistribution
```

The self-contained installable JAR is written to
`build/libs/PlexonShops-2.1.0.jar`. Distribution verification rejects any shaded
`com/zpkdxgames/plexoncore/` classes.

See [Development](docs/DEVELOPMENT.md), [Architecture](docs/ARCHITECTURE.md),
[API](docs/API.md), [PlexonCore](docs/PLEXONCORE.md), and
[Migration 2.1](docs/MIGRATION_2_1.md).

## Data and privacy

Shop data is stored locally in `plugins/PlexonShops/shops.db`. The plugin opens
no network listener and sends no server or player data to an external service.
Back up the database together with the rest of the server before upgrades.

## License

PlexonShops is released under the [MIT License](LICENSE).

Created by [Tonim / ZpkDxGames](https://github.com/ZpkDxGames) for the Plexon ecosystem.
