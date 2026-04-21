package dev.kossnikita.borgbackup.paper;

import dev.kossnikita.borgbackup.core.MinecraftAdapter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;

public final class PaperMinecraftAdapter implements MinecraftAdapter {
    private final Plugin plugin;
    private final AtomicBoolean playerActivitySinceLastBackup = new AtomicBoolean(false);

    public PaperMinecraftAdapter(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean consumePlayerActivityFlag() {
        return playerActivitySinceLastBackup.getAndSet(false);
    }

    public void markPlayerActivity() {
        playerActivitySinceLastBackup.set(true);
    }

    @Override
    public CompletableFuture<Void> disableSaving() {
        return runSync("save-off");
    }

    @Override
    public CompletableFuture<Void> flushSave() {
        return runSync("save-all flush");
    }

    @Override
    public CompletableFuture<Void> enableSaving() {
        return runSync("save-on");
    }

    private CompletableFuture<Void> runSync(String command) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                ConsoleCommandSender console = Bukkit.getConsoleSender();
                Bukkit.dispatchCommand(console, command);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }
}
