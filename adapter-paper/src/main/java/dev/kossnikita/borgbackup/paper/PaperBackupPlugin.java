package dev.kossnikita.borgbackup.paper;

import dev.kossnikita.borgbackup.core.BackupManager;
import dev.kossnikita.borgbackup.core.BackupResult;
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
import java.util.Objects;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperBackupPlugin extends JavaPlugin implements CommandExecutor {
    private BackupManager backupManager;
    private BackupScheduler scheduler;

    @Override
    public void onEnable() {
        try {
            Path configPath = ensureBackupConfig();
            BackupConfig config = new BackupConfigLoader().load(configPath);

            BorgExecutor borgExecutor = new BorgExecutor();
            HookExecutor hookExecutor = new HookExecutor(borgExecutor);
            WebhookNotifier webhookNotifier = new WebhookNotifier();
            BackupStatusStore statusStore = new BackupStatusStore();

            backupManager = new BackupManager(
                config,
                new PaperMinecraftAdapter(this),
                borgExecutor,
                hookExecutor,
                webhookNotifier,
                statusStore
            );

            scheduler = new BackupScheduler();
            scheduler.start(config.schedule(), () -> backupManager.triggerBackupAsync("scheduler"));

            Objects.requireNonNull(getCommand("backup"), "backup command missing in plugin.yml").setExecutor(this);
            getLogger().info("Borg backup plugin enabled");
        } catch (Exception e) {
            getLogger().severe("Failed to start Borg backup plugin: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (backupManager == null) {
            sender.sendMessage(ChatColor.RED + "Backup manager is not initialized.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /backup <now|status>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "now" -> {
                sender.sendMessage(ChatColor.GRAY + "Starting backup...");
                backupManager.triggerBackupAsync("manual").thenAccept(result -> {
                    if (result == BackupResult.SUCCESS) {
                        sender.sendMessage(ChatColor.GREEN + "Backup finished successfully.");
                    } else if (result == BackupResult.SKIPPED_ALREADY_RUNNING) {
                        sender.sendMessage(ChatColor.YELLOW + "Backup already running.");
                    } else {
                        sender.sendMessage(ChatColor.RED + "Backup finished with result: " + result);
                    }
                });
                return true;
            }
            case "status" -> {
                BackupStatus status = backupManager.status();
                sender.sendMessage(ChatColor.AQUA + "Last run: " + status.lastRun());
                sender.sendMessage(ChatColor.AQUA + "Duration: " + status.duration());
                sender.sendMessage(ChatColor.AQUA + "Result: " + status.result());
                if (status.stats() != null && !status.stats().isBlank()) {
                    sender.sendMessage(ChatColor.GRAY + "Stats: " + status.stats().trim());
                }
                if (status.message() != null && !status.message().isBlank()) {
                    sender.sendMessage(ChatColor.GRAY + "Message: " + status.message());
                }
                return true;
            }
            default -> {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /backup <now|status>");
                return true;
            }
        }
    }

    private Path ensureBackupConfig() throws IOException {
        Path dataDir = getDataFolder().toPath();
        Path secretsDir = dataDir.resolve("secrets");
        Files.createDirectories(secretsDir);

        Path backupToml = dataDir.resolve("backup.toml");
        if (Files.notExists(backupToml)) {
            saveResource("backup.toml", false);
        }

        Path secretFile = secretsDir.resolve("borg_passphrase.secret");
        if (Files.notExists(secretFile)) {
            Files.writeString(secretFile, "change-me");
        }

        return backupToml;
    }
}
