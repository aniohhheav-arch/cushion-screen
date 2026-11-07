# Cushion Screen

A Minecraft **26.3** mod for **Fabric** and **NeoForge**. Build screens of any size out of padded Cushion Screen
blocks, right-click, pick a video file from your computer, and watch it on the blocks, with its sound, in sync
with everyone nearby.

## Playing

1. Craft **Cushion Screens** (6 per craft): black wool, glass panes and redstone.

   ```
   W G W
   G R G      W = black wool, G = glass pane, R = redstone
   W G W
   ```

   Optional decorative **Padded Screen Frame** (8 per craft): gray wool and string, `WSW / S S / WSW`.
2. Place them in a filled rectangle. All tiles must face the same way. Clicking the side of an existing screen
   extends it with the same facing. Walls, floors and ceilings all work, and so does any size up to 64×64.
   An unfilled shape shows **INCOMPLETE** on its tiles.
3. Right-click → **Select Video…** opens your system's file dialog. MP4, WebM, MOV and MKV are supported:
   anything FFmpeg can decode.
4. Use **Play / Pause / Stop**, drag the progress bar to seek, toggle **Loop**, open **Fullscreen**
   (Space: play/pause, ← →: 10 s, Esc: back).

The picture keeps its own aspect ratio (letterboxed, never stretched) across the whole wall as one seamless surface.

### Settings (owner and operators)

Name, power on/off, brightness, speaker volume, sound on/off, picture rotation (for floor, ceiling and portrait
screens) and **Control**: who may load videos and control playback (*Everyone*, *Owner only*, *Operators only*).

**My volume** and **Mute for me** in the main menu only affect you. What you hear is the screen's speaker
volume × your volume × the game's *Master* and *Jukebox/Note Blocks* sliders.

## Multiplayer: how videos are shared

**Only the player who loads a video needs the file.** Everyone else gets it through the server automatically:

1. When your game sees a screen with a video you don't have, it asks the server for it, with no clicks needed.
2. If the server doesn't have a copy yet, it asks an online player who has the video to share it. That player's
   game uploads it in the background ("Sharing … with other players… 50%" appears above their hotbar).
3. The server keeps a copy in `<world>/cushionscreen/media/` and streams it to everyone who needs it. Players
   who join later get it straight from the server, even if the original player has left.
4. Your screen shows **Getting video… 37%** with a progress bar, then starts playing in sync with everyone else.

Every copy is checked against the full SHA-256 of the original before it's used, both on the server and on
your computer, so a damaged or swapped file is thrown away. Downloaded videos are kept in
`.minecraft/cushionscreen/downloads/` and reused next time.

You can still use your own copy instead: right-click → **Locate File…**, or drop it into
`.minecraft/cushionscreen/videos/`. A renamed or moved copy still matches; a re-encoded one doesn't.

Playback itself stays synchronized as before: the server keeps one shared timeline per screen on its own media
clock, every client syncs to that clock NTP-style and decodes the video locally. Every request is validated on the
server (reach, permissions, sane values, rate limits), and uploads are only accepted when the server asked for them.

### Server settings

`config/cushionscreen-server.json` (created on first start):

| Setting | Default | Meaning |
|---|---|---|
| `shareVideos` | `true` | Pass videos between players through the server |
| `maxVideoSizeMB` | `4096` | Largest video the server will pass along |
| `cacheSizeMB` | `16384` | Disk space for shared videos; least recently used are removed first |
| `maxConcurrentUploads` | `2` | Players uploading to the server at the same time |

## How it works

| Piece | Implementation |
|---|---|
| Decoding | FFmpeg 8.1 through JavaCPP (bundled natives: Windows x64, Linux x64, macOS x64/arm64). Separate demuxers for video and audio. Multithreaded decode, with hardware decoding (D3D11VA/DXVA2, VideoToolbox, VA-API/CUDA) when available and automatic software fallback. |
| Picture | One persistent GPU texture per playing screen, updated in place with `CommandEncoder.writeToTexture` (works on both OpenGL and Vulkan backends). Videos larger than 1920×1080 are scaled down to that size. |
| Sound | One OpenAL source per screen at the screen's centre, attenuated over a distance that grows with the screen. Stereo is kept where OpenAL Soft can spatialise it. Playback starts by trimming the decoded audio to the exact sample that is due, so it begins in step however long decoding took. The audio clock counts played samples (`AL_SAMPLE_OFFSET`) and is held to the shared timeline with small pitch corrections (at most ±5 %). If sound can't start (for example, every OpenAL voice is busy), it retries every 2 seconds. Volume follows the game's Master slider. Fullscreen switches to direct stereo. |
| Performance | Video is decoded only while the screen is in view, and audio only within hearing range. Players are disposed 3 s after they are no longer needed, and at most 6 decode at once (nearest first). Late frames are skipped before colour conversion, so low frame rates don't make the picture lag. |
| Polish | CRT-style power on/off, a fade-in on the first frame, a loading spinner, a NO SIGNAL screen, an on-screen display after every change, a playing indicator, a soft glow in the picture's average colour, real block light (level 8) while showing a video, and animated menus. |

Client options live in `config/cushionscreen-client.json`: maximum texture size, hardware decoding, maximum
simultaneous screens, view distance and audio range.

## Building

Requires JDK 25.

```bash
./gradlew :fabric:build :neoforge:build
```

Jars are written to `fabric/build/libs/` and `neoforge/build/libs/`. About 90 MB each, almost all of it FFmpeg
for four platforms. For a slim single-platform build:

```bash
./gradlew build -Pnatives=windows-x86_64
```

### Tests

```bash
./gradlew :fabric:test
```

51 unit tests: real decoding of MP4/WebM/MOV, frame-accurate seeking, sample-accurate audio seeks, keeping up at
5 FPS, real-time audio/video sync on OpenAL's silent device (start-up, catch-up, all voices busy), timeline maths,
wire formats and packet size limits, input validation, and screen geometry in all six orientations.

```bash
./gradlew :fabric:runClientGameTest
```

Launches the real game client. It builds a 16×9 wall, loads a test video over the network path, then seeks,
plays, reaches the end, opens the menu and fullscreen, powers the screen off and splits the wall. A second test shares a 6 MB video:
upload to the server, then download back after the local copy is deleted, both verified byte for byte. Each step
is checked with screenshots. Headless, it runs under Xvfb with Mesa:
`DISPLAY=:N LIBGL_ALWAYS_SOFTWARE=1 SDL_VIDEO_FORCE_EGL=1`.

### Layout

- `common/`: all game logic, shared by both loaders and compiled into each.
- `fabric/`, `neoforge/`: thin loader glue (registration, networking, events).
