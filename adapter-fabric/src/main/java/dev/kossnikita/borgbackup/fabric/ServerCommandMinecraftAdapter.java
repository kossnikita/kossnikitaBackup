package dev.kossnikita.borgbackup.fabric;

import dev.kossnikita.borgbackup.core.MinecraftAdapter;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

public final class ServerCommandMinecraftAdapter implements MinecraftAdapter {
    private final Supplier<MinecraftServer> serverSupplier;
    private final AtomicBoolean playerActivitySinceLastBackup = new AtomicBoolean(false);

    public ServerCommandMinecraftAdapter(Supplier<MinecraftServer> serverSupplier) {
        this.serverSupplier = Objects.requireNonNull(serverSupplier, "serverSupplier");
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
        return runCommand("save-off");
    }

    @Override
    public CompletableFuture<Void> flushSave() {
        return runCommand("save-all flush");
    }

    @Override
    public CompletableFuture<Void> enableSaving() {
        return runCommand("save-on");
    }

    private CompletableFuture<Void> runCommand(String command) {
        MinecraftServer server = serverSupplier.get();
        if (server == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Minecraft server is not started yet"));
        }

        CompletableFuture<Void> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
                result.complete(null);
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }
}
