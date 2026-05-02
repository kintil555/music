package com.musicplayer.client.gui;

import com.musicplayer.client.audio.AudioManager;
import com.musicplayer.client.audio.AudioQuality;
import com.musicplayer.client.audio.YtDlpManager;
import com.musicplayer.client.audio.YtSearchManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Music Player GUI — Minecraft style.
 * Tab: Local | YouTube | Search
 * Fitur baru: Stream (playback tanpa download penuh)
 */
@Environment(EnvType.CLIENT)
public class MusicPlayerScreen extends Screen {

    // ── Minecraft color palette ────────────────────────────────────────────────
    private static final int MC_PANEL_BG      = 0xFF2B2B2B;
    private static final int MC_PANEL_INNER   = 0xFF373737;
    private static final int MC_BORDER_DARK   = 0xFF141414;
    private static final int MC_BORDER_LIGHT  = 0xFF666666;
    private static final int MC_BORDER_ACCENT = 0xFF5B5B5B;

    private static final int MC_TEXT_WHITE    = 0xFFEEEEEE;
    private static final int MC_TEXT_GRAY     = 0xFFAAAAAA;
    private static final int MC_TEXT_DARK     = 0xFF555555;
    private static final int MC_TEXT_YELLOW   = 0xFFFFFF55;
    private static final int MC_TEXT_GREEN    = 0xFF55FF55;
    private static final int MC_TEXT_AQUA     = 0xFF55FFFF;
    private static final int MC_TEXT_RED      = 0xFFFF5555;
    private static final int MC_TEXT_GOLD     = 0xFFFFAA00;

    private static final int MC_BAR_BG        = 0xFF1A1A1A;
    private static final int MC_BAR_FILL      = 0xFF3AAFAF;
    private static final int MC_BAR_DONE      = 0xFF55FF55;

    private static final int MC_LIST_BG       = 0xFF1E1E1E;
    private static final int MC_LIST_HOVER    = 0xFF2C3A2C;
    private static final int MC_LIST_SEL      = 0xFF1A3A1A;
    private static final int MC_LIST_DIVIDER  = 0xFF333333;

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
    private static final int     RESULT_H        = 26;

    // ── Quality dropdown ──────────────────────────────────────────────────────
    private boolean qualityDropdownOpen = false;
    private static final AudioQuality[] QUALITIES = AudioQuality.values();

    // ── Widgets ───────────────────────────────────────────────────────────────
    private TextFieldWidget inputField;
    private TextFieldWidget searchField;
    private ButtonWidget    btnPlay, btnStream, btnPauseResume, btnStop;
    private ButtonWidget    tabLocalBtn, tabYouTubeBtn, tabSearchBtn;
    private ButtonWidget    btnQuality;
    private VolumeSlider    volumeSlider;
    private ButtonWidget    btnLoop;
    private ButtonWidget    btnSearch;

