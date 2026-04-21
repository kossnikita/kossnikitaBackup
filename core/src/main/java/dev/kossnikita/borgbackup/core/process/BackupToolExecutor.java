package dev.kossnikita.borgbackup.core.process;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface BackupToolExecutor {
    CommandResult run(List<String> command, Map<String, String> environment, Duration timeout, String workingDirectory);
}
