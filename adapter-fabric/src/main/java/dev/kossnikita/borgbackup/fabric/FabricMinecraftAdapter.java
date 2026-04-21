package dev.kossnikita.borgbackup.fabric;

import dev.kossnikita.borgbackup.core.MinecraftAdapter;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;

public final class FabricMinecraftAdapter implements MinecraftAdapter {
    private final MinecraftServer server;

    public FabricMinecraftAdapter(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<Void> disableSaving() {
        return runOnServer("save-off");
    }

    @Override
    public CompletableFuture<Void> flushSave() {
        return runOnServer("save-all flush");
    }

    @Override
    public CompletableFuture<Void> enableSaving() {
        return runOnServer("save-on");
    }

    private CompletableFuture<Void> runOnServer(String command) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerCommandSource source = server.getCommandSource().withLevel(4);
                server.getCommandManager().executeWithPrefix(source, command);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }
}
