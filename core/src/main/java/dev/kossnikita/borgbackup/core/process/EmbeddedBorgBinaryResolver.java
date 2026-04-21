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

final class EmbeddedBorgBinaryResolver {
    private static final String ENV_BORG_EXECUTABLE = "BORG_EXECUTABLE";
    private static final String ENV_BORG_DOWNLOAD_URL = "BORG_DOWNLOAD_URL";
    private static final String ENV_BORG_DOWNLOAD_SHA256 = "BORG_DOWNLOAD_SHA256";
    private static final String ENV_BORG_EMBEDDED_HOME = "BORG_EMBEDDED_HOME";

    private EmbeddedBorgBinaryResolver() {
    }

    static Path resolve(Map<String, String> environment, String workingDirectory) throws IOException {
        String explicit = trimToNull(environment.get(ENV_BORG_EXECUTABLE));
        if (explicit != null) {
            Path explicitPath = Path.of(explicit).toAbsolutePath().normalize();
            if (!Files.exists(explicitPath)) {
                throw new IOException("BORG_EXECUTABLE does not exist: " + explicitPath);
            }
            ensureExecutable(explicitPath);
            return explicitPath;
        }

        String fileName = isWindows() ? "borg.exe" : "borg";
        Path home = resolveHome(environment, workingDirectory);
        Path binaryPath = home.resolve(fileName);
        if (Files.exists(binaryPath)) {
            ensureExecutable(binaryPath);
            return binaryPath;
        }

        Files.createDirectories(home);

        String downloadUrl = trimToNull(environment.get(ENV_BORG_DOWNLOAD_URL));
        if (downloadUrl == null) {
            downloadUrl = defaultDownloadUrl();
        }

        if (downloadUrl == null) {
            throw new IOException(
                "No embedded Borg binary configured for this OS/arch. "
                    + "Set BORG_DOWNLOAD_URL (and optionally BORG_DOWNLOAD_SHA256) in [environment]."
            );
        }

        downloadBinary(downloadUrl, binaryPath);

        String expectedSha = trimToNull(environment.get(ENV_BORG_DOWNLOAD_SHA256));
        if (expectedSha != null) {
            verifySha256(binaryPath, expectedSha);
        }

        ensureExecutable(binaryPath);
        return binaryPath;
    }

    private static Path resolveHome(Map<String, String> environment, String workingDirectory) {
        String customHome = trimToNull(environment.get(ENV_BORG_EMBEDDED_HOME));
        if (customHome != null) {
            return Path.of(customHome).toAbsolutePath().normalize();
        }

        Path base;
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            base = Path.of(workingDirectory).toAbsolutePath().normalize();
        } else {
            base = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
        return base.resolve(".borgbackup").resolve("bin");
    }

    private static void downloadBinary(String url, Path targetPath) throws IOException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading embedded Borg binary", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to download embedded Borg binary. HTTP status: " + response.statusCode());
        }

        Path tmp = targetPath.resolveSibling(targetPath.getFileName().toString() + ".tmp");
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(tmp)) {
            in.transferTo(out);
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
            throw new IOException("Failed to verify SHA-256 for embedded Borg binary", e);
        }

        if (!actual.equals(normalizedExpected)) {
            throw new IOException(
                "Embedded Borg checksum mismatch. expected=" + normalizedExpected + " actual=" + actual
            );
        }
    }

    private static void ensureExecutable(Path path) throws IOException {
        try {
            path.toFile().setExecutable(true, false);
        } catch (SecurityException e) {
            throw new IOException("Cannot mark Borg binary as executable: " + path, e);
        }
    }

    private static String defaultDownloadUrl() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        if (os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"))) {
            return "https://github.com/borgbackup/borg/releases/download/1.4.4/borg-linux-glibc231-x86_64";
        }
        if (os.contains("linux") && (arch.equals("aarch64") || arch.equals("arm64"))) {
            return "https://github.com/borgbackup/borg/releases/download/1.4.4/borg-linux-glibc235-arm64-gh";
        }
        if (os.contains("mac") && (arch.equals("aarch64") || arch.equals("arm64"))) {
            return "https://github.com/borgbackup/borg/releases/download/1.4.4/borg-macos-15-arm64-gh";
        }
        if (os.contains("mac") && (arch.equals("amd64") || arch.equals("x86_64"))) {
            return "https://github.com/borgbackup/borg/releases/download/1.4.4/borg-macos-15-x86_64-gh";
        }

        return null;
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
