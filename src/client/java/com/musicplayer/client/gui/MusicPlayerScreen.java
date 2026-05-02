package com.musicplayer.client.gui;

import com.musicplayer.client.audio.AudioManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.nio.file.Paths;

/**
 * GUI utama Music Player Mod.
 * Dibuka dengan tombol F9 (configurable di Controls > Music Player).
 *
 * Layout:
 * ┌─────────────────────────────────────────────────────────┐
 * │                  🎵  Music Player                       │
 * │  [ Local File ]  [ YouTube ]        ← tabs             │
 * ├─────────────────────────────────────────────────────────┤
 * │  Path/URL: [__________________________________]         │
 * │                                                         │
 * │   [ ▶ Play ]  [ ⏸ Pause/Resume ]  [ ⏹ Stop ]          │
 * │                                                         │
 * │   [=====════════════════] Volume: 80%                  │
 * │   [✓] Loop                                              │
 * ├─────────────────────────────────────────────────────────┤
 * │  Now Playing: track_name_here                           │
 * └─────────────────────────────────────────────────────────┘
 */
@Environment(EnvType.CLIENT)
public class MusicPlayerScreen extends Screen {

    private static final int BG_COLOR      = 0xD0101018;
    private static final int BORDER_COLOR  = 0xFF5060A0;
    private static final int TITLE_COLOR   = 0xFFFFD700;
    private static final int LABEL_COLOR   = 0xFFCCCCCC;
    private static final int ERROR_COLOR   = 0xFFFF4444;
    private static final int PLAYING_COLOR = 0xFF44FF88;

    // Tab
    private boolean tabLocal = true;  // true = Local, false = YouTube

    // Widget references
    private TextFieldWidget inputField;
    private ButtonWidget btnPlay, btnPauseResume, btnStop;
    private ButtonWidget tabLocalBtn, tabYouTubeBtn;
    private VolumeSlider volumeSlider;
    private ButtonWidget btnLoop;

    private final AudioManager audio = AudioManager.get();

