package dev.kossnikita.borgbackup.fabric;

import dev.kossnikita.borgbackup.core.BackupManager;
import dev.kossnikita.borgbackup.core.BackupStatus;
import dev.kossnikita.borgbackup.core.config.BackupConfig;
import dev.kossnikita.borgbackup.core.config.BackupConfigLoader;
import dev.kossnikita.borgbackup.core.hooks.HookExecutor;
import dev.kossnikita.borgbackup.core.notify.WebhookNotifier;
import dev.kossnikita.borgbackup.core.process.BorgExecutor;
import dev.kossnikita.borgbackup.core.process.CommandResult;
import dev.kossnikita.borgbackup.core.schedule.BackupScheduler;
import dev.kossnikita.borgbackup.core.status.BackupStatusStore;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FabricBackupMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("borgbackup");
    private static final String LOG_PREFIX = "[borgbackup/fabric] ";
    private static volatile BackupScheduler scheduler;
    private static volatile BackupManager backupManager;

    @Override
    public void onInitialize() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (scheduler != null) {
                scheduler.stop();
            }
        }, "borgbackup-shutdown"));

        try {
            Path configPath = ensureBackupConfig();
            Path gameDir = FabricLoader.getInstance().getGameDir();
            ensureRconConsistency(gameDir);
            BackupConfig config = new BackupConfigLoader().load(configPath);

            BorgExecutor borgExecutor = new BorgExecutor();
            HookExecutor hookExecutor = new HookExecutor(borgExecutor);
            WebhookNotifier webhookNotifier = new WebhookNotifier();
            BackupStatusStore statusStore = new BackupStatusStore();

            backupManager = new BackupManager(
                config,
                new RconMinecraftAdapter(gameDir),
                borgExecutor,
                hookExecutor,
                webhookNotifier,
                statusStore
            );

            registerCommands();

            scheduler = new BackupScheduler();
            scheduler.start(config.schedule(), () -> backupManager.triggerBackupAsync("scheduler"));
            if (config.schedule().runOnStartup()) {
                backupManager.triggerBackupAsync("startup");
                LOGGER.info(LOG_PREFIX + "Startup backup has been triggered (run_on_startup=true)");
            }
            startBorgPreflight(config, borgExecutor);

            LOGGER.info(LOG_PREFIX + "Borg backup mod initialized (Fabric 26.1 mode)");
            LOGGER.info(LOG_PREFIX + "Consistency mode: RCON save-off/save-all flush/save-on");
            LOGGER.info(LOG_PREFIX + "Registered commands: /backup now, /backup status");
        } catch (Exception e) {
            LOGGER.error(LOG_PREFIX + "Failed to initialize Borg backup mod", e);
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

    private void startBorgPreflight(BackupConfig config, BorgExecutor borgExecutor) {
        Thread preflightThread = new Thread(() -> {
            try {
                LOGGER.info(LOG_PREFIX + "Borg preflight started: checking embedded runtime with 'borg --version'");
                CommandResult result = borgExecutor.run(
                    List.of("borg", "--version"),
                    config.environment(),
                    Duration.ofMinutes(2),
                    config.workingDirectory()
                );

                if (result.success()) {
                    String version = result.stdout() == null ? "" : result.stdout().trim();
                    LOGGER.info(LOG_PREFIX + "Borg preflight successful. {}", version);
                } else {
                    LOGGER.error(LOG_PREFIX + "Borg preflight failed. stderr={} stdout={}", result.stderr(), result.stdout());
                }
            } catch (Exception e) {
                LOGGER.error(LOG_PREFIX + "Borg preflight crashed", e);
            }
        }, "borgbackup-preflight");
        preflightThread.setDaemon(true);
        preflightThread.start();
    }

    private void ensureRconConsistency(Path gameDir) throws IOException {
        Path serverPropertiesPath = gameDir.resolve("server.properties");
        Properties properties = new Properties();

        if (Files.exists(serverPropertiesPath)) {
            try (var in = Files.newInputStream(serverPropertiesPath)) {
                properties.load(in);
            }
        }

        properties.setProperty("enable-rcon", "true");
        properties.setProperty("broadcast-rcon-to-ops", "false");
        properties.putIfAbsent("rcon.port", "25575");

        String password = properties.getProperty("rcon.password", "").trim();
        if (password.isEmpty()) {
            byte[] random = new byte[24];
            new SecureRandom().nextBytes(random);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            properties.setProperty("rcon.password", password);
            LOGGER.warn(LOG_PREFIX + "RCON password was missing and has been generated in server.properties");
        }

        try (var out = Files.newOutputStream(serverPropertiesPath)) {
            properties.store(out, "Managed by borgbackup for backup consistency");
        }
    }

    private Path ensureBackupConfig() throws IOException {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("borgbackup");
        Path secretsDir = configDir.resolve("secrets");
        Files.createDirectories(secretsDir);

        Path backupToml = configDir.resolve("backup.toml");
        if (Files.notExists(backupToml)) {
            Files.writeString(backupToml, defaultToml());
        }

        Path secretFile = secretsDir.resolve("borg_passphrase.secret");
        if (Files.notExists(secretFile)) {
            Files.writeString(secretFile, "change-me");
        }

        return backupToml;
    }

    private String defaultToml() {
        return "repository = \"user@backup:/backups/minecraft_repo\"\n"
            + "paths = [\"./world\", \"./mods\"]\n"
            + "exclude = [\"logs/**\", \"crash-reports/**\", \"cache/**\", \"*.tmp\", \"session.lock\"]\n"
            + "compression = \"lz4\"\n"
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
            + "# BORG_EXECUTABLE = \"./tools/borg/borg\"\n"
            + "# BORG_DOWNLOAD_URL = \"https://github.com/borgbackup/borg/releases/download/1.4.4/borg-linux-glibc231-x86_64\"\n"
            + "# BORG_DOWNLOAD_SHA256 = \"<sha256>\"\n\n"
            + "[environment_files]\n"
            + "BORG_PASSPHRASE = \"secrets/borg_passphrase.secret\"\n\n"
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
