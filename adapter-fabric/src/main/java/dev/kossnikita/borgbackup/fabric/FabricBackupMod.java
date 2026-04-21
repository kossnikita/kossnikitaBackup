package dev.kossnikita.borgbackup.fabric;

import com.mojang.brigadier.CommandDispatcher;
import dev.kossnikita.borgbackup.core.BackupManager;
import dev.kossnikita.borgbackup.core.BackupStatus;
import dev.kossnikita.borgbackup.core.config.BackupConfig;
import dev.kossnikita.borgbackup.core.config.BackupConfigLoader;
import dev.kossnikita.borgbackup.core.hooks.HookExecutor;
import dev.kossnikita.borgbackup.core.notify.WebhookNotifier;
import dev.kossnikita.borgbackup.core.process.BorgExecutor;
import dev.kossnikita.borgbackup.core.schedule.BackupScheduler;
import dev.kossnikita.borgbackup.core.status.BackupStatusStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FabricBackupMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(FabricBackupMod.class);
    private static volatile BackupManager backupManager;
    private static volatile BackupScheduler scheduler;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (scheduler != null) {
                scheduler.stop();
            }
        });
        CommandRegistrationCallback.EVENT.register(this::registerCommands);
    }

    private void onServerStarted(MinecraftServer server) {
        try {
            Path configPath = ensureBackupConfig(server);
            BackupConfig config = new BackupConfigLoader().load(configPath);

            BorgExecutor borgExecutor = new BorgExecutor();
            HookExecutor hookExecutor = new HookExecutor(borgExecutor);
            WebhookNotifier webhookNotifier = new WebhookNotifier();
            BackupStatusStore statusStore = new BackupStatusStore();

            backupManager = new BackupManager(
                config,
                new FabricMinecraftAdapter(server),
                borgExecutor,
                hookExecutor,
                webhookNotifier,
                statusStore
            );

            scheduler = new BackupScheduler();
            scheduler.start(config.schedule(), () -> backupManager.triggerBackupAsync("scheduler"));
            LOGGER.info("Borg backup mod initialized");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize Borg backup mod", e);
        }
    }

    private void registerCommands(
        CommandDispatcher<ServerCommandSource> dispatcher,
        net.minecraft.command.CommandRegistryAccess registryAccess,
        CommandManager.RegistrationEnvironment environment
    ) {
        dispatcher.register(CommandManager.literal("backup")
            .requires(source -> source.hasPermissionLevel(4))
            .then(CommandManager.literal("now")
                .executes(context -> {
                    if (backupManager == null) {
                        context.getSource().sendError(net.minecraft.text.Text.literal("Backup manager is not initialized"));
                        return 0;
                    }
                    backupManager.triggerBackupAsync("manual");
                    context.getSource().sendFeedback(toText("Backup started"), false);
                    return 1;
                }))
            .then(CommandManager.literal("status")
                .executes(context -> {
                    if (backupManager == null) {
                        context.getSource().sendError(net.minecraft.text.Text.literal("Backup manager is not initialized"));
                        return 0;
                    }
                    BackupStatus status = backupManager.status();
                    context.getSource().sendFeedback(toText("Last run: " + status.lastRun()), false);
                    context.getSource().sendFeedback(toText("Duration: " + status.duration()), false);
                    context.getSource().sendFeedback(toText("Result: " + status.result()), false);
                    if (status.stats() != null && !status.stats().isBlank()) {
                        context.getSource().sendFeedback(toText("Stats: " + status.stats().trim()), false);
                    }
                    if (status.message() != null && !status.message().isBlank()) {
                        context.getSource().sendFeedback(toText("Message: " + status.message()), false);
                    }
                    return 1;
                }))
        );
    }

    private static Supplier<net.minecraft.text.Text> toText(String value) {
        return () -> net.minecraft.text.Text.literal(value);
    }

    private Path ensureBackupConfig(MinecraftServer server) throws IOException {
        Path configDir = server.getRunDirectory().resolve("config").resolve("borgbackup");
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
            + "[retention]\n"
            + "keep_last = 16\n"
            + "compact = false\n\n"
            + "[environment]\n\n"
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
