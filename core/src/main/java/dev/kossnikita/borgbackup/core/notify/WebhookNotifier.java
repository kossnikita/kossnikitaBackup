package dev.kossnikita.borgbackup.core.notify;

import dev.kossnikita.borgbackup.core.config.BackupConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebhookNotifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookNotifier.class);
    private final HttpClient client = HttpClient.newHttpClient();

    public void notify(BackupConfig.WebhookConfig webhook, String event, String message) {
        if (!webhook.enabled() || webhook.url().isBlank()) {
            return;
        }

        String payload = "{\"event\":\"" + escape(event) + "\",\"message\":\"" + escape(message) + "\"}";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(webhook.url()))
            .timeout(webhook.timeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload));

        if (!webhook.token().isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + webhook.token());
        }

        try {
            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            LOGGER.info("Webhook event={} status={}", event, response.statusCode());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.error("Failed to send webhook", e);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
