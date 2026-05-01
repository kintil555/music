package com.musicplayer.client;

import com.musicplayer.client.audio.AudioManager;
import com.musicplayer.client.gui.MusicPlayerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side entrypoint untuk Music Player Mod.
 *
 * Yang dilakukan di sini:
 *  1. Daftarkan keybinding F9 ("Open Music Player")
 *  2. Daftarkan ClientTickEvent untuk deteksi tombol
 *  3. Bersihkan AudioManager saat client shutdown
 */
@Environment(EnvType.CLIENT)
public class MusicPlayerClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("MusicPlayer");

    private static KeyBinding keyOpenGui;

    @Override
    public void onInitializeClient() {
        // ── Daftarkan keybinding F9 ───────────────────────────────────────────
        keyOpenGui = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.musicplayer.open_gui",          // translation key
                InputUtil.Type.KEYSYM,               // tipe: keyboard
                GLFW.GLFW_KEY_F9,                    // default: F9
                "key.categories.musicplayer"         // kategori di Settings > Controls
        ));

        // ── Deteksi keypress tiap tick ────────────────────────────────────────
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyOpenGui.wasPressed()) {
                if (client.currentScreen == null) {
                    // Buka GUI hanya kalau tidak ada screen lain terbuka
                    client.setScreen(new MusicPlayerScreen());
                } else if (client.currentScreen instanceof MusicPlayerScreen) {
                    // Sudah terbuka → tutup
                    client.setScreen(null);
                }
            }
        });

        // ── Shutdown AudioManager saat game ditutup ───────────────────────────
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            LOGGER.info("[MusicPlayer] Shutting down audio engine...");
            AudioManager.get().shutdown();
        });

        LOGGER.info("[MusicPlayer] Client initialized. Press F9 to open Music Player.");
    }
}
