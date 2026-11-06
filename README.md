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

Video files **never** travel over the network, and neither do file paths.

- When you load a video, your client sends the server a small description: file name (without folders), size,
  duration, resolution, and a **fingerprint** (SHA-256 of the size plus three 4 MB samples of the file).
- The server keeps one **shared timeline** per screen (playing/paused, position, loop) on its own media clock.
  Every client syncs to that clock NTP-style and decodes the video locally, so everyone sees the same moment.
- Other players need **their own copy of the same file**. Their screen shows *Video not on this computer*
  until they either:
  - right-click → **Locate File…** and pick their copy (accepted only if the fingerprint matches), or
  - drop the file into `.minecraft/cushionscreen/videos/`, where it's found automatically.

  A copy that was renamed or moved still matches. A re-encoded or edited file does not.
- Every request is validated on the server: reach (64 blocks), permissions, sane values, and a rate limit.

## How it works

| Piece | Implementation |
|---|---|
| Decoding | FFmpeg 8.1 through JavaCPP (bundled natives: Windows x64, Linux x64, macOS x64/arm64). Separate demuxers for video and audio. Multithreaded decode, with hardware decoding (D3D11VA/DXVA2, VideoToolbox, VA-API/CUDA) when available and automatic software fallback. |
| Picture | One persistent GPU texture per playing screen, updated in place with `CommandEncoder.writeToTexture` (works on both OpenGL and Vulkan backends). Videos larger than 1920×1080 are scaled down to that size. |
| Sound | One OpenAL source per screen at the screen's centre, attenuated over a distance that grows with the screen. Stereo is kept where OpenAL Soft can spatialise it. The audio clock (`AL_SAMPLE_OFFSET`) is held to the shared timeline with tiny pitch corrections, and resyncs on large drift. Fullscreen switches to direct stereo. |
| Performance | Video is decoded only while the screen is in view, and audio only within hearing range. Players are disposed 3 s after they are no longer needed, and at most 6 decode at once (nearest first). Late frames are skipped before colour conversion, so low frame rates don't make the picture lag. |
| Polish | CRT-style power on/off, a fade-in on the first frame, a loading spinner, a NO SIGNAL screen, an on-screen display after every change, a playing indicator, a soft glow in the picture's average colour, real block light (level 8) while showing a video, and animated menus. |

Client options live in `config/cushionscreen-client.json`: maximum texture size, hardware decoding, maximum
simultaneous screens, view distance and audio range.

## Building

Requires JDK 25.

```bash
./gradlew :fabric:build :neoforge:build
```

Jars are written to `fabric/build/libs/` and `neoforge/build/libs/`. About 100 MB each, almost all of it FFmpeg
for four platforms. For a slim single-platform build:

```bash
./gradlew build -Pnatives=windows-x86_64
```

### Tests

```bash
./gradlew :fabric:test
```

46 unit tests: real decoding of MP4/WebM/MOV, frame-accurate seeking, sample-accurate audio seeks, keeping up at
5 FPS, timeline maths, wire formats, input validation, and screen geometry in all six orientations.

```bash
./gradlew :fabric:runClientGameTest
```

Launches the real game client. It builds a 16×9 wall, loads a test video over the network path, then seeks,
plays, reaches the end, opens the menu and fullscreen, powers the screen off and splits the wall. Each step is
checked with screenshots. Headless, it runs under Xvfb with Mesa:
`DISPLAY=:N LIBGL_ALWAYS_SOFTWARE=1 SDL_VIDEO_FORCE_EGL=1`.

### Layout

- `common/`: all game logic, shared by both loaders and compiled into each.
- `fabric/`, `neoforge/`: thin loader glue (registration, networking, events).
