package dev.kossnikita.borgbackup.core.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
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

public final class BorgExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(BorgExecutor.class);
    private static final String ENV_BORG_EXECUTABLE = "BORG_EXECUTABLE";
    private static final String ENV_BORG_DOWNLOAD_URL = "BORG_DOWNLOAD_URL";
    private static final String ENV_BORG_DOWNLOAD_SHA256 = "BORG_DOWNLOAD_SHA256";
    private static final String ENV_BORG_EMBEDDED_HOME = "BORG_EMBEDDED_HOME";
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();

    public CommandResult run(List<String> command, Map<String, String> environment, Duration timeout, String workingDirectory) {
        List<String> resolvedCommand;
        try {
            resolvedCommand = resolveCommand(command, environment, workingDirectory);
        } catch (IOException e) {
            return new CommandResult(-1, false, "", e.getMessage());
        }

        ProcessBuilder builder = new ProcessBuilder(resolvedCommand);
        builder.environment().putAll(environment);
        builder.environment().remove(ENV_BORG_DOWNLOAD_URL);
        builder.environment().remove(ENV_BORG_DOWNLOAD_SHA256);
        builder.environment().remove(ENV_BORG_EMBEDDED_HOME);
        if (!environment.containsKey(ENV_BORG_EXECUTABLE)) {
            builder.environment().remove(ENV_BORG_EXECUTABLE);
        }
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            builder.directory(new java.io.File(workingDirectory));
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

    private List<String> resolveCommand(List<String> command, Map<String, String> environment, String workingDirectory) throws IOException {
        if (command.isEmpty()) {
            return command;
        }

        if (!"borg".equals(command.get(0))) {
            return command;
        }

        Path borgBinary = EmbeddedBorgBinaryResolver.resolve(environment, workingDirectory);
        List<String> resolved = new ArrayList<>(command);
        resolved.set(0, borgBinary.toString());
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
                        LOGGER.warn("borg stderr: {}", line);
                    } else {
                        LOGGER.info("borg stdout: {}", line);
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Failed to read process stream", e);
            }
            return result.toString();
        }, ioExecutor);
    }
}
