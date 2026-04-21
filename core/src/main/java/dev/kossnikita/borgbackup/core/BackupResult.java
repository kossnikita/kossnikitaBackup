package dev.kossnikita.borgbackup.core;

public enum BackupResult {
    SUCCESS,
    FAILED,
    TIMEOUT,
    SKIPPED_ALREADY_RUNNING,
    SKIPPED_NO_PLAYER_ACTIVITY
}
