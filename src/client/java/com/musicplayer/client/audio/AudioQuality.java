package com.musicplayer.client.audio;

/**
 * Pilihan kualitas audio saat download dari YouTube.
 * Angka = nilai --audio-quality yt-dlp (0 = terbaik, 9 = terburuk/terkecil)
 */
public enum AudioQuality {
    LOW   ("Low (kecil, cepat)",  9),
    MEDIUM("Medium (seimbang)",   5),
    HIGH  ("High (kualitas baik)",2),
    BEST  ("Best (terbaik)",      0);

    public final String label;
    public final int    ytDlpQuality; // 0-9

    AudioQuality(String label, int ytDlpQuality) {
        this.label        = label;
        this.ytDlpQuality = ytDlpQuality;
    }

    public static AudioQuality getDefault() { return MEDIUM; }
}
