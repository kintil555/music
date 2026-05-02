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
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Auto-download dan kelola ffmpeg static binary.
 * Dipakai oleh yt-dlp untuk konversi audio ke WAV.
 *
 * Source: BtbN/FFmpeg-Builds (static builds, konsisten URL)
 * Windows: ffmpeg-master-latest-win64-gpl.zip  -> bin/ffmpeg.exe
 * Linux:   ffmpeg-master-latest-linux64-gpl.tar.xz -> ffmpeg
 */
@Environment(EnvType.CLIENT)
public class FfmpegManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MusicPlayer/Ffmpeg");

    // BtbN static builds — URL selalu fresh karena "latest"
    private static final String WIN_URL   = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";
    private static final String LINUX_URL = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linux64-gpl.tar.xz";
    private static final String MAC_URL   = "https://evermeet.cx/ffmpeg/getrelease/ffmpeg/zip"; // macOS static build

    private static volatile String resolvedPath = null;
    private static volatile boolean checked = false;

    /**
     * Kembalikan path ffmpeg yang siap dipakai, atau null jika gagal.
     */
    public static synchronized String getOrDownload(Consumer<String> statusCallback) {
        if (checked && resolvedPath != null) return resolvedPath;

        // 1. Cek PATH sistem
        String sys = findOnPath();
        if (sys != null) {
            LOGGER.info("[MusicPlayer] ffmpeg ditemukan di PATH: {}", sys);
            resolvedPath = sys;
            checked = true;
            return resolvedPath;
        }

        // 2. Cek folder config mod
        Path local = getLocalBinaryPath();
        if (Files.exists(local) && local.toFile().canExecute()) {
            if (verify(local.toString())) {
                LOGGER.info("[MusicPlayer] ffmpeg lokal: {}", local);
                resolvedPath = local.toString();
                checked = true;
                return resolvedPath;
            }
        }

        // 3. Download
        if (statusCallback != null) statusCallback.accept("Downloading ffmpeg...");
        try {
            download(local, statusCallback);
            if (Files.exists(local) && verify(local.toString())) {
                LOGGER.info("[MusicPlayer] ffmpeg berhasil di-download: {}", local);
                resolvedPath = local.toString();
                checked = true;
                return resolvedPath;
            }
        } catch (Exception e) {
            LOGGER.error("[MusicPlayer] Gagal download ffmpeg", e);
        }

        checked = true;
        return null;
    }

    public static void reset() {
        checked = false;
        resolvedPath = null;
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private static String findOnPath() {
        for (String cmd : new String[]{"ffmpeg", "ffmpeg.exe"}) {
            try {
                Process p = new ProcessBuilder(cmd, "-version")
                        .redirectErrorStream(true).start();
                p.waitFor();
                if (p.exitValue() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Path getLocalBinaryPath() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("musicplayer");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        return dir.resolve(isWindows() ? "ffmpeg.exe" : "ffmpeg");
    }

    private static void download(Path target, Consumer<String> status) throws Exception {
        String url = isWindows() ? WIN_URL : (isMac() ? MAC_URL : LINUX_URL);
        LOGGER.info("[MusicPlayer] Downloading ffmpeg dari {}", url);
        if (status != null) status.accept("Downloading ffmpeg (~60MB)...");

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
            throw new IOException("HTTP " + resp.statusCode() + " saat download ffmpeg");
        }

        if (status != null) status.accept("Extracting ffmpeg...");

        try (InputStream body = resp.body()) {
            if (isWindows() || isMac()) {
                extractFromZip(body, target);
            } else {
                extractFromTarXz(body, target);
            }
        }

        makeExecutable(target);
        LOGGER.info("[MusicPlayer] ffmpeg extracted to {}", target);
        if (status != null) status.accept("ffmpeg ready!");
    }

    private static void extractFromZip(InputStream zipStream, Path target) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            String targetName = isWindows() ? "ffmpeg.exe" : "ffmpeg";
            while ((entry = zis.getNextEntry()) != null) {
                // Cari ffmpeg.exe / ffmpeg di dalam bin/ folder ZIP
                String name = entry.getName();
                if (!entry.isDirectory() && name.endsWith("bin/" + targetName)) {
                    writeStream(zis, target);
                    return;
                }
                zis.closeEntry();
            }
        }
        throw new IOException("ffmpeg binary tidak ditemukan di dalam ZIP");
    }

    private static void extractFromTarXz(InputStream xzStream, Path target) throws Exception {
        // Java tidak punya built-in XZ. Gunakan workaround: jalankan tar jika tersedia,
        // atau download versi fallback dari GitHub releases JSON
        Path tmpXz = target.resolveSibling("ffmpeg.tar.xz");
        try {
            // Tulis XZ ke disk dulu
            writeStream(xzStream, tmpXz);

            // Coba extract via tar (tersedia di Linux/Mac)
            Process tar = new ProcessBuilder(
                    "tar", "-xJf", tmpXz.toString(),
                    "--strip-components=2",          // hapus folder prefix (ffmpeg-master-.../bin/ffmpeg)
                    "--wildcards", "*/bin/ffmpeg",
                    "-C", target.getParent().toString()
            ).redirectErrorStream(true).start();
            tar.waitFor();

            if (tar.exitValue() != 0 || !Files.exists(target)) {
                // Fallback: coba tanpa wildcards, cari manual
                Process tar2 = new ProcessBuilder(
                        "tar", "-xJf", tmpXz.toString(),
                        "-C", target.getParent().toString()
                ).redirectErrorStream(true).start();
                tar2.waitFor();

                // Cari ffmpeg binary yang di-extract
                Path found = Files.walk(target.getParent())
                        .filter(p -> p.getFileName().toString().equals("ffmpeg"))
                        .filter(p -> !Files.isDirectory(p))
                        .findFirst().orElse(null);
                if (found != null && !found.equals(target)) {
                    Files.move(found, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            try { Files.deleteIfExists(tmpXz); } catch (Exception ignored) {}
        }
    }

    private static void writeStream(InputStream in, Path target) throws Exception {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void makeExecutable(Path p) {
        if (isWindows()) return;
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(p, perms);
        } catch (Exception ignored) {
            try { Runtime.getRuntime().exec(new String[]{"chmod", "+x", p.toString()}).waitFor(); }
            catch (Exception e2) { LOGGER.warn("chmod gagal: {}", e2.getMessage()); }
        }
    }

    private static boolean verify(String path) {
        try {
            Process p = new ProcessBuilder(path, "-version")
                    .redirectErrorStream(true).start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
    static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("darwin");
    }
}
