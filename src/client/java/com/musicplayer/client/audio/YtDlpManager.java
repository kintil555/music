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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.Consumer;

@Environment(EnvType.CLIENT)
public class YtDlpManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MusicPlayer/YtDlp");

    private static final String RELEASES_BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
    private static final String WIN_BINARY    = "yt-dlp.exe";
    private static final String LINUX_BINARY  = "yt-dlp";
    private static final String MAC_BINARY    = "yt-dlp_macos";

    private static volatile String  resolvedPath = null;
    private static volatile boolean checked      = false;

    public static synchronized String getOrDownload(Consumer<String> statusCallback) {
        if (checked && resolvedPath != null) return resolvedPath;

        String systemPath = findOnPath();
        if (systemPath != null) {
            resolvedPath = systemPath; checked = true; return resolvedPath;
        }

        Path localPath = getLocalBinaryPath();
        if (Files.exists(localPath) && localPath.toFile().canExecute()) {
            if (verifyBinary(localPath.toString())) {
                resolvedPath = localPath.toString(); checked = true; return resolvedPath;
            }
        }

        if (statusCallback != null) statusCallback.accept("Downloading yt-dlp...");
        try {
            downloadBinary(localPath, statusCallback);
            if (Files.exists(localPath)) {
                makeExecutable(localPath);
                if (verifyBinary(localPath.toString())) {
                    resolvedPath = localPath.toString(); checked = true; return resolvedPath;
                }
            }
        } catch (Exception e) {
            LOGGER.error("[MusicPlayer] Gagal download yt-dlp", e);
        }

        checked = true;
        return null;
    }

    public static void reset() { checked = false; resolvedPath = null; }

    /**
     * Cek apakah URL ini sudah pernah didownload (ada di cache).
     */
    public static Path getCachedWav(String ytUrl) {
        return buildCachePath(ytUrl);
    }

    /**
     * Buat path cache berdasarkan hash URL agar nama file unik.
     * Disimpan di config/musicplayer/cache/<hash>.wav
     */
    public static Path buildCachePath(String ytUrl) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(ytUrl.getBytes());
            String hex = HexFormat.of().formatHex(hash);
            Path cacheDir = getConfigDir().resolve("cache");
            Files.createDirectories(cacheDir);
            return cacheDir.resolve(hex + ".wav");
        } catch (Exception e) {
            // Fallback ke temp jika gagal
            try {
                return Files.createTempFile("musicplayer_yt_", ".wav");
            } catch (Exception e2) {
                return Path.of(System.getProperty("java.io.tmpdir"), "musicplayer_yt.wav");
            }
        }
    }

    private static Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("musicplayer");
    }

    private static String findOnPath() {
        String[] candidates = isWindows() ? new String[]{"yt-dlp.exe", "yt-dlp"} : new String[]{"yt-dlp"};
        for (String cmd : candidates) {
            try {
                Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
                p.waitFor();
                if (p.exitValue() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Path getLocalBinaryPath() {
        Path configDir = getConfigDir();
        try { Files.createDirectories(configDir); } catch (IOException ignored) {}
        return configDir.resolve(isWindows() ? WIN_BINARY : (isMac() ? MAC_BINARY : LINUX_BINARY));
    }

    private static void downloadBinary(Path target, Consumer<String> status) throws Exception {
        String binaryName = isWindows() ? WIN_BINARY : (isMac() ? MAC_BINARY : LINUX_BINARY);
        String url = RELEASES_BASE + binaryName;
        LOGGER.info("[MusicPlayer] Downloading yt-dlp dari {}", url);
        if (status != null) status.accept("Downloading yt-dlp...");

        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpResponse<InputStream> resp = client.send(
                HttpRequest.newBuilder(new URI(url)).header("User-Agent", "MusicPlayerMod/1.0").build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());

        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[8192]; int read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (status != null) status.accept("yt-dlp ready!");
    }

    private static boolean verifyBinary(String path) {
        try {
            Process p = new ProcessBuilder(path, "--version").redirectErrorStream(true).start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    private static void makeExecutable(Path p) {
        if (isWindows()) return;
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(p, perms);
        } catch (Exception e) {
            try { Runtime.getRuntime().exec(new String[]{"chmod", "+x", p.toString()}).waitFor(); }
            catch (Exception ignored) {}
        }
    }

    static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }
    static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("darwin");
    }
}
