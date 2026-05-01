# 🎵 Music Player Mod — Fabric 1.21.11

Mod Minecraft Fabric yang memungkinkan kamu memutar musik langsung di dalam game.  
Tekan **F9** untuk membuka Music Player kapan saja.

---

## ✨ Fitur

| Fitur | Keterangan |
|---|---|
| **Local File** | Putar file WAV / AIFF dari komputer kamu |
| **YouTube** | Putar audio dari link YouTube (butuh yt-dlp) |
| **Loop** | Toggle loop on/off |
| **Pause / Resume** | Jeda dan lanjutkan lagu |
| **Stop** | Hentikan playback |
| **Volume Slider** | Atur volume 0–100% |
| **Lightweight** | Tidak ada library eksternal — hanya Fabric API & JDK |

---

## 📦 Versi yang Dibutuhkan

| Komponen | Versi |
|---|---|
| Minecraft | **1.21.11** |
| Fabric Loader | **≥ 0.18.1** |
| Fabric API | **0.141.2+1.21.11** atau lebih baru |
| Java | **21** |
| yt-dlp (opsional) | Untuk fitur YouTube |

---

## 🚀 Cara Install

1. Install [Fabric Loader 0.18.1+](https://fabricmc.net/use/installer/)
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) (taruh di folder `mods/`)
3. Download `.jar` dari [Releases](../../releases) dan taruh di folder `mods/`

### Untuk fitur YouTube
Install **yt-dlp** dan pastikan ada di PATH sistem:
- Windows: `winget install yt-dlp` atau download dari [github.com/yt-dlp/yt-dlp](https://github.com/yt-dlp/yt-dlp)
- Linux/macOS: `pip install yt-dlp` atau `brew install yt-dlp`

---

## 🎮 Cara Pakai

1. Masuk ke game, tekan **F9**
2. Pilih tab:
   - **Local File** → masukkan path lengkap file WAV/AIFF, contoh:  
     `C:\Music\lagu.wav` atau `/home/user/music/lagu.wav`
   - **YouTube** → masukkan URL YouTube, contoh:  
     `https://www.youtube.com/watch?v=dQw4w9WgXcQ`
3. Klik **▶ Play**
4. Atur volume dengan slider, toggle loop, atau pause/stop sesuai kebutuhan

> **Catatan format**: Untuk local file, gunakan WAV atau AIFF.  
> MP3 tidak didukung secara native oleh JDK. Untuk MP3, convert dulu ke WAV  
> dengan tools seperti ffmpeg: `ffmpeg -i lagu.mp3 lagu.wav`

---

## 🔨 Build dari Source

### Prerequisites
- JDK 21
- Git

```bash
git clone https://github.com/yourname/musicplayer-mod.git
cd musicplayer-mod
./gradlew build
```

File JAR tersedia di `build/libs/musicplayer-*.jar`

### GitHub Actions
Repo ini sudah dikonfigurasi dengan GitHub Actions.  
Build otomatis berjalan setiap push ke `main`.  
Untuk membuat release, buat tag dengan format `v*`:

```bash
git tag v1.0.0
git push origin v1.0.0
```

---

## 🏗️ Struktur Proyek

```
musicplayer-mod/
├── .github/
│   └── workflows/
│       └── build.yml            # GitHub Actions CI/CD
├── src/
│   ├── main/
│   │   ├── java/com/musicplayer/
│   │   └── resources/
│   │       ├── fabric.mod.json
│   │       ├── musicplayer.mixins.json
│   │       └── assets/musicplayer/lang/en_us.json
│   └── client/
│       └── java/com/musicplayer/client/
│           ├── MusicPlayerClient.java   # Entrypoint & keybinding F9
│           ├── audio/
│           │   └── AudioManager.java    # Engine playback audio
│           └── gui/
│               └── MusicPlayerScreen.java  # GUI utama
├── gradle/
│   └── wrapper/gradle-wrapper.properties
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## 📝 Lisensi

MIT License — bebas digunakan dan dimodifikasi.
