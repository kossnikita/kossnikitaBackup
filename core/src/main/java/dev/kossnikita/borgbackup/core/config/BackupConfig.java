package dev.kossnikita.borgbackup.core.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record BackupConfig(
    String repository,
    List<String> paths,
    List<String> exclude,
    String compression,
    String archivePrefix,
    String workingDirectory,
    Duration timeout,
    Map<String, String> environment,
    ScheduleConfig schedule,
    RetentionConfig retention,
    RetryConfig retry,
    HooksConfig hooks,
    WebhookConfig webhook
) {
    public record ScheduleConfig(Duration interval, String cron, boolean runOnStartup) {
    }

    public record RetentionConfig(int keepLast, boolean compact) {
    }

    public record RetryConfig(boolean enabled, int maxAttempts, Duration delay) {
    }

    public record HooksConfig(List<String> pre, List<String> post, Duration timeout) {
    }

    public record WebhookConfig(boolean enabled, String url, String token, Duration timeout) {
    }
}
