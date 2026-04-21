package dev.kossnikita.borgbackup.core;

import java.time.Duration;
import java.time.Instant;

public record BackupStatus(
    Instant lastRun,
    Duration duration,
    BackupResult result,
    String stats,
    String message
) {
    public static BackupStatus empty() {
        return new BackupStatus(null, null, null, "", "No backups have run yet");
    }
}
