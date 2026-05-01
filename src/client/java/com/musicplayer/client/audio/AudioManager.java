package com.musicplayer.client.audio;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Singleton yang mengelola semua playback audio untuk Music Player mod.
 *
 * Fitur:
 *  - Memutar file lokal WAV / AIFF via javax.sound (built-in JDK, zero deps)
 *  - Memutar audio YouTube dengan bantuan yt-dlp (harus ada di PATH)
 *  - Loop, pause/resume, dan kontrol volume (dB ke linear)
 *
 * Thread safety:
 *  - Semua operasi I/O dan decoding dijalankan di thread daemon tersendiri.
 *  - Callback onError / onStateChanged dipanggil dari thread tersebut;
 *    GUI harus marshal ke Render thread jika perlu.
 */
@Environment(EnvType.CLIENT)
public class AudioManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MusicPlayer/Audio");

    // ── Singleton ────────────────────────────────────────────────────────────
    private static AudioManager INSTANCE;
    public static AudioManager get() {
        if (INSTANCE == null) INSTANCE = new AudioManager();
        return INSTANCE;
    }

    // ── State ────────────────────────────────────────────────────────────────
    public enum State { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    private volatile State state = State.IDLE;
    private volatile String trackName = "";
    private volatile String errorMessage = "";
    private volatile boolean looping = false;
    private volatile float volume = 0.8f;   // 0.0 – 1.0 linear

    // ── Audio objects ─────────────────────────────────────────────────────────
    private Clip clip;
    private FloatControl gainControl;

    // ── Worker thread ─────────────────────────────────────────────────────────
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MusicPlayer-Worker");
        t.setDaemon(true);
        return t;
    });

    // ── Callbacks (optional) ──────────────────────────────────────────────────
    private Consumer<String> onError;
    private Runnable onTrackEnd;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public State getState()        { return state; }
    public String getTrackName()   { return trackName; }
    public String getErrorMsg()    { return errorMessage; }
    public boolean isLooping()     { return looping; }
    public float getVolume()       { return volume; }

    public void setOnError(Consumer<String> cb)   { this.onError    = cb; }
    public void setOnTrackEnd(Runnable cb)         { this.onTrackEnd = cb; }

    // ── Playback ──────────────────────────────────────────────────────────────

    /** Memutar file lokal (WAV atau AIFF). */
    public void playLocalFile(Path path) {
        if (!Files.exists(path)) {
            fireError("File not found: " + path.toAbsolutePath());
            return;
        }
        String name = path.getFileName().toString();
        state = State.LOADING;
        trackName = name;
        worker.submit(() -> loadAndPlay(() -> AudioSystem.getAudioInputStream(path.toFile()), name));
    }

    /**
     * Memutar audio dari YouTube.
     * Membutuhkan yt-dlp yang sudah ada di PATH sistem.
     * yt-dlp hanya dipakai untuk mendapat direct audio URL — tidak download file.
     */
    public void playYouTube(String ytUrl) {
        if (!ytUrl.contains("youtube.com/") && !ytUrl.contains("youtu.be/")) {
            fireError("Invalid YouTube URL!");
            return;
        }
        state = State.LOADING;
        trackName = "Loading from YouTube...";
        worker.submit(() -> {
            try {
                // Cek apakah yt-dlp tersedia
                Process check = new ProcessBuilder("yt-dlp", "--version")
                        .redirectErrorStream(true).start();
                check.waitFor();
                if (check.exitValue() != 0) {
                    fireError("yt-dlp not found on PATH. Install yt-dlp first!");
                    return;
                }

                // Ambil direct audio stream URL (tanpa download)
                Process proc = new ProcessBuilder(
                        "yt-dlp",
                        "--no-playlist",
                        "--get-url",
                        "-f", "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio/best",
                        ytUrl
                ).redirectErrorStream(true).start();

                String directUrl;
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    directUrl = br.lines()
                            .filter(l -> l.startsWith("http"))
                            .findFirst()
                            .orElse(null);
                }
                proc.waitFor();

                if (directUrl == null || directUrl.isBlank()) {
                    fireError("Failed to resolve audio stream from YouTube.");
                    return;
                }

                final String url = directUrl.trim();
                LOGGER.info("[MusicPlayer] Resolved URL: {}", url.substring(0, Math.min(80, url.length())));

                // Stream langsung dari URL
                loadAndPlay(() -> {
                    HttpClient client = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.ALWAYS)
                            .build();
                    HttpResponse<InputStream> resp = client.send(
                            HttpRequest.newBuilder(new URI(url))
                                    .header("User-Agent", "Mozilla/5.0")
                                    .build(),
                            HttpResponse.BodyHandlers.ofInputStream()
                    );
                    return AudioSystem.getAudioInputStream(
                            new BufferedInputStream(resp.body()));
                }, ytUrl);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.error("[MusicPlayer] YouTube error", e);
                fireError("Failed to fetch audio: " + e.getMessage());
            }
        });
    }

    public void pause() {
        if (clip != null && clip.isRunning()) {
            clip.stop();
            state = State.PAUSED;
        }
    }

    public void resume() {
        if (clip != null && state == State.PAUSED) {
            clip.start();
            state = State.PLAYING;
        }
    }

    public void stop() {
        closeClip();
        state = State.IDLE;
        trackName = "";
    }

    public void setLooping(boolean loop) {
        this.looping = loop;
        if (clip != null && clip.isOpen()) {
            clip.loop(loop ? Clip.LOOP_CONTINUOUSLY : 0);
        }
    }

    /** @param v antara 0.0 (mute) dan 1.0 (maksimum) */
    public void setVolume(float v) {
        this.volume = clamp(v, 0f, 1f);
        applyGain();
    }

    public void shutdown() {
        stop();
        worker.shutdownNow();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    interface StreamSupplier {
        AudioInputStream get() throws Exception;
    }

    private void loadAndPlay(StreamSupplier supplier, String name) {
        try {
            closeClip();

            AudioInputStream raw = supplier.get();
            AudioFormat fmt = raw.getFormat();

            // Konversi ke PCM_SIGNED 16-bit yang didukung semua sound card
            AudioFormat pcmFmt = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    fmt.getSampleRate() > 0 ? fmt.getSampleRate() : 44100f,
                    16,
                    fmt.getChannels() > 0 ? fmt.getChannels() : 2,
                    fmt.getChannels() > 0 ? fmt.getChannels() * 2 : 4,
                    fmt.getSampleRate() > 0 ? fmt.getSampleRate() : 44100f,
                    false
            );

            AudioInputStream pcm = AudioSystem.isConversionSupported(pcmFmt, fmt)
                    ? AudioSystem.getAudioInputStream(pcmFmt, raw)
                    : raw;

            clip = AudioSystem.getClip();
            clip.open(pcm);

            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                gainControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                applyGain();
            }

            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP && state == State.PLAYING) {
                    if (looping) {
                        clip.setFramePosition(0);
                        clip.start();
                    } else {
                        state = State.IDLE;
                        if (onTrackEnd != null) onTrackEnd.run();
                    }
                }
            });

            clip.setFramePosition(0);
            if (looping) clip.loop(Clip.LOOP_CONTINUOUSLY);
            else clip.start();

            trackName = name;
            state = State.PLAYING;
            LOGGER.info("[MusicPlayer] Playing: {}", name);

        } catch (UnsupportedAudioFileException e) {
            LOGGER.error("[MusicPlayer] Format tidak didukung", e);
            fireError("Unsupported audio format! Use WAV or AIFF for local files.");
        } catch (Exception e) {
            LOGGER.error("[MusicPlayer] Playback error", e);
            fireError("Playback error: " + e.getMessage());
        }
    }

    private void closeClip() {
        if (clip != null) {
            clip.stop();
            clip.close();
            clip = null;
            gainControl = null;
        }
    }

    /** Konversi volume linear (0–1) ke desibel lalu terapkan ke gainControl. */
    private void applyGain() {
        if (gainControl == null) return;
        float db = volume <= 0f
                ? gainControl.getMinimum()
                : (float) (20.0 * Math.log10(volume));
        gainControl.setValue(clamp(db, gainControl.getMinimum(), gainControl.getMaximum()));
    }

    private void fireError(String msg) {
        errorMessage = msg;
        state = State.ERROR;
        LOGGER.warn("[MusicPlayer] {}", msg);
        if (onError != null) onError.accept(msg);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
