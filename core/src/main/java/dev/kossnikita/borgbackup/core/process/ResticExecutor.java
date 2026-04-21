package dev.kossnikita.borgbackup.core.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ResticExecutor implements BackupToolExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResticExecutor.class);
    private static final String ENV_RESTIC_EXECUTABLE = "RESTIC_EXECUTABLE";
    private static final String ENV_RESTIC_DOWNLOAD_URL = "RESTIC_DOWNLOAD_URL";
    private static final String ENV_RESTIC_DOWNLOAD_SHA256 = "RESTIC_DOWNLOAD_SHA256";
    private static final String ENV_RESTIC_EMBEDDED_HOME = "RESTIC_EMBEDDED_HOME";
    private static final String ENV_TMPDIR = "TMPDIR";
    private static final String ENV_TMP = "TMP";
    private static final String ENV_TEMP = "TEMP";
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();

    @Override
    public CommandResult run(List<String> command, Map<String, String> environment, Duration timeout, String workingDirectory) {
        List<String> resolvedCommand;
        try {
            resolvedCommand = resolveCommand(command, environment, workingDirectory);
        } catch (IOException e) {
            return new CommandResult(-1, false, "", e.getMessage());
        }

        ProcessBuilder builder = new ProcessBuilder(resolvedCommand);
        builder.environment().putAll(environment);
        builder.environment().remove(ENV_RESTIC_DOWNLOAD_URL);
        builder.environment().remove(ENV_RESTIC_DOWNLOAD_SHA256);
        builder.environment().remove(ENV_RESTIC_EMBEDDED_HOME);
        if (!environment.containsKey(ENV_RESTIC_EXECUTABLE)) {
            builder.environment().remove(ENV_RESTIC_EXECUTABLE);
        }
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            builder.directory(new java.io.File(workingDirectory));
        }

        String effectiveTempDir = configureTempDirectory(builder, environment, workingDirectory);
        if (effectiveTempDir != null) {
            LOGGER.info("Using temp directory for restic process: {}", effectiveTempDir);
        }

        LOGGER.info("Running command: {}", String.join(" ", resolvedCommand));

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new CommandResult(-1, false, "", e.getMessage());
        }

        CompletableFuture<String> stdoutFuture = stream(process.getInputStream(), false);
        CompletableFuture<String> stderrFuture = stream(process.getErrorStream(), true);

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new CommandResult(-1, true, "", "Interrupted while waiting for process");
        }

        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String out = stdoutFuture.join();
            String err = stderrFuture.join();
            return new CommandResult(-1, true, out, err + System.lineSeparator() + "Process timed out");
        }

        String out = stdoutFuture.join();
        String err = stderrFuture.join();
        return new CommandResult(process.exitValue(), false, out, err);
    }

    private String configureTempDirectory(ProcessBuilder builder, Map<String, String> environment, String workingDirectory) {
        String configured = firstNonBlank(environment.get(ENV_TMPDIR), environment.get(ENV_TMP), environment.get(ENV_TEMP));
        String tempDir = configured;

        if (tempDir == null) {
            Path base;
            if (workingDirectory != null && !workingDirectory.isBlank()) {
                base = Path.of(workingDirectory).toAbsolutePath().normalize();
            } else {
                base = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            }
            tempDir = base.resolve("tmp").resolve("restic").toString();
        }

        try {
            Files.createDirectories(Path.of(tempDir));
        } catch (IOException e) {
            LOGGER.warn("Failed to create temp directory {}. Falling back to system temp", tempDir, e);
            return null;
        }

        builder.environment().put(ENV_TMPDIR, tempDir);
        builder.environment().put(ENV_TMP, tempDir);
        builder.environment().put(ENV_TEMP, tempDir);
        return tempDir;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private List<String> resolveCommand(List<String> command, Map<String, String> environment, String workingDirectory) throws IOException {
        if (command.isEmpty()) {
            return command;
        }

        if (!"restic".equals(command.get(0))) {
            return command;
        }

        Path resticBinary = EmbeddedResticBinaryResolver.resolve(environment, workingDirectory);
        if (resticBinary == null) {
            return command;
        }

        List<String> resolved = new ArrayList<>(command);
        resolved.set(0, resticBinary.toString());
        return resolved;
    }

    private CompletableFuture<String> stream(java.io.InputStream input, boolean stderr) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder result = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line).append(System.lineSeparator());
                    if (stderr) {
                        LOGGER.warn("restic stderr: {}", line);
                    } else {
                        LOGGER.info("restic stdout: {}", line);
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Failed to read process stream", e);
            }
            return result.toString();
        }, ioExecutor);
    }
}
