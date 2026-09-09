# PlexonShops 2.3.0 Performance Evidence

This document distinguishes verified source/build properties from real-server measurements. Values are not invented when staging evidence is unavailable.

## Architectural result

PlexonCore 2.0.0 has no public movement/damage/lifecycle event subscription API. PlexonShops 2.3.0 therefore keeps the optimized 2.2 local listener acquisition path for those events.

The no-pending movement path remains:

```text
PlayerMoveEvent
  -> UUID
  -> pending.containsKey(UUID)
  -> false: return
```

No configuration lookup, Bukkit player lookup, `Location` allocation, shop lookup, database operation, or async work was added after the no-pending gate.

## Runtime comparison

| Scenario | 2.2 | 2.3 LOCAL | 2.3 CORE player events | Result |
|---|---:|---:|---:|---|
| idle | NOT EXECUTED | NOT EXECUTED | N/A in Core 2.0.0 | pending staging |
| 1 mover, no warmup | NOT EXECUTED | NOT EXECUTED | N/A in Core 2.0.0 | pending staging |
| 10 movers, no warmup | NOT EXECUTED | NOT EXECUTED | N/A in Core 2.0.0 | pending staging |
| 1 warmup | NOT EXECUTED | NOT EXECUTED | N/A in Core 2.0.0 | pending staging |
| 5 warmups | NOT EXECUTED | NOT EXECUTED | N/A in Core 2.0.0 | pending staging |
| damage | NOT EXECUTED | NOT EXECUTED | N/A in Core 2.0.0 | pending staging |
| mixed soak | NOT EXECUTED | NOT EXECUTED | N/A in Core 2.0.0 | pending staging |

## Spark

- Spark 2.2 profile: NOT EXECUTED in this build environment.
- Spark 2.3 LOCAL profile: NOT EXECUTED in this build environment.
- Spark 2.3 CORE player-event profile: N/A until Core publishes player-event subscriptions.
- 30-minute Paper soak: NOT EXECUTED in this build environment.

## Built-in counters

2.3 adds allocation-light counters rendered only on `/pshops diagnostics`:

- movement events received;
- movement fast rejects;
- movement cancellations;
- damage events received;
- damage fast rejects;
- damage cancellations;
- quit cancellations.

These use `LongAdder`; no diagnostic strings/maps are created per movement event.

## Stable-release gate

A GitHub build/verification pass proves compilation, unit tests, distribution structure, Java 25 bytecode, version consistency, checksum generation, and no shaded PlexonCore classes. It does not substitute for real Paper/Spark/soak evidence.
