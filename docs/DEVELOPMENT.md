# Development

## Requirements

- Java 25+
- Paper 26.2 stable for server testing
- The included Gradle wrapper

## Verify and package

```bash
./gradlew clean test javadoc shadowJar verifyDistribution
```

The test suite covers permission limits, immutable aggregate behavior, text-list
encoding, and an end-to-end transactional SQLite round trip.

## Manual smoke test

1. Start a disposable Paper 1.21.10 server with the built JAR.
2. Create a shop through `/pshops manage` and verify it begins CLOSED.
3. Exercise every editor, category limit, label addition/removal, and icon reset.
4. Open the shop; test movement/damage cancellation, bossbar timing, cooldowns,
   paid teleport, owner bypass, effects, and failed-teleport refund.
5. Submit and update a rating from a second account.
6. Restart the server and verify all shop state and counters reload.
7. Test primary/sub-shop rank limits with `plexonshops.subshops.N` and the legacy
   `plexonshops.limit.N` compatibility node.
8. Test once with Vault absent and once with PlaceholderAPI present.

## Releases

1. Update `version` in `build.gradle.kts` and `CHANGELOG.md`.
2. Run the full verification command.
3. Complete the manual smoke test on a disposable server.
4. Push a matching `vX.Y.Z` tag, run the release workflow with that tag, or use
   the guarded `[release]` main-branch commit flow.

GitHub publishes the shaded JAR and its SHA-256 checksum.
