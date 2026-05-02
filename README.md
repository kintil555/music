# 🎵 Music Player Mod — Fabric 1.21.11

A Minecraft Fabric mod that allows you to play music directly inside the game.  
Press **F9** to open the Music Player anytime.

---

## ✨ Features

| Feature | Description |
|---|---|
| **Local File** | Play WAV / AIFF files from your computer |
| **YouTube** | Play audio from YouTube links (requires yt-dlp) |
| **Loop** | Toggle loop on/off |
| **Pause / Resume** | Pause and resume playback |
| **Stop** | Stop playback |
| **Volume Slider** | Adjust volume from 0–100% |
| **Lightweight** | No external libraries — only Fabric API & JDK |

---

## 📦 Requirements

| Component | Version |
|---|---|
| Minecraft | **1.21.11** |
| Fabric Loader | **≥ 0.18.1** |
| Fabric API | **0.141.2+1.21.11** or newer |
| Java | **21** |
| yt-dlp (optional) | Required for YouTube feature |

---

## 🚀 Installation

1. Install Fabric Loader 0.18.1+: https://fabricmc.net/use/installer/
2. Install Fabric API: https://modrinth.com/mod/fabric-api (place it in the `mods/` folder)
3. Download the `.jar` from Releases and place it in the `mods/` folder

### For YouTube feature
Install **yt-dlp** and make sure it is available in your system PATH:
- Windows: `winget install yt-dlp` or download from https://github.com/yt-dlp/yt-dlp
- Linux/macOS: `pip install yt-dlp` or `brew install yt-dlp`

---

## 🎮 Usage

1. Enter the game and press **F9**
2. Choose a tab:
   - **Local File** → enter the full file path (WAV/AIFF), for example:  
     `C:\Music\song.wav` or `/home/user/music/song.wav`
   - **YouTube** → enter a YouTube URL, for example:  
     `https://www.youtube.com/watch?v=dQw4w9WgXcQ`
3. Click **▶ Play**
4. Adjust volume, toggle loop, or pause/stop as needed

> **Format note**: Local files must be in WAV or AIFF format.  
> MP3 is not natively supported by the JDK. Convert it first using tools like ffmpeg:  
> `ffmpeg -i song.mp3 song.wav`

---

## 🔨 Build from Source

### Prerequisites
- JDK 21
- Git

```bash
git clone https://github.com/yourname/musicplayer-mod.git
cd musicplayer-mod
./gradlew build
