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

    // ── Mode: file playback via Clip ──────────────────────────────────────────
    private Clip         clip;
    private FloatControl gainControl;

    // ── Mode: streaming via SourceDataLine ────────────────────────────────────
    private SourceDataLine   streamLine;
    private FloatControl     streamGain;
    private volatile boolean stopStream  = false;
    private volatile boolean pauseStream = false;
    private Process          streamProc;

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

    /**
     * Stream audio YouTube langsung tanpa download ke disk.
     *
     * Arsitektur:
     *  1. yt-dlp --get-url  → dapat direct URL audio CDN
     *  2. ffmpeg -i <url> → decode ke raw PCM S16LE 44100Hz stereo → pipe:1
     *  3. SourceDataLine.write(buf) loop → audio keluar realtime
     *
     * SourceDataLine dipakai (bukan Clip) karena Clip.open() butuh ukuran data yang
     * diketahui di awal. Stream pipe tidak memiliki panjang pasti → "Audio data < 0".
     */
    public void streamYouTube(String ytUrl) {
        if (!ytUrl.contains("youtube.com/") && !ytUrl.contains("youtu.be/")) {
            fireError("Invalid YouTube URL!"); return;
        }

        // Stop apapun yang sedang berjalan
        stopStreamInternal();
        closeClip();

        state = State.LOADING;
        trackName = "Streaming...";
        downloadProgress = 0;
        statusText = "Preparing stream...";
        stopStream  = false;
        pauseStream = false;

        worker.submit(() -> {
            try {
                // ── 1. Cek yt-dlp ─────────────────────────────────────────────
                statusText = "Checking yt-dlp...";
                downloadProgress = 5;
                String ytDlpPath = YtDlpManager.getOrDownload(msg -> statusText = msg);
                if (ytDlpPath == null) { fireError("yt-dlp not found."); return; }

                // ── 2. Cek ffmpeg ─────────────────────────────────────────────
                statusText = "Checking ffmpeg...";
                downloadProgress = 10;
                String ffmpegPath = FfmpegManager.getOrDownload(msg -> statusText = msg);
                if (ffmpegPath == null) { fireError("ffmpeg not found."); return; }

                // ── 3. Resolve direct audio URL via yt-dlp --get-url ──────────
                statusText = "Resolving audio URL...";
                downloadProgress = 15;
                ProcessBuilder urlPb = new ProcessBuilder(
                        ytDlpPath,
                        "--no-playlist",
                        "-f", "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio",
                        "--get-url",
                        ytUrl
                );
                urlPb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process urlProc = urlPb.start();
                String directUrl;
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(urlProc.getInputStream()))) {
                    directUrl = br.readLine();
                }
                urlProc.waitFor();

                if (directUrl == null || directUrl.isBlank()) {
                    fireError("Cannot resolve stream URL. Check yt-dlp."); return;
                }

                // ── 4. ffmpeg: decode URL → raw PCM S16LE → stdout ────────────
                statusText = "Buffering...";
                downloadProgress = 30;
                ProcessBuilder ffPb = new ProcessBuilder(
                        ffmpegPath,
                        "-reconnect", "1",
                        "-reconnect_streamed", "1",
                        "-reconnect_delay_max", "5",
                        "-i", directUrl,
                        "-vn",
                        "-ar", "44100",
                        "-ac", "2",
                        "-f", "s16le",   // raw PCM — tidak ada WAV header → panjang tidak diketahui, aman untuk stream
                        "pipe:1"
                );
                ffPb.redirectError(ProcessBuilder.Redirect.DISCARD);
                streamProc = ffPb.start();
                final Process proc = streamProc;

                // ── 5. Buka SourceDataLine ────────────────────────────────────
                AudioFormat pcmFmt = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        44100f, 16, 2, 4, 44100f, false
                );
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, pcmFmt);
                closeStreamLine();
                streamLine = (SourceDataLine) AudioSystem.getLine(info);
                streamLine.open(pcmFmt, 32768); // 32KB buffer

                if (streamLine.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    streamGain = (FloatControl) streamLine.getControl(FloatControl.Type.MASTER_GAIN);
                    applyStreamGain();
                }

                streamLine.start();
                trackName = ytUrl;
                state = State.PLAYING;
                downloadProgress = -1;
                statusText = "";
                LOGGER.info("[MusicPlayer] Stream started: {}", ytUrl);

                // ── 6. Write-loop di daemon thread terpisah ───────────────────
                // Worker thread SELESAI di sini supaya tidak diblokir selama lagu.
                final SourceDataLine finalLine = streamLine;
                Thread writeThread = new Thread(() -> {
                    try {
                        byte[] buf = new byte[8192];
                        InputStream ffOut = proc.getInputStream();
                        int read;
                        while (!stopStream && (read = ffOut.read(buf)) != -1) {
                            while (pauseStream && !stopStream) {
                                try { Thread.sleep(30); } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt(); return;
                                }
                            }
                            if (stopStream) break;
                            finalLine.write(buf, 0, read);
                        }
                        finalLine.drain();
                        finalLine.stop();
                        if (!stopStream) {
                            state = State.IDLE;
                            LOGGER.info("[MusicPlayer] Stream finished.");
                            if (onTrackEnd != null) onTrackEnd.run();
                        }
                    } catch (Exception e) {
                        if (!stopStream) {
                            LOGGER.error("[MusicPlayer] Stream write error", e);
                            fireError("Stream error: " + e.getMessage());
                        }
                    } finally {
                        proc.destroyForcibly();
                    }
                }, "MusicPlayer-StreamWrite");
                writeThread.setDaemon(true);
                writeThread.setPriority(Thread.NORM_PRIORITY - 1);
                writeThread.start();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.error("[MusicPlayer] Stream setup error", e);
                if (!stopStream) fireError("Stream failed: " + e.getMessage());
            }
        });
    }

    // ── Helper: stop & tutup SourceDataLine ───────────────────────────────────
    private void stopStreamInternal() {
        stopStream = true;
        if (streamProc != null) { streamProc.destroyForcibly(); streamProc = null; }
        closeStreamLine();
    }

    private void closeStreamLine() {
        if (streamLine != null) {
            try { streamLine.stop(); streamLine.close(); } catch (Exception ignored) {}
            streamLine = null;
            streamGain = null;
        }
    }

    private void applyStreamGain() {
        if (streamGain == null) return;
        float db = volume <= 0f ? streamGain.getMinimum() : (float)(20.0 * Math.log10(volume));
        streamGain.setValue(clamp(db, streamGain.getMinimum(), streamGain.getMaximum()));
    }

    public void pause() {
        if (streamLine != null && state == State.PLAYING) {
            pauseStream = true;
            streamLine.stop();
            state = State.PAUSED;
        } else if (clip != null && clip.isRunning()) {
            clip.stop();
            state = State.PAUSED;
        }
    }

    public void resume() {
        if (streamLine != null && state == State.PAUSED) {
            pauseStream = false;
            streamLine.start();
            state = State.PLAYING;
        } else if (clip != null && state == State.PAUSED) {
            clip.start();
            state = State.PLAYING;
        }
    }

    public void stop() {
        stopStream = true;
        pauseStream = false;
        stopStreamInternal();
        closeClip();
        state = State.IDLE;
        trackName = "";
        downloadProgress = -1;
        statusText = "";
    }

    public void setLooping(boolean loop) {
        this.looping = loop;
        if (clip != null && clip.isOpen()) clip.loop(loop ? Clip.LOOP_CONTINUOUSLY : 0);
        // SourceDataLine streaming: loop ditangani setelah write-loop selesai (TODO masa depan)
    }

    public void setVolume(float v) {
        this.volume = clamp(v, 0f, 1f);
        applyGain();
        applyStreamGain();
    }

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
