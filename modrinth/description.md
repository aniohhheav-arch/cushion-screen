# 📺 Cushion Screen

**Build a cinema in your world.** Place padded Cushion Screen blocks in a rectangle of any size, right-click, pick a video from your computer, and watch it on the blocks, with sound, in sync with everyone nearby.

---

## ✨ Features

- 🧱 **Screens of any size**: from a 1×1 TV to a 64×64 drive-in screen, on walls, floors or ceilings. The blocks fuse into one seamless picture.
- 🎬 **Your own videos**: MP4, WebM, MOV and MKV, picked with your normal file window. Videos keep their shape and are never stretched.
- 🔊 **Real sound**: comes from the screen's spot in the world, fades with distance and stays in sync with the picture.
- 👥 **Watch together**: play, pause, seek and loop are shared, so everyone sees the same moment.
- 📤 **Only one player needs the file**: everyone else gets the video through the server automatically, with a progress bar on the screen.
- 🎛️ **Easy controls**: a right-click menu with a live preview, a seek slider, loop, fullscreen, your own volume and "mute for me".
- 🔒 **Owner settings**: name, power, brightness, speaker volume, picture rotation, and who may control it.
- 💡 **Little touches**: an old-TV power-on, a NO SIGNAL screen, a soft glow in the picture's colours, and real light while it plays.
- 🖼️ **Padded Screen Frame** blocks to build a border around your screen.

---

## 🛠️ Crafting

**Cushion Screen** ×6
Black wool in the four corners, glass panes on the four sides, redstone in the middle.

**Padded Screen Frame** ×8
Gray wool in the four corners, string on the four sides, middle empty.

Both are also in the **Cushion Screen** creative tab.

---

## ▶️ How to use

1. Build a **filled rectangle** of Cushion Screens. Click the side of a screen to make it bigger.
2. **Right-click** it and choose **Select Video…**
3. Press **Play**, drag the slider to jump around, or open **Fullscreen**.

**Fullscreen keys:** `Space` play/pause · `←` `→` jump 10 seconds · `Esc` back

---

## 🌐 Multiplayer

Install the mod on the **server** and on **every player's game**.

The player who picks a video is the only one who needs the file. Everyone else gets it through the server automatically, and players who join later get it from the server's copy. Every copy is checked against the original before it plays.

<details>
<summary><b>⚙️ Server settings</b> (click to open)</summary>

In `config/cushionscreen-server.json`:

- `shareVideos`: pass videos between players (default `true`)
- `maxVideoSizeMB`: largest video the server passes along (default `4096`)
- `cacheSizeMB`: disk space for shared videos; the oldest are removed first (default `16384`)
- `maxConcurrentUploads`: players uploading at the same time (default `2`)

</details>

> ⚠️ Only share videos you have the right to share.

---

## ⚡ Performance

Videos are only decoded while you can see the screen, and sound only plays while you're close enough to hear it. Hardware video decoding is used where your computer supports it, and everything is cleaned up a few seconds after you walk away.

---

## 📋 Requirements

- Minecraft **26.3**
- **Fabric** + Fabric API, or **NeoForge**
- Windows, macOS (Intel or Apple chip) or Linux

The file is about 90 MB because it includes the video decoder for every system.

---

<sub>Video decoding by [FFmpeg](https://ffmpeg.org) (LGPL 2.1+) through [JavaCPP Presets](https://github.com/bytedeco/javacpp-presets) (Apache 2.0).</sub>
