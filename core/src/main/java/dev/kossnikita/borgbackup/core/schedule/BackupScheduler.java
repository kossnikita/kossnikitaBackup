package dev.kossnikita.borgbackup.core.schedule;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import dev.kossnikita.borgbackup.core.config.BackupConfig;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BackupScheduler {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public void start(BackupConfig.ScheduleConfig config, Runnable task) {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        if (config.cron() != null && !config.cron().isBlank()) {
            scheduleCron(config.cron(), task);
            return;
        }

        long delayMs = Math.max(1_000L, config.interval().toMillis());
        executor.scheduleWithFixedDelay(task, delayMs, delayMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running.set(false);
        executor.shutdownNow();
    }

    private void scheduleCron(String cronExpression, Runnable task) {
        CronParser parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
        Cron cron = parser.parse(cronExpression);
        ExecutionTime executionTime = ExecutionTime.forCron(cron);
        scheduleNext(executionTime, task);
    }

    private void scheduleNext(ExecutionTime executionTime, Runnable task) {
        if (!running.get()) {
            return;
        }

        ZonedDateTime now = ZonedDateTime.now();
        Optional<ZonedDateTime> next = executionTime.nextExecution(now);
        long delayMs = next.map(it -> Math.max(1_000L, Duration.between(now, it).toMillis())).orElse(60_000L);

        executor.schedule(() -> {
            try {
                task.run();
            } finally {
                scheduleNext(executionTime, task);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }
}
