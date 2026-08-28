# PlexonShops

[![Build](https://github.com/ZpkDxGames/PlexonShops/actions/workflows/build.yml/badge.svg)](https://github.com/ZpkDxGames/PlexonShops/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ZpkDxGames/PlexonShops)](https://github.com/ZpkDxGames/PlexonShops/releases/latest)
[![License](https://img.shields.io/github/license/ZpkDxGames/PlexonShops)](LICENSE)

PlexonShops is a production-oriented Paper plugin that gives players a visual,
global shop directory. Shop creation, discovery, availability, categories,
display icons, locations, showcased items, and ratings are managed through
inventory GUIs; chat is used only for free-form text entered from a GUI action.

## Features

- Paginated global directory with All, Blocks, Tools, Combat, Redstone, Farming,
  Miscellaneous, and Labeled Items filters
- Player-head or custom-item shop icons with MiniMessage names and descriptions
- OPEN, CLOSED, and MAINTENANCE states
- Multiple shops per player through numbered permission overrides
- One-to-five-star ratings, total visits, and unique visitors
- Cancellable teleport warmups using Paper's asynchronous teleport API
- Optional Vault economy charges with refund-on-failure behavior
- Optional internal PlaceholderAPI expansion
- Asynchronous SQLite persistence, WAL mode, prepared statements, transactions,
  immutable cached models, and a bounded background worker
- Configurable limits, blacklisted worlds, inactivity hiding, and GUI slot maps

## Requirements

| Component | Version |
| --- | --- |
| Paper | 1.21.10 (1.21.x API line) |
| Java | 21 |
| Vault + an economy plugin | Optional; required when paid teleports are enabled |
| PlaceholderAPI | Optional |

## Installation

1. Download `PlexonShops-<version>.jar` from the latest release.
2. Place it in the Paper server's `plugins/` directory.
3. Install Vault and a compatible economy provider if teleport fees are enabled.
4. Start the server and review `plugins/PlexonShops/config.yml`.
5. Use `/pshops manage` to create a shop. New shops begin CLOSED by default.

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
| `plexonshops.create` | Everyone | Create and manage owned shops |
| `plexonshops.admin` | Operators | All administrative capabilities |
| `plexonshops.reload` | Operators | Reload runtime configuration and messages |
| `plexonshops.format` | Operators | Use MiniMessage in owned shop text |
| `plexonshops.limit.<number>` | Nobody | Set a higher per-player shop limit |
| `plexonshops.limit.unlimited` | Nobody | Bypass the shop limit |

## PlaceholderAPI

The internal expansion identifier is `plexonshops`:

- `%plexonshops_total%`
- `%plexonshops_open%`
- `%plexonshops_owned%`
- `%plexonshops_name%`
- `%plexonshops_status%`
- `%plexonshops_rating%`
- `%plexonshops_visitors%`
- `%plexonshops_unique_visitors%`

Player-specific values use the player's oldest owned shop when several exist.

## Building

```bash
./gradlew clean test javadoc shadowJar
```

The installable JAR is written to `build/libs/PlexonShops-<version>.jar`.
See [Development](docs/DEVELOPMENT.md) and [Architecture](docs/ARCHITECTURE.md)
for runtime boundaries and release details.

## Data and privacy

Shop data is stored locally in `plugins/PlexonShops/shops.db`. The plugin opens
no network listener and sends no server or player data to an external service.
Back up the database together with the rest of the server before upgrades.

## License

PlexonShops is released under the [MIT License](LICENSE).

Created by [Tonim / ZpkDxGames](https://github.com/ZpkDxGames) for the Plexon ecosystem.
