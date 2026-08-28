# Development

## Requirements

- Java 21
- Paper 1.21.10 for server testing
- The included Gradle wrapper

## Verify and package

```bash
./gradlew clean test javadoc shadowJar
```

The test suite covers permission limits, immutable aggregate behavior, text-list
encoding, and an end-to-end transactional SQLite round trip.

## Manual smoke test

1. Start a disposable Paper 1.21.10 server with the built JAR.
2. Create a shop through `/pshops manage` and verify it begins CLOSED.
3. Exercise every editor, category limit, label addition/removal, and icon reset.
4. Open the shop, test movement cancellation, paid teleport, and failed-teleport refund.
5. Submit and update a rating from a second account.
6. Restart the server and verify all shop state and counters reload.
7. Test once with Vault absent and once with PlaceholderAPI present.

## Releases

1. Update `version` in `build.gradle.kts` and `CHANGELOG.md`.
2. Run the full verification command.
3. Complete the manual smoke test on a disposable server.
4. Push a matching `vX.Y.Z` tag or run the release workflow with that tag.

GitHub publishes the shaded JAR and its SHA-256 checksum.
