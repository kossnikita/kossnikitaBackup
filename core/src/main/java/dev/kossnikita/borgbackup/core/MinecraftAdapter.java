package dev.kossnikita.borgbackup.core;

import java.util.concurrent.CompletableFuture;

public interface MinecraftAdapter {
    CompletableFuture<Void> disableSaving();

    CompletableFuture<Void> flushSave();

    CompletableFuture<Void> enableSaving();
}
