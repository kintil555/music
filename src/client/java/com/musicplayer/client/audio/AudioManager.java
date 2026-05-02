package com.musicplayer.client.audio;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@Environment(EnvType.CLIENT)
public class AudioManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MusicPlayer/Audio");

    private static AudioManager INSTANCE;
    public static AudioManager get() {
        if (INSTANCE == null) INSTANCE = new AudioManager();
        return INSTANCE;
    }

    public enum State { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    private volatile State        state            = State.IDLE;
    private volatile String       trackName        = "";
    private volatile String       errorMessage     = "";
    private volatile boolean      looping          = false;
    private volatile float        volume           = 0.8f;
    private volatile int          downloadProgress = -1;
    private volatile String       statusText       = "";
    private volatile AudioQuality quality          = AudioQuality.getDefault();

    private Clip         clip;
    private FloatControl gainControl;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MusicPlayer-Worker");
        t.setDaemon(true);
        return t;
    });

    private Consumer<String> onError;
    private Runnable         onTrackEnd;

    public State        getState()            { return state; }
    public String       getTrackName()        { return trackName; }
    public String       getErrorMsg()         { return errorMessage; }
    public boolean      isLooping()           { return looping; }
    public float        getVolume()           { return volume; }
    public int          getDownloadProgress() { return downloadProgress; }
    public String       getStatusText()       { return statusText; }
    public AudioQuality getQuality()          { return quality; }

    public void setOnError(Consumer<String> cb) { this.onError    = cb; }
    public void setOnTrackEnd(Runnable cb)       { this.onTrackEnd = cb; }

    public void setQuality(AudioQuality q) {
        this.quality = q;
        LOGGER.info("[MusicPlayer] Quality set to {}", q.label);
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    public void playLocalFile(Path path) {
        if (!Files.exists(path)) { fireError("File not found: " + path.toAbsolutePath()); return; }
        state = State.LOADING;
        trackName = path.getFileName().toString();
        downloadProgress = -1;
        statusText = "Loading...";
        worker.submit(() -> loadAndPlay(
                () -> AudioSystem.getAudioInputStream(path.toFile()),
                path.getFileName().toString()
        ));
    }

    public void playYouTube(String ytUrl) {
        if (!ytUrl.contains("youtube.com/") && !ytUrl.contains("youtu.be/")) {
            fireError("Invalid YouTube URL!"); return;
        }
        state = State.LOADING;
        trackName = "Loading from YouTube...";
        downloadProgress = 0;
        statusText = "Preparing...";

        worker.submit(() -> {
            Path tempWav = null;
            try {
                // 1. Cek cache (per URL + kualitas, agar tidak mix cache beda kualitas)
                Path cached = YtDlpManager.getCachedWav(ytUrl + "_q" + quality.ytDlpQuality);
                if (cached != null && Files.exists(cached)) {
                    statusText = "Loading from cache...";
                    downloadProgress = 100;
                    loadAndPlay(() -> AudioSystem.getAudioInputStream(cached.toFile()), ytUrl);
                    return;
                }

                // 2. yt-dlp
                statusText = "Checking yt-dlp...";
                downloadProgress = 5;
                String ytDlpPath = YtDlpManager.getOrDownload(msg -> { statusText = msg; });
                if (ytDlpPath == null) { fireError("yt-dlp not found."); return; }

                // 3. ffmpeg
                statusText = "Checking ffmpeg...";
                downloadProgress = 10;
                String ffmpegPath = FfmpegManager.getOrDownload(msg -> { statusText = msg; });
                if (ffmpegPath == null) { fireError("ffmpeg not found."); return; }

                // 4. Output ke cache
                tempWav = YtDlpManager.buildCachePath(ytUrl + "_q" + quality.ytDlpQuality);
                final Path wavFile = tempWav;
                Files.deleteIfExists(wavFile);

                statusText = "Downloading audio...";
                downloadProgress = 15;

                ProcessBuilder pb = new ProcessBuilder(
                        ytDlpPath,
                        "--no-playlist",
                        "-f", "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio",
                        "-x",
                        "--audio-format", "wav",
                        "--audio-quality", String.valueOf(quality.ytDlpQuality),
                        "--ffmpeg-location", ffmpegPath,
                        "--no-progress",
                        "-o", wavFile.toString(),
                        ytUrl
                );
                pb.redirectErrorStream(true);
                Process proc = pb.start();

                StringBuilder log = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        LOGGER.debug("[yt-dlp] {}", line);
                        log.append(line).append("\n");
                        parseYtDlpProgress(line);
                    }
                }
                proc.waitFor();

                if (proc.exitValue() != 0) {
                    LOGGER.error("[MusicPlayer] yt-dlp failed:\n{}", log);
                    fireError("yt-dlp failed (exit " + proc.exitValue() + "). Check logs.");
                    return;
                }

                Path actualWav = findOutputFile(wavFile);
                if (actualWav == null || !Files.exists(actualWav)) {
                    fireError("Conversion failed: output file not found."); return;
                }

                downloadProgress = 100;
                statusText = "Loading audio...";
                LOGGER.info("[MusicPlayer] WAV ready: {} ({} KB)",
                        actualWav.getFileName(), Files.size(actualWav) / 1024);

                final Path finalWav = actualWav;
                loadAndPlay(() -> AudioSystem.getAudioInputStream(finalWav.toFile()), ytUrl);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.error("[MusicPlayer] YouTube error", e);
                fireError("Failed: " + e.getMessage());
                if (tempWav != null) try { Files.deleteIfExists(tempWav); } catch (Exception ignored) {}
            }
        });
    }

    private void parseYtDlpProgress(String line) {
        if (line.contains("[download]") && line.contains("%")) {
            try {
                String pctStr = line.replaceAll(".*?(\\d+\\.?\\d*)%.*", "$1");
                float pct = Float.parseFloat(pctStr);
                downloadProgress = 15 + (int)(pct * 0.70f);
                statusText = "Downloading... " + (int)pct + "%";
            } catch (Exception ignored) {}
        } else if (line.contains("[ExtractAudio]") || line.contains("[ffmpeg]")) {
            downloadProgress = 88;
            statusText = "Converting...";
        } else if (line.contains("[Metadata]")) {
            downloadProgress = 95;
            statusText = "Finalizing...";
        }
    }

    private Path findOutputFile(Path basePath) {
        if (Files.exists(basePath)) return basePath;
        Path withExt = basePath.resolveSibling(basePath.getFileName() + ".wav");
        if (Files.exists(withExt)) return withExt;
        try {
            String baseName = basePath.getFileName().toString().replace(".wav", "");
            return Files.list(basePath.getParent())
                    .filter(p -> p.getFileName().toString().startsWith(baseName))
                    .filter(p -> p.getFileName().toString().endsWith(".wav"))
                    .findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

    public void pause() {
        if (clip != null && clip.isRunning()) { clip.stop(); state = State.PAUSED; }
    }

    public void resume() {
        if (clip != null && state == State.PAUSED) { clip.start(); state = State.PLAYING; }
    }

    public void stop() {
        closeClip();
        state = State.IDLE;
        trackName = "";
        downloadProgress = -1;
        statusText = "";
    }

    public void setLooping(boolean loop) {
        this.looping = loop;
        if (clip != null && clip.isOpen()) clip.loop(loop ? Clip.LOOP_CONTINUOUSLY : 0);
    }

    public void setVolume(float v) { this.volume = clamp(v, 0f, 1f); applyGain(); }

    public void shutdown() { stop(); worker.shutdownNow(); }

    // ── Internal ──────────────────────────────────────────────────────────────

    @FunctionalInterface interface StreamSupplier { AudioInputStream get() throws Exception; }

    private void loadAndPlay(StreamSupplier supplier, String name) {
        try {
            closeClip();
            AudioInputStream raw = supplier.get();
            AudioFormat fmt = raw.getFormat();
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
                    ? AudioSystem.getAudioInputStream(pcmFmt, raw) : raw;
            clip = AudioSystem.getClip();
            clip.open(pcm);
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                gainControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                applyGain();
            }
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP && state == State.PLAYING) {
                    if (looping) { clip.setFramePosition(0); clip.start(); }
                    else { state = State.IDLE; if (onTrackEnd != null) onTrackEnd.run(); }
                }
            });
            clip.setFramePosition(0);
            if (looping) clip.loop(Clip.LOOP_CONTINUOUSLY); else clip.start();
            trackName = name;
            state = State.PLAYING;
            downloadProgress = -1;
            statusText = "";
            LOGGER.info("[MusicPlayer] Playing: {}", name);
        } catch (UnsupportedAudioFileException e) {
            fireError("Unsupported audio format!");
        } catch (Exception e) {
            LOGGER.error("[MusicPlayer] Playback error", e);
            fireError("Playback error: " + e.getMessage());
        }
    }

    private void closeClip() {
        if (clip != null) { clip.stop(); clip.close(); clip = null; gainControl = null; }
    }

    private void applyGain() {
        if (gainControl == null) return;
        float db = volume <= 0f ? gainControl.getMinimum() : (float)(20.0 * Math.log10(volume));
        gainControl.setValue(clamp(db, gainControl.getMinimum(), gainControl.getMaximum()));
    }

    private void fireError(String msg) {
        errorMessage = msg;
        state = State.ERROR;
        downloadProgress = -1;
        statusText = "";
        LOGGER.warn("[MusicPlayer] {}", msg);
        if (onError != null) onError.accept(msg);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
