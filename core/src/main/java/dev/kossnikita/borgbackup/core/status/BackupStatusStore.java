package dev.kossnikita.borgbackup.core.status;

import dev.kossnikita.borgbackup.core.BackupStatus;
import java.util.concurrent.atomic.AtomicReference;

public final class BackupStatusStore {
    private final AtomicReference<BackupStatus> status = new AtomicReference<>(BackupStatus.empty());

    public BackupStatus get() {
        return status.get();
    }

    public void update(BackupStatus next) {
        status.set(next);
    }
}
