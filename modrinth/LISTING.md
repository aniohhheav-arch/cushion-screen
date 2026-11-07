# Modrinth listing: what to fill in

Everything needed to create the Cushion Screen project on modrinth.com, field by field.

## Project

| Field | Value |
|---|---|
| Name | `Cushion Screen` |
| URL / slug | `cushion-screen` |
| Project type | Mod |
| Summary | `Build screens of any size from padded blocks and watch your own videos on them, with sound, in sync with everyone nearby. Only one player needs the file.` |
| Icon | `modrinth/icon.gif` (animated, 128×128, 96 KB; Modrinth's limit is 256 KB). Static fallback: `modrinth/icon.png` |
| Categories (primary) | Decoration, Social, Technology |
| Client side | Required |
| Server side | Required |
| License | MIT (see `LICENSE`; change it there and in the mod files if you prefer another) |
| Source code | https://github.com/aniohhheav-arch/cushion-screen (make the repository public first) |
| Issue tracker | https://github.com/aniohhheav-arch/cushion-screen/issues |

**Description:** paste the whole of `modrinth/description.md`.

## Gallery

Upload these from `modrinth/gallery/`:

| File | Title | Description |
|---|---|---|
| `1-playing.png` | Watching a video | A video playing on a wall of Cushion Screens |
| `2-no-signal.png` | Ready for a video | A new screen waiting for a video, in its padded frame |
| `3-controls.png` | Controls | The right-click menu: preview, seek slider, playback, volume and settings |
| `4-sharing.png` | Shared through the server | A player without the file gets it from the server automatically |

Tick **Featured** on `1-playing.png`.

## Versions

Create **two versions** with the same number, one per loader.

### Fabric

| Field | Value |
|---|---|
| File | `release/cushionscreen-fabric-26.3-1.1.1.jar` (all platforms) |
| Version name | `1.1.1 (Fabric)` |
| Version number | `1.1.1+fabric` |
| Release channel | Release |
| Loaders | Fabric |
| Game versions | 26.3 |
| Dependencies | Fabric API: **required** |

### NeoForge

| Field | Value |
|---|---|
| File | `release/cushionscreen-neoforge-26.3-1.1.1.jar` (all platforms) |
| Version name | `1.1.1 (NeoForge)` |
| Version number | `1.1.1+neoforge` |
| Release channel | Release |
| Loaders | NeoForge |
| Game versions | 26.3 |
| Dependencies | none |

### Changelog (same for both)

```markdown
**First public release.**

- Screens of any size from Cushion Screen blocks, on walls, floors and ceilings
- Play your own MP4, WebM, MOV and MKV videos with in-world sound
- Playback is synchronized for everyone on the server
- Only the player who loads a video needs the file: others get it through the server automatically
- Controls menu with seek slider, loop, fullscreen, per-player volume and owner settings
- Padded Screen Frame decoration block
```

## Before you submit

- Make the GitHub repository **public**, or leave the source link out. Modrinth's reviewers check that links work.
- Modrinth reviews new projects by hand. Mention in the review notes that the jar contains the FFmpeg native libraries (LGPL) used for video decoding, and that it downloads nothing at runtime.
- The NeoForge version compiles and packages but hasn't been run in a NeoForge game yet. Try it once before publishing it, or publish Fabric only to start.
