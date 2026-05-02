package com.musicplayer.client.audio;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Cari lagu di YouTube menggunakan yt-dlp (tanpa API key).
 * Mengembalikan daftar SearchResult berisi judul, durasi, channel, dan URL.
 *
 * Cara kerja:
 *   yt-dlp "ytsearch5:<query>" --print "%(title)s|%(duration_string)s|%(uploader)s|%(webpage_url)s" --flat-playlist
 */
@Environment(EnvType.CLIENT)
public class YtSearchManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MusicPlayer/Search");

    public static class SearchResult {
        public final String title;
        public final String duration;   // contoh: "3:45"
        public final String uploader;
        public final String url;

        public SearchResult(String title, String duration, String uploader, String url) {
            this.title    = title;
            this.duration = duration;
            this.uploader = uploader;
            this.url      = url;
        }

        @Override public String toString() {
            return title + " [" + duration + "] - " + uploader;
        }
    }

    /**
     * Jalankan pencarian secara sinkron (panggil dari worker thread).
     * @param query        kata kunci pencarian
     * @param maxResults   jumlah hasil maksimal (1–15)
     * @param ytDlpPath    path ke binary yt-dlp
     * @param onStatus     callback status (opsional)
     * @return list hasil, kosong jika tidak ada / gagal
     */
    public static List<SearchResult> search(
            String query, int maxResults, String ytDlpPath,
            Consumer<String> onStatus) {

        List<SearchResult> results = new ArrayList<>();
        if (query == null || query.isBlank() || ytDlpPath == null) return results;

        maxResults = Math.max(1, Math.min(15, maxResults));

        try {
            if (onStatus != null) onStatus.accept("Searching...");
            LOGGER.info("[MusicPlayer] Searching YouTube: {}", query);

            // yt-dlp ytsearch5:<query> --print title|duration_string|uploader|webpage_url
            // --flat-playlist supaya tidak download video, cukup metadata
            ProcessBuilder pb = new ProcessBuilder(
                    ytDlpPath,
                    "ytsearch" + maxResults + ":" + query,
                    "--flat-playlist",
                    "--no-warnings",
                    "--print", "%(title)s|%(duration_string)s|%(uploader)s|%(webpage_url)s"
            );
            pb.redirectErrorStream(false); // pisah stderr agar tidak kotor output
            Process proc = pb.start();

            // Baca stderr ke log saja
            Thread errThread = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream()))) {
                    String line;
                    while ((line = br.readLine()) != null)
                        LOGGER.debug("[yt-dlp search stderr] {}", line);
                } catch (Exception ignored) {}
            }, "ytdlp-stderr");
            errThread.setDaemon(true);
            errThread.start();

            // Parse stdout — setiap baris = satu video
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    LOGGER.debug("[yt-dlp search] {}", line);
                    String[] parts = line.split("\\|", 4);
                    if (parts.length < 4) continue;

                    String title    = parts[0].trim();
                    String duration = parts[1].trim();
                    String uploader = parts[2].trim();
                    String url      = parts[3].trim();

                    if (title.isEmpty() || url.isEmpty()) continue;
                    if (duration.equals("NA") || duration.isEmpty()) duration = "??:??";

                    results.add(new SearchResult(title, duration, uploader, url));
                }
            }

            proc.waitFor();

            if (onStatus != null) {
                if (results.isEmpty()) onStatus.accept("No results found.");
                else onStatus.accept("Found " + results.size() + " results.");
            }

            LOGGER.info("[MusicPlayer] Search returned {} results", results.size());

        } catch (Exception e) {
            LOGGER.error("[MusicPlayer] Search error", e);
            if (onStatus != null) onStatus.accept("Search failed: " + e.getMessage());
        }

        return results;
    }
}
