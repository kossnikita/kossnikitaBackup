package dev.kossnikita.borgbackup.core;

import dev.kossnikita.borgbackup.core.config.BackupConfig;
import dev.kossnikita.borgbackup.core.hooks.HookExecutor;
import dev.kossnikita.borgbackup.core.notify.WebhookNotifier;
import dev.kossnikita.borgbackup.core.process.BorgExecutor;
import dev.kossnikita.borgbackup.core.process.CommandResult;
import dev.kossnikita.borgbackup.core.status.BackupStatusStore;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BackupManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackupManager.class);
    private static final DateTimeFormatter ARCHIVE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final BackupConfig config;
    private final MinecraftAdapter adapter;
    private final BorgExecutor borgExecutor;
    private final HookExecutor hookExecutor;
    private final WebhookNotifier webhookNotifier;
    private final BackupStatusStore statusStore;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public BackupManager(
        BackupConfig config,
        MinecraftAdapter adapter,
        BorgExecutor borgExecutor,
        HookExecutor hookExecutor,
        WebhookNotifier webhookNotifier,
        BackupStatusStore statusStore
    ) {
        this.config = config;
        this.adapter = adapter;
        this.borgExecutor = borgExecutor;
        this.hookExecutor = hookExecutor;
        this.webhookNotifier = webhookNotifier;
        this.statusStore = statusStore;
    }

    public CompletableFuture<BackupResult> triggerBackupAsync(String source) {
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(BackupResult.SKIPPED_ALREADY_RUNNING);
        }

        return CompletableFuture.supplyAsync(() -> runBackup(source), worker)
            .whenComplete((result, throwable) -> running.set(false));
    }

    public BackupStatus status() {
        return statusStore.get();
    }

    private BackupResult runBackup(String source) {
        Instant started = Instant.now();
        webhookNotifier.notify(config.webhook(), "backup_start", "Backup started: " + source);

        Map<String, String> env = config.environment();

        try {
            hookExecutor.runHooks(config.hooks().pre(), config.hooks().timeout(), env, config.workingDirectory());

            adapter.disableSaving().join();
            adapter.flushSave().join();

            CommandResult createResult = executeCreate(env);
            if (createResult.timedOut()) {
                return finalizeStatus(started, BackupResult.TIMEOUT, createResult, "Backup timed out");
            }
            if (!createResult.success()) {
                BackupResult maybeRetried = retryIfConfigured(env, started, createResult);
                if (maybeRetried != null) {
                    return maybeRetried;
                }
                return finalizeStatus(started, BackupResult.FAILED, createResult, "Borg create failed");
            }

            CommandResult pruneResult = executePrune(env);
            if (!pruneResult.success()) {
                LOGGER.error("Prune failed: {}", pruneResult.stderr());
            }

            if (config.retention().compact()) {
                CommandResult compactResult = executeCompact(env);
                if (!compactResult.success()) {
                    LOGGER.error("Compact failed: {}", compactResult.stderr());
                }
            }

            hookExecutor.runHooks(config.hooks().post(), config.hooks().timeout(), env, config.workingDirectory());
            return finalizeStatus(started, BackupResult.SUCCESS, createResult, "Backup completed successfully");
        } catch (Exception e) {
            LOGGER.error("Backup failed with exception", e);
            CommandResult synthetic = new CommandResult(-1, false, "", e.getMessage());
            return finalizeStatus(started, BackupResult.FAILED, synthetic, "Backup failed with exception");
        } finally {
            // save-on must be guaranteed even on failures/timeouts.
            try {
                adapter.enableSaving().join();
            } catch (Exception e) {
                LOGGER.error("Failed to re-enable world saving", e);
            }
        }
    }

    private BackupResult retryIfConfigured(Map<String, String> env, Instant started, CommandResult initialFailure) {
        if (!config.retry().enabled()) {
            return null;
        }

        for (int attempt = 1; attempt <= config.retry().maxAttempts(); attempt++) {
            try {
                Thread.sleep(config.retry().delay().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return finalizeStatus(started, BackupResult.FAILED, initialFailure, "Retry interrupted");
            }

            CommandResult retryResult = executeCreate(env);
            if (retryResult.success()) {
                return finalizeStatus(started, BackupResult.SUCCESS, retryResult, "Backup succeeded after retry");
            }
            if (retryResult.timedOut()) {
                return finalizeStatus(started, BackupResult.TIMEOUT, retryResult, "Backup timed out after retry");
            }
        }
        return finalizeStatus(started, BackupResult.FAILED, initialFailure, "Backup failed after retries");
    }

    private CommandResult executeCreate(Map<String, String> env) {
        String archive = config.archivePrefix() + "-" + ARCHIVE_TIME_FORMAT.format(Instant.now());
        List<String> command = new ArrayList<>();
        command.add("borg");
        command.add("create");
        command.add("--compression");
        command.add(config.compression());
        command.add("--stats");

        for (String pattern : config.exclude()) {
            command.add("--exclude");
            command.add(pattern);
        }

        command.add(config.repository() + "::" + archive);
        command.addAll(config.paths());

        return borgExecutor.run(command, env, config.timeout(), config.workingDirectory());
    }

    private CommandResult executePrune(Map<String, String> env) {
        List<String> command = List.of(
            "borg",
            "prune",
            "--keep-last=" + config.retention().keepLast(),
            config.repository()
        );
        return borgExecutor.run(command, env, config.timeout(), config.workingDirectory());
    }

    private CommandResult executeCompact(Map<String, String> env) {
        List<String> command = List.of("borg", "compact", config.repository());
        return borgExecutor.run(command, env, config.timeout(), config.workingDirectory());
    }

    private BackupResult finalizeStatus(Instant started, BackupResult result, CommandResult commandResult, String message) {
        Duration duration = Duration.between(started, Instant.now());
        String stats = commandResult.stdout();
        String mergedMessage = message;
        if (commandResult.stderr() != null && !commandResult.stderr().isBlank()) {
            mergedMessage = message + " | stderr: " + commandResult.stderr();
        }

        statusStore.update(new BackupStatus(Instant.now(), duration, result, stats, mergedMessage));

        if (result == BackupResult.SUCCESS) {
            webhookNotifier.notify(config.webhook(), "backup_success", mergedMessage);
            LOGGER.info("Backup success in {} seconds", duration.toSeconds());
        } else {
            webhookNotifier.notify(config.webhook(), "backup_failed", mergedMessage);
            LOGGER.error("Backup failed result={} message={}", result, mergedMessage);
        }

        return result;
    }
}
