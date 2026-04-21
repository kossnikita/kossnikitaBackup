package dev.kossnikita.borgbackup.core.process;

public record CommandResult(int exitCode, boolean timedOut, String stdout, String stderr) {
    public boolean success() {
        return !timedOut && exitCode == 0;
    }
}
