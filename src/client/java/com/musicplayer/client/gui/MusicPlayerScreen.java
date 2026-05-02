package com.musicplayer.client.gui;

import com.musicplayer.client.audio.AudioManager;
import com.musicplayer.client.audio.AudioQuality;
import com.musicplayer.client.audio.YtDlpManager;
import com.musicplayer.client.audio.YtSearchManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.Click;
import net.minecraft.text.Text;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GUI Music Player — tab: Local | YouTube | Search
 *
 * Layout panel (400 x 300):
 * ┌──────────────────────────────────────────────┐
 * │           🎵  Music Player                   │
 * │  [Local]  [YouTube]  [Search]    [Quality ▼] │
 * ├──────────────────────────────────────────────┤
 * │  (isi tab)                                   │
 * ├──────────────────────────────────────────────┤
 * │  [▶ Play] [⏸ Pause/Resume] [⏹ Stop]          │
 * │  [====Volume====]  Vol: 80%                  │
 * │  [✓ Loop]                                    │
 * │  Now Playing / Progress bar                  │
 * └──────────────────────────────────────────────┘
 */
@Environment(EnvType.CLIENT)
public class MusicPlayerScreen extends Screen {

    // ── Colors ────────────────────────────────────────────────────────────────
    private static final int BG_COLOR        = 0xD0101018;
    private static final int BORDER_COLOR    = 0xFF5060A0;
    private static final int TITLE_COLOR     = 0xFFFFD700;
    private static final int LABEL_COLOR     = 0xFFCCCCCC;
    private static final int ERROR_COLOR     = 0xFFFF4444;
    private static final int PLAYING_COLOR   = 0xFF44FF88;
    private static final int BAR_BG_COLOR    = 0xFF222233;
    private static final int BAR_FILL_COLOR  = 0xFF4488FF;
    private static final int BAR_DONE_COLOR  = 0xFF44FF88;
    private static final int RESULT_BG       = 0xFF181825;
    private static final int RESULT_HOVER    = 0xFF252540;
    private static final int RESULT_SELECTED = 0xFF1E3050;
    private static final int RESULT_BORDER   = 0xFF334466;
    private static final int DURATION_COLOR  = 0xFF88AAFF;
    private static final int UPLOADER_COLOR  = 0xFF888888;

    // ── Tab ───────────────────────────────────────────────────────────────────
    private enum Tab { LOCAL, YOUTUBE, SEARCH }
    private Tab activeTab = Tab.YOUTUBE;

    // ── Search state ──────────────────────────────────────────────────────────
    private final List<YtSearchManager.SearchResult> searchResults = new ArrayList<>();
    private volatile boolean     searching      = false;
    private volatile String      searchStatus   = "";
    private int                  selectedResult = -1;
    private int                  scrollOffset   = 0;
    private static final int     RESULTS_VISIBLE = 5;
    private static final int     RESULT_H        = 28;

    // ── Quality dropdown ──────────────────────────────────────────────────────
    private boolean qualityDropdownOpen = false;
    private static final AudioQuality[] QUALITIES = AudioQuality.values();

    // ── Widgets ───────────────────────────────────────────────────────────────
    private TextFieldWidget inputField;    // Local / YouTube
    private TextFieldWidget searchField;  // Search tab
    private ButtonWidget    btnPlay, btnPauseResume, btnStop;
    private ButtonWidget    tabLocalBtn, tabYouTubeBtn, tabSearchBtn;
    private ButtonWidget    btnQuality;
    private VolumeSlider    volumeSlider;
    private ButtonWidget    btnLoop;
    private ButtonWidget    btnSearch;

