package dev.kossnikita.borgbackup.fabric;

import dev.kossnikita.borgbackup.core.BackupManager;
import dev.kossnikita.borgbackup.core.BackupStatus;
import dev.kossnikita.borgbackup.core.config.BackupConfig;
import dev.kossnikita.borgbackup.core.config.BackupConfigLoader;
import dev.kossnikita.borgbackup.core.hooks.HookExecutor;
import dev.kossnikita.borgbackup.core.notify.WebhookNotifier;
import dev.kossnikita.borgbackup.core.process.ResticExecutor;
import dev.kossnikita.borgbackup.core.process.CommandResult;
import dev.kossnikita.borgbackup.core.schedule.BackupScheduler;
import dev.kossnikita.borgbackup.core.status.BackupStatusStore;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FabricBackupMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("borgbackup");
    private static final String LOG_PREFIX = "[borgbackup/fabric] ";
    private static volatile BackupScheduler scheduler;
    private static volatile BackupManager backupManager;
    private static volatile ServerCommandMinecraftAdapter minecraftAdapter;
    private static volatile MinecraftServer minecraftServer;

    @Override
    public void onInitialize() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (scheduler != null) {
                scheduler.stop();
            }
        }, "borgbackup-shutdown"));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> minecraftServer = server);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> minecraftServer = null);

        try {
            Path configPath = ensureBackupConfig();
            BackupConfig config = new BackupConfigLoader().load(configPath);

            ResticExecutor resticExecutor = new ResticExecutor();
            HookExecutor hookExecutor = new HookExecutor(resticExecutor);
            WebhookNotifier webhookNotifier = new WebhookNotifier();
            BackupStatusStore statusStore = new BackupStatusStore();

            minecraftAdapter = new ServerCommandMinecraftAdapter(() -> minecraftServer);

            backupManager = new BackupManager(
                config,
                minecraftAdapter,
                resticExecutor,
                hookExecutor,
                webhookNotifier,
                statusStore
            );

            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
                ServerCommandMinecraftAdapter adapter = minecraftAdapter;
                if (adapter != null) {
                    adapter.markPlayerActivity();
                }
            });

            registerCommands();

            scheduler = new BackupScheduler();
            scheduler.start(config.schedule(), () -> backupManager.triggerBackupAsync("scheduler"));
            if (config.schedule().runOnStartup()) {
                backupManager.triggerBackupAsync("startup");
                LOGGER.info(LOG_PREFIX + "Startup backup has been triggered (run_on_startup=true)");
            }
            startResticPreflight(config, resticExecutor);

            LOGGER.info(LOG_PREFIX + "Restic backup mod initialized (Fabric 26.1 mode)");
            LOGGER.info(LOG_PREFIX + "Consistency mode: server commands save-off/save-all flush/save-on");
            LOGGER.info(LOG_PREFIX + "Registered commands: /backup now, /backup status");
        } catch (Exception e) {
            LOGGER.error(LOG_PREFIX + "Failed to initialize Restic backup mod", e);
        }
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            if (!environment.includeDedicated) {
                return;
            }

            dispatcher.register(
                Commands.literal("backup")
                    .then(Commands.literal("now").executes(this::executeBackupNow))
                    .then(Commands.literal("status").executes(this::executeBackupStatus))
            );
        });
    }

    private int executeBackupNow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        BackupManager manager = backupManager;
        if (manager == null) {
            sendFailure(source, "Backup manager is not initialized");
            LOGGER.error(LOG_PREFIX + "Command '/backup now' rejected: backup manager is not initialized");
            return 0;
        }

        sendSuccess(source, "Starting backup...");
        LOGGER.info(LOG_PREFIX + "Command '/backup now' received");
        CompletableFuture<?> future = manager.triggerBackupAsync("command");
        future.thenAccept(result -> {
            sendSuccess(source, "Backup finished with result: " + result);
            LOGGER.info(LOG_PREFIX + "Command '/backup now' finished with result {}", result);
        });
        return 1;
    }

    private int executeBackupStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        BackupManager manager = backupManager;
        if (manager == null) {
            sendFailure(source, "Backup manager is not initialized");
            LOGGER.error(LOG_PREFIX + "Command '/backup now' rejected: backup manager is not initialized");
            return 0;
        }

        BackupStatus status = manager.status();
        sendSuccess(source, "Last run: " + status.lastRun());
        sendSuccess(source, "Duration: " + status.duration());
        sendSuccess(source, "Result: " + status.result());
        if (status.message() != null && !status.message().isBlank()) {
            sendSuccess(source, "Message: " + status.message());
        }

        LOGGER.info(
            LOG_PREFIX + "Status: lastRun={} duration={} result={} message={}",
            status.lastRun(),
            status.duration(),
            status.result(),
            status.message()
        );
        return 1;
    }

    private void sendSuccess(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    private void sendFailure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
    }

    private void startResticPreflight(BackupConfig config, ResticExecutor resticExecutor) {
        Thread preflightThread = new Thread(() -> {
            try {
                LOGGER.info(LOG_PREFIX + "Restic preflight started: checking runtime with 'restic version'");
                CommandResult result = resticExecutor.run(
                    List.of("restic", "version"),
                    config.environment(),
                    Duration.ofMinutes(2),
                    config.workingDirectory()
                );

                if (result.success()) {
                    String version = result.stdout() == null ? "" : result.stdout().trim();
                    LOGGER.info(LOG_PREFIX + "Restic preflight successful. {}", version);
                } else {
                    LOGGER.error(LOG_PREFIX + "Restic preflight failed. stderr={} stdout={}", result.stderr(), result.stdout());
                }
            } catch (Exception e) {
                LOGGER.error(LOG_PREFIX + "Restic preflight crashed", e);
            }
        }, "borgbackup-preflight");
        preflightThread.setDaemon(true);
        preflightThread.start();
    }

    private Path ensureBackupConfig() throws IOException {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("borgbackup");
        Path secretsDir = configDir.resolve("secrets");
        Files.createDirectories(secretsDir);

        Path backupToml = configDir.resolve("backup.toml");
        if (Files.notExists(backupToml)) {
            Files.writeString(backupToml, defaultToml());
        }

        Path secretFile = secretsDir.resolve("restic_password.secret");
        if (Files.notExists(secretFile)) {
            Files.writeString(secretFile, "change-me");
        }

        return backupToml;
    }

    private String defaultToml() {
        return "repository = \"s3:https://garage.internal:3900/minecraft-backups\"\n"
            + "paths = [\"./world\", \"./mods\"]\n"
            + "exclude = [\"logs/**\", \"crash-reports/**\", \"cache/**\", \"*.tmp\", \"session.lock\"]\n"
            + "compression = \"auto\"\n"
            + "archive_prefix = \"fabric\"\n"
            + "working_directory = \".\"\n"
            + "timeout = \"2h\"\n\n"
            + "[schedule]\n"
            + "interval = \"3h\"\n\n"
            + "run_on_startup = false\n\n"
            + "[retention]\n"
            + "keep_last = 16\n"
            + "compact = false\n\n"
            + "[environment]\n"
            + "AWS_ACCESS_KEY_ID = \"garage-access-key\"\n"
            + "AWS_SECRET_ACCESS_KEY = \"garage-secret-key\"\n"
            + "AWS_REGION = \"garage\"\n"
            + "AWS_ENDPOINT_URL = \"https://garage.internal:3900\"\n"
            + "# RESTIC_EXECUTABLE = \"./tools/restic/restic\"\n"
            + "# RESTIC_DOWNLOAD_URL = \"https://example.com/restic\"\n"
            + "# RESTIC_DOWNLOAD_SHA256 = \"<sha256>\"\n\n"
            + "[environment_files]\n"
            + "RESTIC_PASSWORD = \"secrets/restic_password.secret\"\n\n"
            + "[retry]\n"
            + "enabled = true\n"
            + "max_attempts = 2\n"
            + "delay = \"5m\"\n\n"
            + "[hooks]\n"
            + "pre = []\n"
            + "post = []\n"
            + "timeout = \"10m\"\n\n"
            + "[webhook]\n"
            + "enabled = false\n"
            + "url = \"\"\n"
            + "token = \"\"\n"
            + "timeout = \"10s\"\n";
    }
}
