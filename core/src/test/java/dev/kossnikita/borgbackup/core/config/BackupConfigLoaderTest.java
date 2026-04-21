package dev.kossnikita.borgbackup.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackupConfigLoaderTest {
    @Test
    void parseDurationSupportsKnownSuffixes() {
        assertEquals(Duration.ofSeconds(15), BackupConfigLoader.parseDuration("15s"));
        assertEquals(Duration.ofMinutes(3), BackupConfigLoader.parseDuration("3m"));
        assertEquals(Duration.ofHours(2), BackupConfigLoader.parseDuration("2h"));
        assertEquals(Duration.ofDays(1), BackupConfigLoader.parseDuration("1d"));
    }
}
