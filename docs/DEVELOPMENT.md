# Development

## Requirements

- Java 25+
- Paper 26.2 stable for server testing
- The included Gradle wrapper
- PlexonCore 2.0.0 API installed in the local Maven repository as `com.zpkdxgames:PlexonCore:2.0.0`

CI provisions the official Core 2.0.0 release JAR and verifies SHA-256 `179b82ce7fd82e3095b3a6d0d893af6d7c97f91bd7ce403e96d7f3581462dfec` before compiling.

## Verify and package

```bash
./gradlew --no-daemon clean test check javadoc shadowJar verifyDistribution
```

The distribution task verifies the public Shops API/events, embedded Hikari/SQLite runtime, Core runtime ownership bridge, and absence of shaded PlexonCore classes.

## Core runtime tests

PlexonShops 2.3.0 separates Core module registration from player-event ownership. Core 2.0.0 does not expose movement/damage/join/quit subscriptions, so the expected stable ownership for those families is LOCAL.

Verify at minimum:

1. Core absent -> standalone startup with local player-event listeners.
2. Core 1.x -> legacy Core module registration with local player-event listeners.
3. Core 2.0.0 -> `CORE_RUNTIME` module registration with movement/damage/quit/join reported LOCAL.
4. `core-runtime.mode: LOCAL` -> local ownership without degraded status.
5. Forced `CORE` with fallback allowed -> local ownership plus explicit degraded status.
6. Forced `CORE` with fallback disabled -> startup fails closed.
7. Reload does not duplicate listeners or switch ownership; ownership changes require restart.

## Manual smoke test

1. Start a disposable Paper 26.2 server with Java 25, PlexonCore 2.0.0, and the built JAR.
2. Create a shop through `/pshops manage` and verify it begins CLOSED.
3. Exercise every editor, category limit, label addition/removal, and icon reset.
4. Open the shop; test movement/damage cancellation, rotation-only movement, bossbar timing, cooldowns, paid teleport, owner bypass, effects, and failed-teleport refund.
5. Verify cancelled external movement/damage does not cancel a warmup.
6. Verify quit during warmup clears the task/bossbar without a message.
7. Submit and update a rating from a second account.
8. Restart the server and verify all shop state and counters reload.
9. Test primary/sub-shop rank limits with `plexonshops.subshops.N` and legacy `plexonshops.limit.N`.
10. Test once with Vault absent and once with PlaceholderAPI present.
11. Run `/pshops diagnostics` and confirm event ownership and counters match the active mode.

## Performance staging

For a stable 2.3 release, record actual Paper/Spark evidence in `PERFORMANCE_2_3_0.md`. Do not replace unexecuted cells with estimates. The most important case is normal movement with no pending shop teleport, where the Shops path must remain the O(1) pending UUID gate.

## Releases

1. Update `version` in `build.gradle.kts`, `CHANGELOG.md`, and `releases/<version>.md`.
2. Run the full verification command.
3. Complete the manual smoke/performance gates required by the release specification.
4. Merge the verified PR to `main` and record the exact commit SHA.
5. Publish only a matching `vX.Y.Z` release.
6. Verify the release contains `PlexonShops-X.Y.Z.jar` and `SHA256SUMS.txt`.
7. Verify the downloaded JAR against the published checksum.

Stable release assets are immutable. The release workflow fails if a release already exists instead of replacing assets with `--clobber`.
