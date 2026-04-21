package dev.kossnikita.borgbackup.core.hooks;

import dev.kossnikita.borgbackup.core.process.BackupToolExecutor;
import dev.kossnikita.borgbackup.core.process.CommandResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HookExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(HookExecutor.class);
    private final BackupToolExecutor executor;

    public HookExecutor(BackupToolExecutor executor) {
        this.executor = executor;
    }

    public void runHooks(List<String> commands, Duration timeout, Map<String, String> environment, String workingDirectory) {
        for (String command : commands) {
            List<String> shellCommand = osShell(command);
            CommandResult result = executor.run(shellCommand, environment, timeout, workingDirectory);
            if (!result.success()) {
                LOGGER.error("Hook command failed: {}\n{}", command, result.stderr());
            }
        }
    }

    private static List<String> osShell(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return List.of("powershell", "-NoProfile", "-Command", command);
        }
        return List.of("/bin/sh", "-c", command);
    }
}
