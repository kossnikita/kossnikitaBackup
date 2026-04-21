package dev.kossnikita.borgbackup.core;

public enum BackupResult {
    SUCCESS,
    FAILED,
    TIMEOUT,
    SKIPPED_ALREADY_RUNNING
}
