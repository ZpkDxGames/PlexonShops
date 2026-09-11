# PlexonShops 3.0.0 runtime certification

Stable GitHub source/release closure is independent from live PlexonCraft deployment certification.

The `v3.0.0` stable artifact may therefore record:

`runtime_certification=NOT_EXECUTED`

in its release provenance while still requiring exact-source CI, tests, distribution verification, accepted Phase 3/RC2 ancestry, immutable stable publication, and downloaded-asset checksum/provenance verification.

## Live deployment certification

Before production cutover is considered operationally certified, validate on PlexonCraft:

- representative `2.2.1 -> 3.0.0` migration and backup behavior;
- discovery/browser/categories/search/filter;
- shop profile and owner management;
- OPEN/CLOSED/MAINTENANCE/unavailable behavior;
- successful free and paid shop teleport;
- duplicate warmup/in-flight rejection;
- movement and damage cancellation during warmup;
- logout during warmup cancels the warmup without charging;
- logout after `teleportAsync` has started does not refund a potentially successful teleport;
- failed/exceptional async teleport refunds the exact charged amount;
- shop close/destination invalidation during warmup;
- Vault/Theosis charge/refund behavior;
- visit publication only after successful teleport;
- stale discovery-load rejection;
- restart persistence;
- invalid reload rollback;
- PlaceholderAPI;
- public API/events;
- PlexonCore/cross-plugin behavior;
- Spark/MSPT comparison under representative directory/teleport traffic;
- at least 30 minutes of soak;
- zero HIGH/CRITICAL defects.

A runtime failure discovered during this follow-up must be handled as a new defect/remediation cycle; it does not retroactively change the exact GitHub artifact provenance.