    private final AudioManager audio  = AudioManager.get();
    private final ExecutorService searchWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MusicPlayer-Search");
        t.setDaemon(true);
        return t;
    });

    // ── Layout ────────────────────────────────────────────────────────────────
    private int panelX, panelY, panelW, panelH;

    public MusicPlayerScreen() {
        super(Text.literal("Music Player"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        panelW = Math.min(420, this.width  - 40);
        panelH = 310;
        panelX = (this.width  - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int cx = panelX;

        // ── Tabs (3 tombol + quality di kanan) ───────────────────────────────
        int tabY  = panelY + 22;
        int tabW  = 80;

        tabLocalBtn = ButtonWidget.builder(Text.literal("Local"),
                btn -> switchTab(Tab.LOCAL)
        ).dimensions(cx, tabY, tabW, 18).build();
        addDrawableChild(tabLocalBtn);

        tabYouTubeBtn = ButtonWidget.builder(Text.literal("YouTube"),
                btn -> switchTab(Tab.YOUTUBE)
        ).dimensions(cx + tabW + 4, tabY, tabW, 18).build();
        addDrawableChild(tabYouTubeBtn);

        tabSearchBtn = ButtonWidget.builder(Text.literal("🔍 Search"),
                btn -> switchTab(Tab.SEARCH)
        ).dimensions(cx + (tabW + 4) * 2, tabY, tabW, 18).build();
        addDrawableChild(tabSearchBtn);

        // Quality button — pojok kanan atas
        btnQuality = ButtonWidget.builder(
                Text.literal("⚙ " + audio.getQuality().label),
                btn -> toggleQualityDropdown()
        ).dimensions(cx + panelW - 120, tabY, 118, 18).build();
        addDrawableChild(btnQuality);

        // ── Input field (Local & YouTube) ─────────────────────────────────────
        inputField = new TextFieldWidget(
                this.textRenderer, cx, panelY + 56, panelW, 20, Text.literal(""));
        inputField.setMaxLength(512);
        inputField.setPlaceholder(Text.literal(getInputPlaceholder()));
        addSelectableChild(inputField);
        addDrawableChild(inputField);

        // ── Search field + tombol ─────────────────────────────────────────────
        int sfW = panelW - 64;
        searchField = new TextFieldWidget(
                this.textRenderer, cx, panelY + 56, sfW, 20, Text.literal(""));
        searchField.setMaxLength(200);
        searchField.setPlaceholder(Text.literal("Cari judul lagu..."));
        addSelectableChild(searchField);
        addDrawableChild(searchField);

        btnSearch = ButtonWidget.builder(Text.literal("Search"),
                btn -> doSearch()
        ).dimensions(cx + sfW + 4, panelY + 56, 58, 20).build();
        addDrawableChild(btnSearch);

        // ── Playback buttons ──────────────────────────────────────────────────
        int btnY = panelY + 184;
        int btnW = (panelW - 8) / 3;

        btnPlay = ButtonWidget.builder(Text.literal("▶ Play"),
                btn -> handlePlay()
        ).dimensions(cx, btnY, btnW, 20).build();
        addDrawableChild(btnPlay);

        btnPauseResume = ButtonWidget.builder(Text.literal("⏸ Pause"),
                btn -> handlePauseResume()
        ).dimensions(cx + btnW + 4, btnY, btnW, 20).build();
        addDrawableChild(btnPauseResume);

        btnStop = ButtonWidget.builder(Text.literal("⏹ Stop"),
                btn -> audio.stop()
        ).dimensions(cx + (btnW + 4) * 2, btnY, btnW, 20).build();
        addDrawableChild(btnStop);

        // ── Volume ────────────────────────────────────────────────────────────
        volumeSlider = new VolumeSlider(cx, panelY + 214, panelW - 68, 20, audio.getVolume());
        addDrawableChild(volumeSlider);

        // ── Loop ─────────────────────────────────────────────────────────────
        btnLoop = ButtonWidget.builder(loopLabel(),
                btn -> { audio.setLooping(!audio.isLooping()); btn.setMessage(loopLabel()); }
        ).dimensions(cx, panelY + 242, 100, 18).build();
        addDrawableChild(btnLoop);

        refreshTabVisibility();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Render
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx);

        // Panel background
        ctx.fill(panelX - 2, panelY - 2, panelX + panelW + 2, panelY + panelH + 2, BORDER_COLOR);
        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, BG_COLOR);

        // Title
        ctx.drawCenteredTextWithShadow(textRenderer, "🎵  Music Player",
                this.width / 2, panelY + 6, TITLE_COLOR);

        // Separator bawah tab
        ctx.fill(panelX, panelY + 44, panelX + panelW, panelY + 45, BORDER_COLOR);

        // ── Render isi tab ────────────────────────────────────────────────────
        switch (activeTab) {
            case LOCAL   -> renderLocalTab(ctx);
            case YOUTUBE -> renderYouTubeTab(ctx);
            case SEARCH  -> renderSearchTab(ctx, mouseX, mouseY);
        }

        // ── Volume label ──────────────────────────────────────────────────────
        ctx.drawTextWithShadow(textRenderer,
                "Vol: " + Math.round(audio.getVolume() * 100) + "%",
                panelX + panelW - 64, panelY + 218, LABEL_COLOR);

        // ── Separator ─────────────────────────────────────────────────────────
        ctx.fill(panelX, panelY + 178, panelX + panelW, panelY + 179, BORDER_COLOR);

        // ── Now Playing / Progress ────────────────────────────────────────────
        renderNowPlaying(ctx);

        // Update tombol
        AudioManager.State st = audio.getState();
        btnPauseResume.setMessage(st == AudioManager.State.PAUSED
                ? Text.literal("▶ Resume") : Text.literal("⏸ Pause"));

        // Widgets
        super.render(ctx, mouseX, mouseY, delta);

        // Quality dropdown — render di atas widget lain
        if (qualityDropdownOpen) renderQualityDropdown(ctx, mouseX, mouseY);
    }

    private void renderLocalTab(DrawContext ctx) {
        ctx.drawTextWithShadow(textRenderer, "File Path (.wav / .aiff):",
                panelX, panelY + 46, LABEL_COLOR);
    }

    private void renderYouTubeTab(DrawContext ctx) {
        ctx.drawTextWithShadow(textRenderer, "YouTube URL:",
                panelX, panelY + 46, LABEL_COLOR);
    }

    private void renderSearchTab(DrawContext ctx, int mx, int my) {
        ctx.drawTextWithShadow(textRenderer, "Cari lagu di YouTube:",
                panelX, panelY + 46, LABEL_COLOR);

        int listY = panelY + 84;
        int listH = RESULTS_VISIBLE * RESULT_H;

        if (searching) {
            ctx.drawCenteredTextWithShadow(textRenderer, "Searching...",
                    panelX + panelW / 2, listY + listH / 2 - 4, 0xFFAAAAAA);
            return;
        }

        if (!searchStatus.isEmpty() && searchResults.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, searchStatus,
                    panelX + panelW / 2, listY + listH / 2 - 4, 0xFF888888);
            return;
        }

        // Hasil
        int visibleEnd = Math.min(scrollOffset + RESULTS_VISIBLE, searchResults.size());
        for (int i = scrollOffset; i < visibleEnd; i++) {
            int itemY = listY + (i - scrollOffset) * RESULT_H;
            YtSearchManager.SearchResult r = searchResults.get(i);

            boolean hovered  = mx >= panelX && mx <= panelX + panelW - 14
                    && my >= itemY && my < itemY + RESULT_H;
            boolean selected = i == selectedResult;

            // Background item
            int bg = selected ? RESULT_SELECTED : (hovered ? RESULT_HOVER : RESULT_BG);
            ctx.fill(panelX, itemY, panelX + panelW - 14, itemY + RESULT_H, bg);
            ctx.fill(panelX, itemY + RESULT_H - 1, panelX + panelW - 14, itemY + RESULT_H, RESULT_BORDER);

            // Judul — max lebar
            int titleMaxW = panelW - 14 - 60;
            String title = textRenderer.getWidth(r.title) > titleMaxW
                    ? textRenderer.trimToWidth(r.title, titleMaxW - 8) + "…"
                    : r.title;
            ctx.drawTextWithShadow(textRenderer, title, panelX + 4, itemY + 4, 0xFFEEEEEE);

            // Durasi (kanan atas)
            String dur = "[" + r.duration + "]";
            ctx.drawTextWithShadow(textRenderer, dur,
                    panelX + panelW - 14 - textRenderer.getWidth(dur) - 4,
                    itemY + 4, DURATION_COLOR);

            // Uploader (bawah, abu-abu)
            String up = textRenderer.getWidth(r.uploader) > titleMaxW
                    ? textRenderer.trimToWidth(r.uploader, titleMaxW - 8) + "…"
                    : r.uploader;
            ctx.drawTextWithShadow(textRenderer, up, panelX + 4, itemY + 16, UPLOADER_COLOR);
        }

        // Scrollbar
        renderScrollbar(ctx, listY, listH);

        // Hint double-click
        if (!searchResults.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer, "Klik 2x untuk play",
                    panelX, panelY + 84 + listH + 4, 0xFF555577);
        }
    }

    private void renderScrollbar(DrawContext ctx, int listY, int listH) {
        if (searchResults.size() <= RESULTS_VISIBLE) return;
        int sbX = panelX + panelW - 12;
        int sbW = 10;
        ctx.fill(sbX, listY, sbX + sbW, listY + listH, 0xFF1A1A2A);

        float ratio    = (float) RESULTS_VISIBLE / searchResults.size();
        int   thumbH   = Math.max(16, (int)(listH * ratio));
        float scrolled = (float) scrollOffset / (searchResults.size() - RESULTS_VISIBLE);
        int   thumbY   = listY + (int)((listH - thumbH) * scrolled);

        ctx.fill(sbX + 2, thumbY, sbX + sbW - 2, thumbY + thumbH, 0xFF4466AA);
    }

    private void renderNowPlaying(DrawContext ctx) {
        int npY = panelY + 268;
        AudioManager.State st  = audio.getState();
        int   progress          = audio.getDownloadProgress();

        if (st == AudioManager.State.LOADING && progress >= 0) {
            renderProgressBar(ctx, panelX, npY, panelW, progress, audio.getStatusText());
            return;
        }

        ctx.drawTextWithShadow(textRenderer, "Now Playing:", panelX, npY, LABEL_COLOR);

        String info; int color;
        switch (st) {
            case PLAYING -> { info = audio.getTrackName();                color = PLAYING_COLOR; }
            case PAUSED  -> { info = "[PAUSED] " + audio.getTrackName(); color = 0xFFFFAA00;    }
            case LOADING -> { info = audio.getStatusText();               color = 0xFFAAAAAA;    }
            case ERROR   -> { info = audio.getErrorMsg();                 color = ERROR_COLOR;   }
            default      -> { info = "No track loaded";                   color = 0xFF666688;    }
        }

        int maxW = panelW - 78;
        String disp = textRenderer.getWidth(info) > maxW
                ? textRenderer.trimToWidth(info, maxW) + "…" : info;
        ctx.drawTextWithShadow(textRenderer, disp, panelX + 74, npY, color);
    }

    private void renderProgressBar(DrawContext ctx, int x, int y, int w, int pct, String status) {
        int barH  = 10;
        int fill  = (int)(w * (pct / 100f));
        int fillC = pct >= 100 ? BAR_DONE_COLOR : BAR_FILL_COLOR;

        ctx.fill(x, y, x + w, y + barH, BAR_BG_COLOR);
        if (fill > 0) ctx.fill(x, y, x + fill, y + barH, fillC);
        ctx.fill(x, y, x + w, y + 1,      0xFF334466);
        ctx.fill(x, y + barH - 1, x + w, y + barH, 0xFF334466);
        ctx.fill(x, y, x + 1, y + barH,   0xFF334466);
        ctx.fill(x + w - 1, y, x + w, y + barH, 0xFF334466);

        String pctTxt = pct + "%";
        ctx.drawTextWithShadow(textRenderer, pctTxt,
                x + (w - textRenderer.getWidth(pctTxt)) / 2, y + 1, 0xFFFFFFFF);

        if (status != null && !status.isEmpty()) {
            String d = textRenderer.getWidth(status) > w
                    ? textRenderer.trimToWidth(status, w - 10) + "…" : status;
            ctx.drawTextWithShadow(textRenderer, d, x, y + barH + 4, 0xFFAAAAAA);
        }
    }

    private void renderQualityDropdown(DrawContext ctx, int mx, int my) {
        int ddX = panelX + panelW - 120;
        int ddY = panelY + 42;
        int ddW = 118;
        int ddH = QUALITIES.length * 18 + 4;

        ctx.fill(ddX - 1, ddY - 1, ddX + ddW + 1, ddY + ddH + 1, BORDER_COLOR);
        ctx.fill(ddX, ddY, ddX + ddW, ddY + ddH, 0xFF151520);

        for (int i = 0; i < QUALITIES.length; i++) {
            int iy = ddY + 2 + i * 18;
            boolean hover = mx >= ddX && mx < ddX + ddW && my >= iy && my < iy + 18;
            boolean cur   = QUALITIES[i] == audio.getQuality();
            if (hover) ctx.fill(ddX, iy, ddX + ddW, iy + 18, 0xFF252540);
            int tc = cur ? 0xFF44FF88 : (hover ? 0xFFFFFFFF : 0xFFCCCCCC);
            String prefix = cur ? "✓ " : "  ";
            ctx.drawTextWithShadow(textRenderer, prefix + QUALITIES[i].label, ddX + 4, iy + 4, tc);
        }
    }

    @Override
    public void renderBackground(DrawContext ctx) { /* no-op */ }

    // ─────────────────────────────────────────────────────────────────────────
    // Mouse events
    // ─────────────────────────────────────────────────────────────────────────

    private long lastClickTime = 0;
    private int  lastClickIdx  = -1;

    @Override
    public boolean mouseClicked(net.minecraft.client.input.Click click, boolean fromTouch) {
        double mx = click.x();
        double my = click.y();
        // Tutup dropdown jika klik di luar
        if (qualityDropdownOpen) {
            int ddX = panelX + panelW - 120;
            int ddY = panelY + 42;
            int ddW = 118;
            int ddH = QUALITIES.length * 18 + 4;

            if (mx >= ddX && mx < ddX + ddW && my >= ddY && my < ddY + ddH) {
                int idx = ((int)my - (ddY + 2)) / 18;
                if (idx >= 0 && idx < QUALITIES.length) {
                    audio.setQuality(QUALITIES[idx]);
                    btnQuality.setMessage(Text.literal("⚙ " + QUALITIES[idx].label));
                }
            }
            qualityDropdownOpen = false;
            return true;
        }

        // Klik hasil search
        if (activeTab == Tab.SEARCH && !searchResults.isEmpty()) {
            int listY = panelY + 84;
            int listW = panelW - 14;
            if (mx >= panelX && mx < panelX + listW && my >= listY) {
                int idx = scrollOffset + (int)((my - listY) / RESULT_H);
                if (idx >= 0 && idx < searchResults.size()
                        && my < listY + RESULTS_VISIBLE * RESULT_H) {
                    long now = System.currentTimeMillis();
                    if (idx == lastClickIdx && now - lastClickTime < 500) {
                        // Double-click → play
                        playSearchResult(idx);
                    } else {
                        selectedResult = idx;
                    }
                    lastClickIdx  = idx;
                    lastClickTime = now;
                    return true;
                }
            }
        }

        return super.mouseClicked(click, fromTouch);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        if (activeTab == Tab.SEARCH && !searchResults.isEmpty()) {
            int listY = panelY + 84;
            if (my >= listY && my < listY + RESULTS_VISIBLE * RESULT_H) {
                scrollOffset = clamp(scrollOffset - (int)vAmt, 0,
                        Math.max(0, searchResults.size() - RESULTS_VISIBLE));
                return true;
            }
        }
        return super.mouseScrolled(mx, my, hAmt, vAmt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Keyboard
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        // Enter di search field → cari
        if (activeTab == Tab.SEARCH && input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                && searchField.isFocused()) {
            doSearch();
            return true;
        }
        // Enter saat ada hasil terpilih → play
        if (activeTab == Tab.SEARCH && input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                && selectedResult >= 0) {
            playSearchResult(selectedResult);
            return true;
        }
        // Arrow keys untuk navigasi hasil
        if (activeTab == Tab.SEARCH && !searchResults.isEmpty()) {
            if (input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN) {
                selectedResult = Math.min(selectedResult + 1, searchResults.size() - 1);
                if (selectedResult >= scrollOffset + RESULTS_VISIBLE)
                    scrollOffset = Math.min(scrollOffset + 1, searchResults.size() - RESULTS_VISIBLE);
                return true;
            }
            if (input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_UP) {
                selectedResult = Math.max(selectedResult - 1, 0);
                if (selectedResult < scrollOffset)
                    scrollOffset = Math.max(scrollOffset - 1, 0);
                return true;
            }
        }
        if (input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        return super.keyPressed(input);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void switchTab(Tab tab) {
        activeTab = tab;
        qualityDropdownOpen = false;
        refreshTabVisibility();
    }

    private void refreshTabVisibility() {
        boolean isSearch = activeTab == Tab.SEARCH;
        boolean isLocal  = activeTab == Tab.LOCAL;
        boolean isYt     = activeTab == Tab.YOUTUBE;

        inputField.visible  = isLocal || isYt;
        searchField.visible = isSearch;
        btnSearch.visible   = isSearch;

        inputField.setPlaceholder(Text.literal(getInputPlaceholder()));

        tabLocalBtn.active   = activeTab != Tab.LOCAL;
        tabYouTubeBtn.active = activeTab != Tab.YOUTUBE;
        tabSearchBtn.active  = activeTab != Tab.SEARCH;
    }

    private String getInputPlaceholder() {
        return activeTab == Tab.LOCAL
                ? "/path/to/music.wav"
                : "https://www.youtube.com/watch?v=...";
    }

    private void toggleQualityDropdown() {
        qualityDropdownOpen = !qualityDropdownOpen;
    }

    private void doSearch() {
        String query = searchField.getText().trim();
        if (query.isEmpty() || searching) return;

        searching = true;
        searchStatus = "Searching...";
        searchResults.clear();
        selectedResult = -1;
        scrollOffset   = 0;

        searchWorker.submit(() -> {
            try {
                // Pastikan yt-dlp tersedia
                String ytDlpPath = YtDlpManager.getOrDownload(msg -> searchStatus = msg);
                if (ytDlpPath == null) {
                    searchStatus = "yt-dlp not found.";
                    return;
                }

                List<YtSearchManager.SearchResult> results = YtSearchManager.search(
                        query, 8, ytDlpPath, msg -> searchStatus = msg);

                searchResults.clear();
                searchResults.addAll(results);
                if (results.isEmpty()) searchStatus = "Tidak ada hasil ditemukan.";
                else searchStatus = "";

            } finally {
                searching = false;
            }
        });
    }

    private void playSearchResult(int idx) {
        if (idx < 0 || idx >= searchResults.size()) return;
        String url = searchResults.get(idx).url;
        String title = searchResults.get(idx).title;
        switchTab(Tab.YOUTUBE);
        inputField.setText(url);
        audio.playYouTube(url);
    }

    private void handlePlay() {
        if (activeTab == Tab.LOCAL) {
            String path = inputField.getText().trim();
            if (!path.isEmpty()) audio.playLocalFile(Paths.get(path));
        } else if (activeTab == Tab.YOUTUBE) {
            String url = inputField.getText().trim();
            if (!url.isEmpty()) audio.playYouTube(url);
        } else if (activeTab == Tab.SEARCH && selectedResult >= 0) {
            playSearchResult(selectedResult);
        }
    }

    private void handlePauseResume() {
        AudioManager.State s = audio.getState();
        if (s == AudioManager.State.PLAYING)     audio.pause();
        else if (s == AudioManager.State.PAUSED) audio.resume();
    }

    private Text loopLabel() {
        return audio.isLooping() ? Text.literal("✓ Loop ON") : Text.literal("  Loop OFF");
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    @Override public boolean shouldPause() { return false; }

    // ── Volume Slider ─────────────────────────────────────────────────────────
    private class VolumeSlider extends SliderWidget {
        VolumeSlider(int x, int y, int w, int h, float vol) {
            super(x, y, w, h, Text.literal(""), vol);
        }
        @Override protected void updateMessage() {}
        @Override protected void applyValue() { audio.setVolume((float) this.value); }
    }
}