    public MusicPlayerScreen() {
        super(Text.translatable("musicplayer.gui.title"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        int w = this.width;
        int h = this.height;

        // Panel dimensions
        int panelW = Math.min(400, w - 40);
        int panelH = 220;
        int panelX = (w - panelW) / 2;
        int panelY = (h - panelH) / 2;

        int cx = panelX; // left edge of panel

        // ── Tab buttons ───────────────────────────────────────────────────────
        int tabY = panelY + 22;
        int tabW = panelW / 2 - 4;

        tabLocalBtn = ButtonWidget.builder(
                Text.translatable("musicplayer.gui.tab.local"),
                btn -> switchTab(true)
        ).dimensions(cx, tabY, tabW, 20).build();
        addDrawableChild(tabLocalBtn);

        tabYouTubeBtn = ButtonWidget.builder(
                Text.translatable("musicplayer.gui.tab.youtube"),
                btn -> switchTab(false)
        ).dimensions(cx + tabW + 8, tabY, tabW, 20).build();
        addDrawableChild(tabYouTubeBtn);

        // ── Text field ────────────────────────────────────────────────────────
        int fieldY = panelY + 60;
        inputField = new TextFieldWidget(
                this.textRenderer,
                cx, fieldY, panelW, 20,
                Text.literal("")
        );
        inputField.setMaxLength(512);
        inputField.setPlaceholder(Text.literal(tabLocal
                ? "/path/to/music.wav"
                : "https://www.youtube.com/watch?v=..."));
        addSelectableChild(inputField);
        addDrawableChild(inputField);

        // ── Playback buttons ──────────────────────────────────────────────────
        int btnY = panelY + 92;
        int btnW = (panelW - 8) / 3;

        btnPlay = ButtonWidget.builder(
                Text.translatable("musicplayer.gui.btn.play"),
                btn -> handlePlay()
        ).dimensions(cx, btnY, btnW, 20).build();
        addDrawableChild(btnPlay);

        btnPauseResume = ButtonWidget.builder(
                Text.translatable("musicplayer.gui.btn.pause"),
                btn -> handlePauseResume()
        ).dimensions(cx + btnW + 4, btnY, btnW, 20).build();
        addDrawableChild(btnPauseResume);

        btnStop = ButtonWidget.builder(
                Text.translatable("musicplayer.gui.btn.stop"),
                btn -> audio.stop()
        ).dimensions(cx + (btnW + 4) * 2, btnY, btnW, 20).build();
        addDrawableChild(btnStop);

        // ── Volume slider ─────────────────────────────────────────────────────
        int volY = panelY + 124;
        volumeSlider = new VolumeSlider(cx, volY, panelW - 60, 20, audio.getVolume());
        addDrawableChild(volumeSlider);

        // ── Loop button ───────────────────────────────────────────────────────
        int loopY = panelY + 152;
        btnLoop = ButtonWidget.builder(
                loopLabel(),
                btn -> {
                    audio.setLooping(!audio.isLooping());
                    btn.setMessage(loopLabel());
                }
        ).dimensions(cx, loopY, 100, 20).build();
        addDrawableChild(btnLoop);

        updateTabStyle();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Render
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Render background blur SEKALI di awal
        super.renderBackground(ctx, mouseX, mouseY, delta);

        int w = this.width;
        int h = this.height;
        int panelW = Math.min(400, w - 40);
        int panelH = 220;
        int panelX = (w - panelW) / 2;
        int panelY = (h - panelH) / 2;

        // Panel background
        ctx.fill(panelX - 2, panelY - 2, panelX + panelW + 2, panelY + panelH + 2, BORDER_COLOR);
        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, BG_COLOR);

        // Title
        String title = "🎵  Music Player";
        ctx.drawCenteredTextWithShadow(this.textRenderer, title, w / 2, panelY + 6, TITLE_COLOR);

        // Label atas field
        String fieldLabel = tabLocal
                ? "File Path (.wav / .aiff):"
                : "YouTube URL:";
        ctx.drawTextWithShadow(this.textRenderer, fieldLabel, panelX, panelY + 48, LABEL_COLOR);

        // Volume label (kanan slider)
        int volPct = Math.round(audio.getVolume() * 100);
        ctx.drawTextWithShadow(this.textRenderer,
                "Volume: " + volPct + "%",
                panelX + panelW - 56, panelY + 128, LABEL_COLOR);

        // Now Playing section
        int npY = panelY + 178;
        ctx.drawTextWithShadow(this.textRenderer, "Now Playing:", panelX, npY, LABEL_COLOR);

        AudioManager.State state = audio.getState();
        String trackInfo;
        int trackColor;

        switch (state) {
            case PLAYING -> {
                trackInfo = audio.getTrackName();
                trackColor = PLAYING_COLOR;
            }
            case PAUSED -> {
                trackInfo = "[PAUSED] " + audio.getTrackName();
                trackColor = 0xFFFFAA00;
            }
            case LOADING -> {
                trackInfo = "Loading...";
                trackColor = 0xFFAAAAAA;
            }
            case ERROR -> {
                trackInfo = audio.getErrorMsg();
                trackColor = ERROR_COLOR;
            }
            default -> {
                trackInfo = "No track loaded";
                trackColor = 0xFF888888;
            }
        }

        // Truncate agar tidak keluar panel
        String display = textRenderer.getWidth(trackInfo) > panelW - 80
                ? textRenderer.trimToWidth(trackInfo, panelW - 80) + "..."
                : trackInfo;
        ctx.drawTextWithShadow(this.textRenderer, display, panelX + 70, npY, trackColor);

        // Update pause/resume button label
        btnPauseResume.setMessage(state == AudioManager.State.PAUSED
                ? Text.translatable("musicplayer.gui.btn.resume")
                : Text.translatable("musicplayer.gui.btn.pause"));

        // Render semua widget (panggil super tapi renderBackground sudah di-override jadi no-op)
        super.render(ctx, mouseX, mouseY, delta);
    }

    /**
     * Override renderBackground menjadi no-op agar super.render() tidak memanggil blur
     * kedua kalinya. Background sudah di-render manual di awal method render().
     */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Sengaja kosong — background dirender sekali di render() sebelum konten panel
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void switchTab(boolean local) {
        tabLocal = local;
        inputField.setText("");
        inputField.setPlaceholder(Text.literal(local
                ? "/path/to/music.wav"
                : "https://www.youtube.com/watch?v=..."));
        updateTabStyle();
    }

    private void updateTabStyle() {
        // Highlight active tab via alpha (sedikit bedakan warna)
        tabLocalBtn.active    = !tabLocal;    // disable = active tab (tidak bisa diklik lagi)
        tabYouTubeBtn.active  = tabLocal;
    }

    private void handlePlay() {
        String input = inputField.getText().trim();
        if (input.isEmpty()) return;

        if (tabLocal) {
            audio.playLocalFile(Paths.get(input));
        } else {
            audio.playYouTube(input);
        }
    }

    private void handlePauseResume() {
        AudioManager.State state = audio.getState();
        if (state == AudioManager.State.PLAYING) {
            audio.pause();
        } else if (state == AudioManager.State.PAUSED) {
            audio.resume();
        }
    }

    private Text loopLabel() {
        return audio.isLooping()
                ? Text.literal("✓ Loop ON")
                : Text.literal("  Loop OFF");
    }

    @Override
    public boolean shouldPause() {
        return false; // Jangan pause game saat GUI terbuka
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        // Jangan tutup GUI saat tekan Escape — tetap bisa mainkan musik di belakang
        if (input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            this.close();
            return true;
        }
        return super.keyPressed(input);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner: Volume Slider
    // ─────────────────────────────────────────────────────────────────────────

    private class VolumeSlider extends SliderWidget {

        VolumeSlider(int x, int y, int width, int height, float initialVolume) {
            super(x, y, width, height, Text.literal(""), initialVolume);
        }

        @Override
        protected void updateMessage() {
            // Label slider kosong, volume label di-render manual di atas
        }

        @Override
        protected void applyValue() {
            audio.setVolume((float) this.value);
        }
    }
}
