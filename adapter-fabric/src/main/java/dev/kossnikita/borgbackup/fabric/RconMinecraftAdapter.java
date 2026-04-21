package dev.kossnikita.borgbackup.fabric;

import dev.kossnikita.borgbackup.core.MinecraftAdapter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

public final class RconMinecraftAdapter implements MinecraftAdapter {
    private static final int PACKET_TYPE_COMMAND = 2;
    private static final int PACKET_TYPE_AUTH = 3;
    private static final int READ_TIMEOUT_MS = 5000;

    private final String host;
    private final int port;
    private final String password;

    public RconMinecraftAdapter(Path gameDir) throws IOException {
        Path serverPropertiesPath = gameDir.resolve("server.properties");
        Properties properties = new Properties();
        try (var in = Files.newInputStream(serverPropertiesPath)) {
            properties.load(in);
        }

        boolean enabled = Boolean.parseBoolean(properties.getProperty("enable-rcon", "false"));
        if (!enabled) {
            throw new IllegalStateException("RCON is disabled in server.properties");
        }

        String value = properties.getProperty("rcon.password", "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("RCON password is missing in server.properties");
        }

        this.host = "127.0.0.1";
        this.port = Integer.parseInt(properties.getProperty("rcon.port", "25575"));
        this.password = value;
    }

    @Override
    public CompletableFuture<Void> disableSaving() {
        return runCommand("save-off");
    }

    @Override
    public CompletableFuture<Void> flushSave() {
        return runCommand("save-all flush");
    }

    @Override
    public CompletableFuture<Void> enableSaving() {
        return runCommand("save-on");
    }

    private CompletableFuture<Void> runCommand(String command) {
        try {
            executeRcon(command);
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void executeRcon(String command) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), READ_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            try (InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
                int authId = 1;
                writePacket(out, authId, PACKET_TYPE_AUTH, password);
                Packet authReply = readPacket(in);
                if (authReply.requestId == -1) {
                    throw new IllegalStateException("RCON auth failed");
                }

                int commandId = 2;
                writePacket(out, commandId, PACKET_TYPE_COMMAND, command);
                Packet commandReply = readPacket(in);
                if (commandReply.requestId == -1) {
                    throw new IllegalStateException("RCON command failed: " + command);
                }
            }
        }
    }

    private static void writePacket(OutputStream out, int requestId, int type, String payload) throws IOException {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        int length = 4 + 4 + data.length + 2;

        writeIntLE(out, length);
        writeIntLE(out, requestId);
        writeIntLE(out, type);
        out.write(data);
        out.write(0);
        out.write(0);
        out.flush();
    }

    private static Packet readPacket(InputStream in) throws IOException {
        int length = readIntLE(in);
        byte[] body = in.readNBytes(length);
        if (body.length != length) {
            throw new EOFException("Unexpected EOF while reading RCON packet");
        }

        int requestId = readIntLE(body, 0);
        int type = readIntLE(body, 4);
        int payloadEnd = Math.max(8, body.length - 2);
        String payload = new String(body, 8, payloadEnd - 8, StandardCharsets.UTF_8);

        return new Packet(requestId, type, payload);
    }

    private static int readIntLE(InputStream in) throws IOException {
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        int b4 = in.read();
        if ((b1 | b2 | b3 | b4) < 0) {
            throw new EOFException("Unexpected EOF while reading integer");
        }
        return (b1 & 0xff) | ((b2 & 0xff) << 8) | ((b3 & 0xff) << 16) | ((b4 & 0xff) << 24);
    }

    private static int readIntLE(byte[] source, int offset) {
        return (source[offset] & 0xff)
            | ((source[offset + 1] & 0xff) << 8)
            | ((source[offset + 2] & 0xff) << 16)
            | ((source[offset + 3] & 0xff) << 24);
    }

    private static void writeIntLE(OutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private record Packet(int requestId, int type, String payload) {
    }
}
