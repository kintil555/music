package com.musicplayer.client.audio;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Mengelola yt-dlp binary secara otomatis.
 * - Cek PATH sistem terlebih dahulu
 * - Jika tidak ada, auto-download dari GitHub releases ke config/musicplayer/yt-dlp
 * - Support Windows (.exe) dan Linux/macOS
 */
@Environment(EnvType.CLIENT)
public class YtDlpManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MusicPlayer/YtDlp");

    // URL GitHub releases terbaru yt-dlp
    private static final String RELEASES_BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
    private static final String WIN_BINARY    = "yt-dlp.exe";
    private static final String LINUX_BINARY  = "yt-dlp";
    private static final String MAC_BINARY    = "yt-dlp_macos";

    private static volatile String resolvedPath = null;
    private static volatile boolean checked = false;

    /**
     * Kembalikan path yt-dlp yang siap dipakai, atau null jika gagal.
     * Download otomatis jika belum ada.
     * @param statusCallback dipanggil dengan status message (opsional, bisa null)
     */
    public static synchronized String getOrDownload(java.util.function.Consumer<String> statusCallback) {
        if (checked && resolvedPath != null) return resolvedPath;

        // 1. Cek PATH sistem dulu
        String systemPath = findOnPath();
        if (systemPath != null) {
            LOGGER.info("[MusicPlayer] yt-dlp ditemukan di PATH: {}", systemPath);
            resolvedPath = systemPath;
            checked = true;
            return resolvedPath;
        }

        // 2. Cek di folder config mod
        Path localPath = getLocalBinaryPath();
        if (Files.exists(localPath) && isExecutable(localPath)) {
            if (verifyBinary(localPath.toString())) {
                LOGGER.info("[MusicPlayer] yt-dlp lokal ditemukan: {}", localPath);
                resolvedPath = localPath.toString();
                checked = true;
                return resolvedPath;
            }
        }

        // 3. Download otomatis
        if (statusCallback != null) statusCallback.accept("Downloading yt-dlp...");
        try {
            downloadBinary(localPath, statusCallback);
            if (Files.exists(localPath)) {
                makeExecutable(localPath);
                if (verifyBinary(localPath.toString())) {
                    LOGGER.info("[MusicPlayer] yt-dlp berhasil di-download ke: {}", localPath);
                    resolvedPath = localPath.toString();
                    checked = true;
                    return resolvedPath;
                }
            }
        } catch (Exception e) {
            LOGGER.error("[MusicPlayer] Gagal download yt-dlp", e);
        }

        checked = true;
        return null;
    }

    /** Reset cache agar dicek ulang (berguna setelah update manual) */
    public static void reset() {
        checked = false;
        resolvedPath = null;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String findOnPath() {
        String[] candidates = isWindows()
                ? new String[]{"yt-dlp.exe", "yt-dlp"}
                : new String[]{"yt-dlp"};

        for (String cmd : candidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor();
                if (p.exitValue() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Path getLocalBinaryPath() {
        Path configDir = FabricLoader.getInstance().getConfigDir()
                .resolve("musicplayer");
        try {
            Files.createDirectories(configDir);
        } catch (IOException ignored) {}

        String binaryName = isWindows() ? WIN_BINARY : (isMac() ? MAC_BINARY : LINUX_BINARY);
        return configDir.resolve(binaryName);
    }

    private static void downloadBinary(Path target, java.util.function.Consumer<String> status)
            throws Exception {
        String binaryName = isWindows() ? WIN_BINARY : (isMac() ? MAC_BINARY : LINUX_BINARY);
        String url = RELEASES_BASE + binaryName;

        LOGGER.info("[MusicPlayer] Downloading yt-dlp dari {}", url);
        if (status != null) status.accept("Downloading yt-dlp from GitHub...");

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        HttpResponse<InputStream> resp = client.send(
                HttpRequest.newBuilder(new URI(url))
                        .header("User-Agent", "MusicPlayerMod/1.0")
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );

        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " saat download yt-dlp");
        }

        // Tulis ke file sementara dulu, lalu atomic move
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(tmp,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        LOGGER.info("[MusicPlayer] yt-dlp berhasil diunduh ke {}", target);
        if (status != null) status.accept("yt-dlp downloaded!");
    }

    private static boolean verifyBinary(String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(path, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isExecutable(Path p) {
        return p.toFile().canExecute();
    }

    private static void makeExecutable(Path p) {
        if (isWindows()) return; // Windows tidak perlu chmod
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(p, perms);
        } catch (Exception e) {
            // Fallback: coba lewat Runtime
            try {
                Runtime.getRuntime().exec(new String[]{"chmod", "+x", p.toString()}).waitFor();
            } catch (Exception ignored) {}
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("darwin");
    }
}
