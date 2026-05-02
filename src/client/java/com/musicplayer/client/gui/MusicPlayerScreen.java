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

@Environment(EnvType.CLIENT)
public class MusicPlayerScreen extends Screen {

    private static final int BG_COLOR      = 0xD0101018;
    private static final int BORDER_COLOR  = 0xFF5060A0;
    private static final int TITLE_COLOR   = 0xFFFFD700;
    private static final int LABEL_COLOR   = 0xFFCCCCCC;
    private static final int ERROR_COLOR   = 0xFFFF4444;
    private static final int PLAYING_COLOR = 0xFF44FF88;

    // Progress bar colors
    private static final int BAR_BG_COLOR   = 0xFF222233;
    private static final int BAR_FILL_COLOR = 0xFF4488FF;
    private static final int BAR_DONE_COLOR = 0xFF44FF88;

    private boolean tabLocal = true;

    private TextFieldWidget inputField;
    private ButtonWidget    btnPlay, btnPauseResume, btnStop;
    private ButtonWidget    tabLocalBtn, tabYouTubeBtn;
    private VolumeSlider    volumeSlider;
    private ButtonWidget    btnLoop;

    private final AudioManager audio = AudioManager.get();

    // Panel layout — dipakai render() juga
    private int panelX, panelY, panelW, panelH;

    public MusicPlayerScreen() {
        super(Text.translatable("musicplayer.gui.title"));
    }

    @Override
    protected void init() {
        int w = this.width;
        int h = this.height;

        panelW = Math.min(400, w - 40);
        panelH = 240; // sedikit lebih tinggi untuk progress bar
        panelX = (w - panelW) / 2;
        panelY = (h - panelH) / 2;

        int cx = panelX;

        // ── Tab buttons ───────────────────────────────────────────────────────
        int tabW = panelW / 2 - 4;
        tabLocalBtn = ButtonWidget.builder(
                Text.translatable("musicplayer.gui.tab.local"),
                btn -> switchTab(true)
        ).dimensions(cx, panelY + 22, tabW, 20).build();
        addDrawableChild(tabLocalBtn);

        tabYouTubeBtn = ButtonWidget.builder(
                Text.translatable("musicplayer.gui.tab.youtube"),
                btn -> switchTab(false)
        ).dimensions(cx + tabW + 8, panelY + 22, tabW, 20).build();
        addDrawableChild(tabYouTubeBtn);

        // ── Text field ────────────────────────────────────────────────────────
        inputField = new TextFieldWidget(
                this.textRenderer, cx, panelY + 60, panelW, 20, Text.literal(""));
        inputField.setMaxLength(512);
        inputField.setPlaceholder(Text.literal(tabLocal
                ? "/path/to/music.wav"
                : "https://www.youtube.com/watch?v=..."));
        addSelectableChild(inputField);
        addDrawableChild(inputField);

        // ── Playback buttons ──────────────────────────────────────────────────
        int btnW = (panelW - 8) / 3;
        btnPlay = ButtonWidget.builder(
                Text.translatable("musicplayer.gui.btn.play"),
                btn -> handlePlay()
        ).dimensions(cx, panelY + 92, btnW, 20).build();
        addDrawableChild(btnPlay);

        btnPauseResume = ButtonWidget.builder(
                Text.translatable("musicplayer.gui.btn.pause"),
                btn -> handlePauseResume()
        ).dimensions(cx + btnW + 4, panelY + 92, btnW, 20).build();
        addDrawableChild(btnPauseResume);

        btnStop = ButtonWidget.builder(
                Text.translatable("musicplayer.gui.btn.stop"),
                btn -> audio.stop()
        ).dimensions(cx + (btnW + 4) * 2, panelY + 92, btnW, 20).build();
        addDrawableChild(btnStop);

        // ── Volume slider ─────────────────────────────────────────────────────
        volumeSlider = new VolumeSlider(cx, panelY + 124, panelW - 60, 20, audio.getVolume());
        addDrawableChild(volumeSlider);

        // ── Loop button ───────────────────────────────────────────────────────
        btnLoop = ButtonWidget.builder(
                loopLabel(),
                btn -> { audio.setLooping(!audio.isLooping()); btn.setMessage(loopLabel()); }
        ).dimensions(cx, panelY + 152, 100, 20).build();
        addDrawableChild(btnLoop);

        updateTabStyle();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);

        int w = this.width;

