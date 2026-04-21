package dev.kossnikita.borgbackup.core;

import java.util.concurrent.CompletableFuture;

public interface MinecraftAdapter {
    boolean consumePlayerActivityFlag();

    CompletableFuture<Void> disableSaving();

    CompletableFuture<Void> flushSave();

    CompletableFuture<Void> enableSaving();
}
