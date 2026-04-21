package dev.kossnikita.borgbackup.core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

public final class BackupConfigLoader {
    public BackupConfig load(Path configPath) throws IOException {
        TomlParseResult result = Toml.parse(configPath);
        if (result.hasErrors()) {
            throw new IllegalArgumentException("Invalid TOML config: " + result.errors());
        }

        String repository = requiredString(result, "repository");
        List<String> paths = requiredArray(result, "paths");
        List<String> exclude = stringArray(result.getArray("exclude"));
        String compression = orDefault(result.getString("compression"), "lz4");
        String archivePrefix = orDefault(result.getString("archive_prefix"), "mc");
        String workingDirectory = orDefault(result.getString("working_directory"), ".");
        Duration timeout = parseDuration(orDefault(result.getString("timeout"), "2h"));

        Map<String, String> environment = readEnvironment(result.getTable("environment"));
        readSecretEnvironment(result.getTable("environment_files"), configPath.getParent(), environment);

        BackupConfig.ScheduleConfig schedule = readSchedule(result.getTable("schedule"));
        BackupConfig.RetentionConfig retention = readRetention(result.getTable("retention"));
        BackupConfig.RetryConfig retry = readRetry(result.getTable("retry"));
        BackupConfig.HooksConfig hooks = readHooks(result.getTable("hooks"));
        BackupConfig.WebhookConfig webhook = readWebhook(result.getTable("webhook"));

        return new BackupConfig(
            repository,
            paths,
            exclude,
            compression,
            archivePrefix,
            workingDirectory,
            timeout,
            environment,
            schedule,
            retention,
            retry,
            hooks,
            webhook
        );
    }

    private static BackupConfig.ScheduleConfig readSchedule(TomlTable table) {
        if (table == null) {
            return new BackupConfig.ScheduleConfig(Duration.ofHours(3), "", false);
        }
        Duration interval = parseDuration(orDefault(table.getString("interval"), "3h"));
        String cron = orDefault(table.getString("cron"), "");
        Boolean runOnStartup = table.getBoolean("run_on_startup");
        return new BackupConfig.ScheduleConfig(interval, cron, runOnStartup != null && runOnStartup);
    }

    private static BackupConfig.RetentionConfig readRetention(TomlTable table) {
        if (table == null) {
            return new BackupConfig.RetentionConfig(16, false);
        }
        Long keepLast = table.getLong("keep_last");
        Boolean compact = table.getBoolean("compact");
        return new BackupConfig.RetentionConfig(keepLast == null ? 16 : keepLast.intValue(), compact != null && compact);
    }

    private static BackupConfig.RetryConfig readRetry(TomlTable table) {
        if (table == null) {
            return new BackupConfig.RetryConfig(false, 1, Duration.ofMinutes(5));
        }
        Boolean enabled = table.getBoolean("enabled");
        Long maxAttempts = table.getLong("max_attempts");
        String delay = table.getString("delay");
        return new BackupConfig.RetryConfig(
            enabled != null && enabled,
            maxAttempts == null ? 2 : maxAttempts.intValue(),
            parseDuration(orDefault(delay, "5m"))
        );
    }

    private static BackupConfig.HooksConfig readHooks(TomlTable table) {
        if (table == null) {
            return new BackupConfig.HooksConfig(List.of(), List.of(), Duration.ofMinutes(10));
        }
        List<String> pre = stringArray(table.getArray("pre"));
        List<String> post = stringArray(table.getArray("post"));
        Duration timeout = parseDuration(orDefault(table.getString("timeout"), "10m"));
        return new BackupConfig.HooksConfig(pre, post, timeout);
    }

    private static BackupConfig.WebhookConfig readWebhook(TomlTable table) {
        if (table == null) {
            return new BackupConfig.WebhookConfig(false, "", "", Duration.ofSeconds(10));
        }
        Boolean enabled = table.getBoolean("enabled");
        return new BackupConfig.WebhookConfig(
            enabled != null && enabled,
            orDefault(table.getString("url"), ""),
            orDefault(table.getString("token"), ""),
            parseDuration(orDefault(table.getString("timeout"), "10s"))
        );
    }

    private static Map<String, String> readEnvironment(TomlTable table) {
        Map<String, String> environment = new HashMap<>();
        if (table == null) {
            return environment;
        }

        for (String key : table.keySet()) {
            Object value = table.get(key);
            if (value instanceof String stringValue) {
                environment.put(key, stringValue);
            }
        }
        return environment;
    }

    private static void readSecretEnvironment(TomlTable table, Path baseDir, Map<String, String> env) throws IOException {
        if (table == null) {
            return;
        }

        for (String envName : table.keySet()) {
            String secretPath = table.getString(envName);
            if (secretPath == null || secretPath.isBlank()) {
                continue;
            }

            Path resolved = baseDir.resolve(secretPath).normalize();
            String content = Files.readString(resolved).trim();
            env.put(envName, content);
        }
    }

    private static String requiredString(TomlParseResult result, String key) {
        String value = result.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required string key: " + key);
        }
        return value;
    }

    private static List<String> requiredArray(TomlParseResult result, String key) {
        TomlArray array = result.getArray(key);
        List<String> values = stringArray(array);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Missing required array key: " + key);
        }
        return values;
    }

    private static List<String> stringArray(TomlArray array) {
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            if (value instanceof String stringValue) {
                values.add(stringValue);
            }
        }
        return values;
    }

    private static String orDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public static Duration parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ofHours(1);
        }

        String raw = value.trim().toLowerCase();
        if (raw.matches("^\\d+$")) {
            return Duration.ofSeconds(Long.parseLong(raw));
        }

        long number = Long.parseLong(raw.substring(0, raw.length() - 1));
        char suffix = raw.charAt(raw.length() - 1);
        return switch (suffix) {
            case 's' -> Duration.ofSeconds(number);
            case 'm' -> Duration.ofMinutes(number);
            case 'h' -> Duration.ofHours(number);
            case 'd' -> Duration.ofDays(number);
            default -> throw new IllegalArgumentException("Unsupported duration: " + value);
        };
    }
}
