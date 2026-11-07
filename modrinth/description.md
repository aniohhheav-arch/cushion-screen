# Cushion Screen

**Build a cinema in your world.** Place padded Cushion Screen blocks in a rectangle of any size, right-click, pick a video from your computer, and watch it on the blocks, with sound, in sync with everyone nearby.

## Features

- **Screens of any size.** Tiles that face the same way fuse into one seamless display, from a 1×1 TV up to a 64×64 drive-in screen. They work on walls, floors and ceilings.
- **Your own videos.** MP4, WebM, MOV and MKV, picked with your system's normal file dialog. Videos keep their shape (letterboxed, never stretched) at any resolution.
- **Real sound.** The audio comes from the screen's position in the world and fades with distance. It stays in sync with the picture, and switches to normal stereo in fullscreen.
- **Watch together.** Play, pause, seek and loop are shared by everyone, and every player sees the same moment.
- **Only one player needs the file.** Everyone else gets the video through the server automatically, with a progress bar on the screen. Players who join later get it from the server's copy.
- **Proper controls.** A right-click menu with a live preview, a seek slider, play/pause/stop, loop, fullscreen, your own volume and "mute for me".
- **Owner settings.** Screen name, power, brightness, speaker volume, picture rotation, and who may control it: everyone, the owner only, or operators only.
- **Polish.** A CRT-style power-on and power-off, a NO SIGNAL screen, a loading animation, an on-screen time display, a soft glow in the picture's colours, and real block light while it plays.
- **Decorative frames.** Padded Screen Frame blocks to build a bezel around your screen.

## Crafting

| Block | Recipe | Makes |
|---|---|---|
| Cushion Screen | Black wool in the corners, glass panes on the sides, redstone in the middle | 6 |
| Padded Screen Frame | Gray wool in the corners, string on the sides, middle empty | 8 |

Both are also in the **Cushion Screen** creative tab.

## How to use

1. Build a filled rectangle of Cushion Screens. Clicking the side of an existing screen extends it.
2. Right-click it, then **Select Video…** and pick a file.
3. Use **Play**, drag the slider to seek, or open **Fullscreen** (Space: play/pause, ← →: 10 seconds, Esc: back).

## Multiplayer

The mod is needed on the **server and on every player's game**.

When a player loads a video, the server keeps everyone on one shared timeline. Players who don't have the file get it through the server: the player who loaded it uploads it in the background, and the server keeps a checked copy and sends it on. Every copy is verified against the original file's SHA-256 before it plays.

Server owners can change this in `config/cushionscreen-server.json`:

| Setting | Default | |
|---|---|---|
| `shareVideos` | `true` | Pass videos between players through the server |
| `maxVideoSizeMB` | `4096` | Largest video the server passes along |
| `cacheSizeMB` | `16384` | Disk space for shared videos; oldest removed first |
| `maxConcurrentUploads` | `2` | Players uploading at the same time |

Only share videos you have the right to share.

## Performance

Video is only decoded while a screen is in view, and sound only while you're in hearing range. Hardware video decoding is used where available. At most 6 screens decode at once (nearest first, configurable), and everything is released a few seconds after you walk away. Client options are in `config/cushionscreen-client.json`.

## Requirements

- Minecraft **26.3**
- **Fabric** with Fabric API, or **NeoForge**
- Windows (x64), macOS (Intel or Apple silicon) or Linux (x64)

The download is about 90 MB because it carries the FFmpeg video decoder for all four platforms.

## Credits

Video decoding uses [FFmpeg](https://ffmpeg.org) (LGPL 2.1 or later) through [JavaCPP Presets](https://github.com/bytedeco/javacpp-presets) (Apache 2.0).