    private final AudioManager audio = AudioManager.get();
    private final ExecutorService searchWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MusicPlayer-Search");
        t.setDaemon(true);
        return t;
    });

    // ── Layout ────────────────────────────────────────────────────────────────
    private int panelX, panelY, panelW, panelH;

    public MusicPlayerScreen() {
        super(Text.literal("Jukebox"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        panelW = Math.min(430, this.width  - 40);
        panelH = 318;
        panelX = (this.width  - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int cx  = panelX + 8;
        int iW  = panelW - 16;

        // ── Tab buttons ───────────────────────────────────────────────────────
        int tabY = panelY + 24;
        int tabW = 74;

        tabLocalBtn = ButtonWidget.builder(Text.literal("Local"),
                btn -> switchTab(Tab.LOCAL)
        ).dimensions(cx, tabY, tabW, 16).build();
        addDrawableChild(tabLocalBtn);

        tabYouTubeBtn = ButtonWidget.builder(Text.literal("YouTube"),
                btn -> switchTab(Tab.YOUTUBE)
        ).dimensions(cx + tabW + 3, tabY, tabW, 16).build();
        addDrawableChild(tabYouTubeBtn);

        tabSearchBtn = ButtonWidget.builder(Text.literal("Search"),
                btn -> switchTab(Tab.SEARCH)
        ).dimensions(cx + (tabW + 3) * 2, tabY, tabW, 16).build();
        addDrawableChild(tabSearchBtn);

        // Quality selector di kanan atas
        btnQuality = ButtonWidget.builder(
                Text.literal("Quality: " + audio.getQuality().shortLabel()),
                btn -> toggleQualityDropdown()
        ).dimensions(panelX + panelW - 102, tabY, 96, 16).build();
        addDrawableChild(btnQuality);

        // ── Input field (Local + YouTube) ──────────────────────────────────────
        inputField = new TextFieldWidget(
                this.textRenderer, cx, panelY + 58, iW, 18, Text.literal(""));
        inputField.setMaxLength(512);
        inputField.setPlaceholder(Text.literal(getInputPlaceholder()));
        addSelectableChild(inputField);
        addDrawableChild(inputField);

        // ── Play / Stream buttons di bawah input (YouTube) ────────────────────
        int dblY = panelY + 80;
        int halfW = (iW - 4) / 2;

        btnPlay = ButtonWidget.builder(Text.literal("Play (Download)"),
                btn -> handlePlay()
        ).dimensions(cx, dblY, halfW, 16).build();
        addDrawableChild(btnPlay);

        btnStream = ButtonWidget.builder(Text.literal("Stream (Online)"),
                btn -> handleStream()
        ).dimensions(cx + halfW + 4, dblY, halfW, 16).build();
        addDrawableChild(btnStream);

        // ── Search field + button ──────────────────────────────────────────────
        int sfW = iW - 60;
        searchField = new TextFieldWidget(
                this.textRenderer, cx, panelY + 58, sfW, 18, Text.literal(""));
        searchField.setMaxLength(200);
        searchField.setPlaceholder(Text.literal("Search song title..."));
        addSelectableChild(searchField);
        addDrawableChild(searchField);

        btnSearch = ButtonWidget.builder(Text.literal("Search"),
                btn -> doSearch()
        ).dimensions(cx + sfW + 4, panelY + 58, 54, 18).build();
        addDrawableChild(btnSearch);

        // ── Playback controls ──────────────────────────────────────────────────
        int ctrlY = panelY + 192;
        int ctrlW = (iW - 8) / 3;

        // Play button untuk Local (sama widget, tapi hanya muncul di Local tab di area control)
        // Kita pakai btnPauseResume dan btnStop saja di control bar, play disatukan dengan btnPlay di atas
        btnPauseResume = ButtonWidget.builder(Text.literal("Pause"),
                btn -> handlePauseResume()
        ).dimensions(cx + ctrlW + 4, ctrlY, ctrlW, 18).build();
        addDrawableChild(btnPauseResume);

        btnStop = ButtonWidget.builder(Text.literal("Stop"),
                btn -> audio.stop()
        ).dimensions(cx + (ctrlW + 4) * 2, ctrlY, ctrlW, 18).build();
        addDrawableChild(btnStop);

        // Play di control bar (untuk Local tab)
        ButtonWidget btnCtrlPlay = ButtonWidget.builder(Text.literal("Play"),
                btn -> handlePlay()
        ).dimensions(cx, ctrlY, ctrlW, 18).build();
        addDrawableChild(btnCtrlPlay);

        // ── Volume slider ──────────────────────────────────────────────────────
        volumeSlider = new VolumeSlider(cx, panelY + 218, iW - 50, 14, audio.getVolume());
        addDrawableChild(volumeSlider);

        // ── Loop button ───────────────────────────────────────────────────────
        btnLoop = ButtonWidget.builder(loopLabel(),
                btn -> { audio.setLooping(!audio.isLooping()); btn.setMessage(loopLabel()); }
        ).dimensions(cx, panelY + 240, 90, 16).build();
        addDrawableChild(btnLoop);

        refreshTabVisibility();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Render
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);

        // ── Panel outer border (Minecraft pixel-border) ───────────────────────
        ctx.fill(panelX + 2, panelY + panelH,     panelX + panelW + 2, panelY + panelH + 2, MC_BORDER_DARK);
        ctx.fill(panelX + panelW, panelY + 2,     panelX + panelW + 2, panelY + panelH,     MC_BORDER_DARK);
        ctx.fill(panelX,     panelY,              panelX + panelW,     panelY + 2,           MC_BORDER_LIGHT);
        ctx.fill(panelX,     panelY,              panelX + 2,          panelY + panelH,      MC_BORDER_LIGHT);
        ctx.fill(panelX,     panelY + panelH - 2, panelX + panelW,     panelY + panelH,      MC_BORDER_DARK);
        ctx.fill(panelX + panelW - 2, panelY,     panelX + panelW,     panelY + panelH,      MC_BORDER_DARK);

        // ── Panel background ──────────────────────────────────────────────────
        ctx.fill(panelX + 2, panelY + 2, panelX + panelW - 2, panelY + panelH - 2, MC_PANEL_BG);

        // ── Title bar ─────────────────────────────────────────────────────────
        ctx.fill(panelX + 2, panelY + 2, panelX + panelW - 2, panelY + 20, MC_PANEL_INNER);
        ctx.drawCenteredTextWithShadow(textRenderer, "Jukebox",
                this.width / 2, panelY + 7, MC_TEXT_YELLOW);

        // ── Tab underline separator ────────────────────────────────────────────
        ctx.fill(panelX + 2, panelY + 43, panelX + panelW - 2, panelY + 44, MC_BORDER_ACCENT);

        // ── Tab content ───────────────────────────────────────────────────────
        switch (activeTab) {
            case LOCAL   -> renderLocalTab(ctx);
            case YOUTUBE -> renderYouTubeTab(ctx);
            case SEARCH  -> renderSearchTab(ctx, mouseX, mouseY);
        }

        // ── Control area separator ────────────────────────────────────────────
        ctx.fill(panelX + 2, panelY + 186, panelX + panelW - 2, panelY + 187, MC_BORDER_ACCENT);

        // ── Volume label ──────────────────────────────────────────────────────
        String volTxt = "Vol " + Math.round(audio.getVolume() * 100) + "%";
        ctx.drawTextWithShadow(textRenderer, volTxt,
                panelX + panelW - 8 - textRenderer.getWidth(volTxt), panelY + 221, MC_TEXT_GRAY);

        // ── Now playing separator ─────────────────────────────────────────────
        ctx.fill(panelX + 2, panelY + 260, panelX + panelW - 2, panelY + 261, MC_BORDER_ACCENT);
        renderNowPlaying(ctx);

        // Update Pause/Resume label
        AudioManager.State st = audio.getState();
        btnPauseResume.setMessage(st == AudioManager.State.PAUSED
                ? Text.literal("Resume") : Text.literal("Pause"));

        // Render semua widgets
        super.render(ctx, mouseX, mouseY, delta);

        // Quality dropdown terakhir (di atas semua)
        if (qualityDropdownOpen) renderQualityDropdown(ctx, mouseX, mouseY);
    }

    private void renderLocalTab(DrawContext ctx) {
        ctx.drawTextWithShadow(textRenderer, "File path (.wav / .aiff):",
                panelX + 8, panelY + 47, MC_TEXT_GRAY);
    }

    private void renderYouTubeTab(DrawContext ctx) {
        ctx.drawTextWithShadow(textRenderer, "YouTube URL:",
                panelX + 8, panelY + 47, MC_TEXT_GRAY);
        ctx.drawTextWithShadow(textRenderer,
                "Download = save to cache   |   Stream = no download",
                panelX + 8, panelY + 100, MC_TEXT_DARK);
    }

    private void renderSearchTab(DrawContext ctx, int mx, int my) {
        ctx.drawTextWithShadow(textRenderer, "Search YouTube:",
                panelX + 8, panelY + 47, MC_TEXT_GRAY);

        int listX = panelX + 8;
        int listY = panelY + 82;
        int listW = panelW - 16;
        int listH = RESULTS_VISIBLE * RESULT_H;

        ctx.fill(listX, listY, listX + listW, listY + listH, MC_LIST_BG);
        drawMcBorder(ctx, listX, listY, listW, listH);

        if (searching) {
            ctx.drawCenteredTextWithShadow(textRenderer, "Searching...",
                    panelX + panelW / 2, listY + listH / 2 - 4, MC_TEXT_GRAY);
            return;
        }

        if (!searchStatus.isEmpty() && searchResults.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, searchStatus,
                    panelX + panelW / 2, listY + listH / 2 - 4, MC_TEXT_DARK);
            return;
        }

        int sbW   = 8;
        int itemW = listW - sbW - 2;
        int visEnd = Math.min(scrollOffset + RESULTS_VISIBLE, searchResults.size());

        for (int i = scrollOffset; i < visEnd; i++) {
            int itemY = listY + (i - scrollOffset) * RESULT_H;
            YtSearchManager.SearchResult r = searchResults.get(i);

            boolean hovered  = mx >= listX && mx < listX + itemW
                    && my >= itemY && my < itemY + RESULT_H;
            boolean selected = i == selectedResult;

            int bg = selected ? MC_LIST_SEL : (hovered ? MC_LIST_HOVER : MC_LIST_BG);
            ctx.fill(listX, itemY, listX + itemW, itemY + RESULT_H, bg);
            if (selected) ctx.fill(listX, itemY, listX + 2, itemY + RESULT_H, MC_BAR_FILL);
            ctx.fill(listX, itemY + RESULT_H - 1, listX + itemW, itemY + RESULT_H, MC_LIST_DIVIDER);

            int durW     = textRenderer.getWidth("[00:00]") + 4;
            int titleMaxW = itemW - durW - 8;
            String title = textRenderer.getWidth(r.title) > titleMaxW
                    ? textRenderer.trimToWidth(r.title, titleMaxW - 4) + "..." : r.title;
            ctx.drawTextWithShadow(textRenderer, title,
                    listX + 5, itemY + 4, selected ? MC_TEXT_GREEN : MC_TEXT_WHITE);

            String dur = "[" + r.duration + "]";
            ctx.drawTextWithShadow(textRenderer, dur,
                    listX + itemW - textRenderer.getWidth(dur) - 4, itemY + 4, MC_TEXT_AQUA);

            String up = textRenderer.getWidth(r.uploader) > titleMaxW
                    ? textRenderer.trimToWidth(r.uploader, titleMaxW - 4) + "..." : r.uploader;
            ctx.drawTextWithShadow(textRenderer, up, listX + 5, itemY + 15, MC_TEXT_DARK);
        }

        renderScrollbar(ctx, listX + listW - sbW, listY, sbW, listH);

        if (!searchResults.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer, "Double-click or Enter to stream",
                    panelX + 8, listY + listH + 3, MC_TEXT_DARK);
        }
    }

    private void drawMcBorder(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x,     y,     x + w, y + 1, MC_BORDER_LIGHT);
        ctx.fill(x,     y,     x + 1, y + h, MC_BORDER_LIGHT);
        ctx.fill(x,     y + h - 1, x + w, y + h, MC_BORDER_DARK);
        ctx.fill(x + w - 1, y, x + w, y + h, MC_BORDER_DARK);
    }

    private void renderScrollbar(DrawContext ctx, int sbX, int listY, int sbW, int listH) {
        if (searchResults.size() <= RESULTS_VISIBLE) return;
        ctx.fill(sbX, listY, sbX + sbW, listY + listH, 0xFF1A1A1A);
        float ratio  = (float) RESULTS_VISIBLE / searchResults.size();
        int   thumbH = Math.max(14, (int)(listH * ratio));
        float scroll = (float) scrollOffset / (searchResults.size() - RESULTS_VISIBLE);
        int   thumbY = listY + (int)((listH - thumbH) * scroll);
        ctx.fill(sbX + 2, thumbY,     sbX + sbW - 2, thumbY + thumbH, 0xFF555555);
        ctx.fill(sbX + 2, thumbY,     sbX + sbW - 2, thumbY + 1,      0xFF888888);
    }

    private void renderNowPlaying(DrawContext ctx) {
        int npY  = panelY + 266;
        int cx   = panelX + 8;
        AudioManager.State st = audio.getState();
        int progress = audio.getDownloadProgress();

        ctx.drawTextWithShadow(textRenderer, "Now Playing:", cx, npY, MC_TEXT_GRAY);

        if (st == AudioManager.State.LOADING && progress >= 0) {
            renderProgressBar(ctx, cx, npY + 10, panelW - 16, progress, audio.getStatusText());
            return;
        }

        String info; int color;
        switch (st) {
            case PLAYING -> { info = audio.getTrackName();                color = MC_TEXT_GREEN; }
            case PAUSED  -> { info = "[Paused] " + audio.getTrackName(); color = MC_TEXT_GOLD;  }
            case LOADING -> { info = audio.getStatusText();               color = MC_TEXT_GRAY;  }
            case ERROR   -> { info = audio.getErrorMsg();                 color = MC_TEXT_RED;   }
            default      -> { info = "No track loaded";                   color = MC_TEXT_DARK;  }
        }

        int labelW = textRenderer.getWidth("Now Playing: ");
        int maxW   = panelW - 16 - labelW;
        String disp = textRenderer.getWidth(info) > maxW
                ? textRenderer.trimToWidth(info, maxW - 4) + "..." : info;
        ctx.drawTextWithShadow(textRenderer, disp, cx + labelW, npY, color);
    }

    private void renderProgressBar(DrawContext ctx, int x, int y, int w, int pct, String status) {
        int barH = 8;
        int fill = (int)(w * (pct / 100f));
        int fillC = pct >= 100 ? MC_BAR_DONE : MC_BAR_FILL;
        ctx.fill(x, y, x + w, y + barH, MC_BAR_BG);
        if (fill > 0) ctx.fill(x, y, x + fill, y + barH, fillC);
        drawMcBorder(ctx, x, y, w, barH);
        String pctTxt = pct + "%";
        ctx.drawTextWithShadow(textRenderer, pctTxt,
                x + (w - textRenderer.getWidth(pctTxt)) / 2, y + 1, MC_TEXT_WHITE);
        if (status != null && !status.isEmpty()) {
            String d = textRenderer.getWidth(status) > w
                    ? textRenderer.trimToWidth(status, w - 8) + "..." : status;
            ctx.drawTextWithShadow(textRenderer, d, x, y + barH + 3, MC_TEXT_GRAY);
        }
    }

    private void renderQualityDropdown(DrawContext ctx, int mx, int my) {
        int ddX = panelX + panelW - 102;
        int ddY = panelY + 42;
        int ddW = 98;
        int ddH = QUALITIES.length * 16 + 4;
        ctx.fill(ddX - 1, ddY - 1, ddX + ddW + 1, ddY + ddH + 1, MC_BORDER_DARK);
        ctx.fill(ddX, ddY, ddX + ddW, ddY + ddH, 0xFF222222);
        for (int i = 0; i < QUALITIES.length; i++) {
            int iy = ddY + 2 + i * 16;
            boolean hover = mx >= ddX && mx < ddX + ddW && my >= iy && my < iy + 16;
            boolean cur   = QUALITIES[i] == audio.getQuality();
            if (hover || cur) ctx.fill(ddX, iy, ddX + ddW, iy + 16, cur ? MC_LIST_SEL : 0xFF303030);
            int tc = cur ? MC_TEXT_GREEN : (hover ? MC_TEXT_WHITE : MC_TEXT_GRAY);
            ctx.drawTextWithShadow(textRenderer, (cur ? "> " : "  ") + QUALITIES[i].shortLabel(),
                    ddX + 4, iy + 4, tc);
        }
    }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) { /* no-op */ }

    // ─────────────────────────────────────────────────────────────────────────
    // Mouse
    // ─────────────────────────────────────────────────────────────────────────

    private long lastClickTime = 0;
    private int  lastClickIdx  = -1;

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();

        if (qualityDropdownOpen) {
            int ddX = panelX + panelW - 102;
            int ddY = panelY + 42;
            int ddW = 98;
            int ddH = QUALITIES.length * 16 + 4;
            if (mx >= ddX && mx < ddX + ddW && my >= ddY && my < ddY + ddH) {
                int idx = ((int)my - (ddY + 2)) / 16;
                if (idx >= 0 && idx < QUALITIES.length) {
                    audio.setQuality(QUALITIES[idx]);
                    btnQuality.setMessage(Text.literal("Quality: " + QUALITIES[idx].shortLabel()));
                }
            }
            qualityDropdownOpen = false;
            return true;
        }

        if (activeTab == Tab.SEARCH && !searchResults.isEmpty()) {
            int listX = panelX + 8;
            int listY = panelY + 82;
            int listW = panelW - 16 - 10;
            if (mx >= listX && mx < listX + listW && my >= listY) {
                int idx = scrollOffset + (int)((my - listY) / RESULT_H);
                if (idx >= 0 && idx < searchResults.size()
                        && my < listY + RESULTS_VISIBLE * RESULT_H) {
                    long now = System.currentTimeMillis();
                    if (idx == lastClickIdx && now - lastClickTime < 500) {
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

        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        if (activeTab == Tab.SEARCH && !searchResults.isEmpty()) {
            int listY = panelY + 82;
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
        if (activeTab == Tab.SEARCH && input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                && searchField.isFocused()) { doSearch(); return true; }
        if (activeTab == Tab.SEARCH && input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                && selectedResult >= 0) { playSearchResult(selectedResult); return true; }
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
        boolean isYt     = activeTab == Tab.YOUTUBE;

        inputField.visible  = !isSearch;
        searchField.visible = isSearch;
        btnSearch.visible   = isSearch;
        btnPlay.visible     = isYt;
        btnStream.visible   = isYt;

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

    private void toggleQualityDropdown() { qualityDropdownOpen = !qualityDropdownOpen; }

    private void doSearch() {
        String query = searchField.getText().trim();
        if (query.isEmpty() || searching) return;
        searching = true; searchStatus = "Searching...";
        searchResults.clear(); selectedResult = -1; scrollOffset = 0;
        searchWorker.submit(() -> {
            try {
                String ytDlpPath = YtDlpManager.getOrDownload(msg -> searchStatus = msg);
                if (ytDlpPath == null) { searchStatus = "yt-dlp not found."; return; }
                List<YtSearchManager.SearchResult> res = YtSearchManager.search(
                        query, 8, ytDlpPath, msg -> searchStatus = msg);
                searchResults.clear(); searchResults.addAll(res);
                searchStatus = res.isEmpty() ? "No results found." : "";
            } finally { searching = false; }
        });
    }

    private void playSearchResult(int idx) {
        if (idx < 0 || idx >= searchResults.size()) return;
        String url = searchResults.get(idx).url;
        switchTab(Tab.YOUTUBE);
        inputField.setText(url);
        audio.streamYouTube(url);
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

    private void handleStream() {
        String url = inputField.getText().trim();
        if (!url.isEmpty()) audio.streamYouTube(url);
    }

    private void handlePauseResume() {
        AudioManager.State s = audio.getState();
        if (s == AudioManager.State.PLAYING)     audio.pause();
        else if (s == AudioManager.State.PAUSED) audio.resume();
    }

    private Text loopLabel() {
        return audio.isLooping() ? Text.literal("Loop: ON") : Text.literal("Loop: OFF");
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    @Override public boolean shouldPause() { return false; }

    // ── Volume Slider ─────────────────────────────────────────────────────────
    private class VolumeSlider extends SliderWidget {
        VolumeSlider(int x, int y, int w, int h, float vol) {
            super(x, y, w, h, Text.literal(""), vol);
        }
        @Override protected void updateMessage() {}
        @Override protected void applyValue()    { audio.setVolume((float) this.value); }
    }
}
