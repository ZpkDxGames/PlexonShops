package com.plexon.shops.storage;

/** Cache-only persistence diagnostics safe for command rendering on the server thread. */
public record StorageDiagnostics(
        int schemaVersion,
        String migrationStatus,
        String backupFile,
        int corruptRows
) {
    public StorageDiagnostics {
        migrationStatus = migrationStatus == null ? "unknown" : migrationStatus;
        backupFile = backupFile == null ? "" : backupFile;
    }
}
