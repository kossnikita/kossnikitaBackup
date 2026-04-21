package dev.kossnikita.borgbackup.core.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

final class EmbeddedResticBinaryResolver {
    private static final String ENV_RESTIC_EXECUTABLE = "RESTIC_EXECUTABLE";
    private static final String ENV_RESTIC_DOWNLOAD_URL = "RESTIC_DOWNLOAD_URL";
    private static final String ENV_RESTIC_DOWNLOAD_SHA256 = "RESTIC_DOWNLOAD_SHA256";
    private static final String ENV_RESTIC_EMBEDDED_HOME = "RESTIC_EMBEDDED_HOME";

    private EmbeddedResticBinaryResolver() {
    }

    static Path resolve(Map<String, String> environment, String workingDirectory) throws IOException {
        String explicit = trimToNull(environment.get(ENV_RESTIC_EXECUTABLE));
        if (explicit != null) {
            Path explicitPath = Path.of(explicit).toAbsolutePath().normalize();
            if (!Files.exists(explicitPath)) {
                throw new IOException("RESTIC_EXECUTABLE does not exist: " + explicitPath);
            }
            ensureExecutable(explicitPath);
            return explicitPath;
        }

        String fileName = isWindows() ? "restic.exe" : "restic";
        Path home = resolveHome(environment, workingDirectory);
        Path binaryPath = home.resolve(fileName);
        if (Files.exists(binaryPath)) {
            ensureExecutable(binaryPath);
            return binaryPath;
        }

        String downloadUrl = trimToNull(environment.get(ENV_RESTIC_DOWNLOAD_URL));
        if (downloadUrl == null) {
            return null;
        }

        Files.createDirectories(home);
        downloadBinary(downloadUrl, binaryPath);

        String expectedSha = trimToNull(environment.get(ENV_RESTIC_DOWNLOAD_SHA256));
        if (expectedSha != null) {
            verifySha256(binaryPath, expectedSha);
        }

        ensureExecutable(binaryPath);
        return binaryPath;
    }

    private static Path resolveHome(Map<String, String> environment, String workingDirectory) {
        String customHome = trimToNull(environment.get(ENV_RESTIC_EMBEDDED_HOME));
        if (customHome != null) {
            return Path.of(customHome).toAbsolutePath().normalize();
        }

        Path base;
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            base = Path.of(workingDirectory).toAbsolutePath().normalize();
        } else {
            base = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
        return base.resolve("mods").resolve("restic").resolve("bin");
    }

    private static void downloadBinary(String url, Path targetPath) throws IOException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading embedded restic binary", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to download embedded restic binary. HTTP status: " + response.statusCode());
        }

        Path tmp = targetPath.resolveSibling(targetPath.getFileName().toString() + ".tmp");
        try (InputStream in = response.body()) {
            if (url.endsWith(".bz2")) {
                try (BZip2CompressorInputStream bzIn = new BZip2CompressorInputStream(in);
                     OutputStream out = Files.newOutputStream(tmp)) {
                    bzIn.transferTo(out);
                }
            } else {
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    in.transferTo(out);
                }
            }
        }

        Files.move(tmp, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void verifySha256(Path file, String expected) throws IOException {
        String normalizedExpected = expected.trim().toLowerCase(Locale.ROOT);
        String actual;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(file);
            actual = HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IOException("Failed to verify SHA-256 for embedded restic binary", e);
        }

        if (!actual.equals(normalizedExpected)) {
            throw new IOException(
                "Embedded restic checksum mismatch. expected=" + normalizedExpected + " actual=" + actual
            );
        }
    }

    private static void ensureExecutable(Path path) throws IOException {
        try {
            path.toFile().setExecutable(true, false);
        } catch (SecurityException e) {
            throw new IOException("Cannot mark restic binary as executable: " + path, e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