        // Panel
        ctx.fill(panelX - 2, panelY - 2, panelX + panelW + 2, panelY + panelH + 2, BORDER_COLOR);
        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, BG_COLOR);

        // Title
        ctx.drawCenteredTextWithShadow(this.textRenderer, "🎵  Music Player", w / 2, panelY + 6, TITLE_COLOR);

        // Field label
        ctx.drawTextWithShadow(this.textRenderer,
                tabLocal ? "File Path (.wav / .aiff):" : "YouTube URL:",
                panelX, panelY + 48, LABEL_COLOR);

        // Volume label
        ctx.drawTextWithShadow(this.textRenderer,
                "Volume: " + Math.round(audio.getVolume() * 100) + "%",
                panelX + panelW - 56, panelY + 128, LABEL_COLOR);

        // ── Status / Progress bar area ────────────────────────────────────────
        int npY = panelY + 180;
        AudioManager.State state = audio.getState();
        int progress = audio.getDownloadProgress();

        if (state == AudioManager.State.LOADING && progress >= 0) {
            // Render progress bar
            renderProgressBar(ctx, panelX, npY, panelW, progress, audio.getStatusText());
        } else {
            // Render "Now Playing"
            ctx.drawTextWithShadow(this.textRenderer, "Now Playing:", panelX, npY, LABEL_COLOR);

            String trackInfo;
            int trackColor;
            switch (state) {
                case PLAYING -> { trackInfo = audio.getTrackName();            trackColor = PLAYING_COLOR; }
                case PAUSED  -> { trackInfo = "[PAUSED] " + audio.getTrackName(); trackColor = 0xFFFFAA00; }
                case LOADING -> { trackInfo = audio.getStatusText();           trackColor = 0xFFAAAAAA; }
                case ERROR   -> { trackInfo = audio.getErrorMsg();             trackColor = ERROR_COLOR; }
                default      -> { trackInfo = "No track loaded";               trackColor = 0xFF888888; }
            }

            String display = textRenderer.getWidth(trackInfo) > panelW - 80
                    ? textRenderer.trimToWidth(trackInfo, panelW - 80) + "..."
                    : trackInfo;
            ctx.drawTextWithShadow(this.textRenderer, display, panelX + 74, npY, trackColor);
        }

        // Update pause/resume label
        btnPauseResume.setMessage(state == AudioManager.State.PAUSED
                ? Text.translatable("musicplayer.gui.btn.resume")
                : Text.translatable("musicplayer.gui.btn.pause"));

        super.render(ctx, mouseX, mouseY, delta);
    }

    /**
     * Render progress bar dengan persentase dan status teks.
     */
    private void renderProgressBar(DrawContext ctx, int x, int y, int w, int pct, String status) {
        int barH    = 10;
        int barFull = w;
        int barFill = (int)(barFull * (pct / 100f));
        int fillColor = pct >= 100 ? BAR_DONE_COLOR : BAR_FILL_COLOR;

        // Background bar
        ctx.fill(x, y, x + barFull, y + barH, BAR_BG_COLOR);
        // Fill
        if (barFill > 0) ctx.fill(x, y, x + barFill, y + barH, fillColor);
        // Border
        ctx.fill(x,             y,          x + barFull, y + 1,       0xFF334466);
        ctx.fill(x,             y + barH-1, x + barFull, y + barH,    0xFF334466);
        ctx.fill(x,             y,          x + 1,       y + barH,    0xFF334466);
        ctx.fill(x + barFull-1, y,          x + barFull, y + barH,    0xFF334466);

        // Pct text di dalam bar
        String pctText = pct + "%";
        int textX = x + (barFull - textRenderer.getWidth(pctText)) / 2;
        ctx.drawTextWithShadow(this.textRenderer, pctText, textX, y + 1, 0xFFFFFFFF);

        // Status text di bawah bar
        if (status != null && !status.isEmpty()) {
            String disp = textRenderer.getWidth(status) > w
                    ? textRenderer.trimToWidth(status, w - 10) + "..."
                    : status;
            ctx.drawTextWithShadow(this.textRenderer, disp, x, y + barH + 4, 0xFFAAAAAA);
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // no-op — dirender manual di render()
    }

    private void switchTab(boolean local) {
        tabLocal = local;
        inputField.setText("");
        inputField.setPlaceholder(Text.literal(local
                ? "/path/to/music.wav"
                : "https://www.youtube.com/watch?v=..."));
        updateTabStyle();
    }

    private void updateTabStyle() {
        tabLocalBtn.active   = !tabLocal;
        tabYouTubeBtn.active = tabLocal;
    }

    private void handlePlay() {
        String input = inputField.getText().trim();
        if (input.isEmpty()) return;
        if (tabLocal) audio.playLocalFile(Paths.get(input));
        else          audio.playYouTube(input);
    }

    private void handlePauseResume() {
        AudioManager.State s = audio.getState();
        if (s == AudioManager.State.PLAYING)      audio.pause();
        else if (s == AudioManager.State.PAUSED)  audio.resume();
    }

    private Text loopLabel() {
        return audio.isLooping() ? Text.literal("✓ Loop ON") : Text.literal("  Loop OFF");
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { this.close(); return true; }
        return super.keyPressed(input);
    }

    private class VolumeSlider extends SliderWidget {
        VolumeSlider(int x, int y, int width, int height, float initialVolume) {
            super(x, y, width, height, Text.literal(""), initialVolume);
        }
        @Override protected void updateMessage() {}
        @Override protected void applyValue() { audio.setVolume((float) this.value); }
    }
}
