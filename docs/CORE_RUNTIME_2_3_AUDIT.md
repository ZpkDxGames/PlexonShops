# PlexonShops 2.3.0 Core Runtime Listener Audit

Baseline: PlexonShops 2.2.0 (`5186d21b612ef9bda62aefca561d32e1ff82c759`)

Verified Core dependency: PlexonCore 2.0.0 (`v2.0.0`), API 2.0.

## Stable Core 2.0.0 player-event finding

The released `CoreEventGateway` exposes the block-break subscription path used by block-oriented modules. It does **not** expose public subscriptions for `PlayerMoveEvent`, player `EntityDamageEvent`, `PlayerJoinEvent`, or `PlayerQuitEvent`.

PlexonShops therefore does not invent or emulate a Core player-event API in this repository. The optimized local listeners remain authoritative for these event families in 2.3.0. The shop-domain processing has been separated into source-independent movement/damage/quit fact methods so a future Core runtime contract can feed the same logic without duplicating cancellation rules.

| Handler | Event | Frequency | 2.2 role | 2.3 ownership |
|---|---|---:|---|---|
| `TeleportListener.onMove` | `PlayerMoveEvent` | very high | pending warmup movement | LOCAL |
| `TeleportListener.onDamage` | player `EntityDamageEvent` | high | pending warmup damage | LOCAL |
| `TeleportListener.onQuit` | `PlayerQuitEvent` | lifecycle | cancel warmup | LOCAL |
| `OwnerActivityListener.onJoin` | `PlayerJoinEvent` | lifecycle | owner activity | LOCAL |
| `GuiListener` | inventory | medium | GUI | LOCAL |
| `ChatPromptService` | chat | low | edit prompt | LOCAL |

## Preserved hot-path semantics

- Movement listener remains `MONITOR` + `ignoreCancelled=true`.
- Damage listener remains `MONITOR` + `ignoreCancelled=true`.
- `hasPending(UUID)` remains the first shop-specific movement/damage gate.
- Rotation-only events remain ignored because world/XYZ equality is checked before domain processing.
- Movement cancellation keeps the `squaredDistance > 1.0E-6` threshold relative to the warmup origin.
- World changes cancel an active warmup when movement cancellation is enabled.
- No live `Player` lookup was added to the no-pending path.
- Cancellation messaging continues to resolve the live player lazily.

## Core registration

PlexonShops 2.3.0 accepts both:

- legacy API range `>=1.0 <2.0` as `CORE_LEGACY`;
- runtime API range `>=2.0 <3.0` as `CORE_RUNTIME`.

Core 2 module registration does not imply that player activity is Core-owned. `/pshops diagnostics` reports Core mode separately from event ownership.

## Runtime selection

`core-runtime.mode` supports `AUTO`, `CORE`, and `LOCAL`.

For stable Core 2.0.0:

- `AUTO` => Core module registration can use `CORE_RUNTIME`, player activity stays LOCAL.
- `LOCAL` => player activity stays LOCAL explicitly.
- `CORE` => player activity stays LOCAL only when fallback is allowed and diagnostics mark the runtime degraded.
- If player-event protection is required and local fallback is disabled, startup fails closed rather than silently losing warmup protection.

Changing runtime ownership settings requires a restart. Ordinary shop settings remain reloadable.
