# Starshine: to do and to test

Last updated: 2026-11-19 (version 2.7.0, installed on the pod at 19:15).

## Open: to do

1. **YouTube Music returns to full screen after a while.** Starshine itself reopens the app (seen 4 times between 19:01 and 19:05), and the log doesn't show which part yet.
   - Suspect: the ▶ in Quick settings. YouTube Music refuses Starshine's remote commands, so ▶ opens the app, presses play and comes back; when the app starts slowly, it stays in front.
   - Next: read `recordings/ytm-front.txt` (a log watcher was left running) or the `StarshineLaunch` / `StarshinePlayer` log lines, then fix that path.
2. **Publish the repo and release:** run `! /c/pinokio/bin/miniforge/Scripts/gh.exe auth login`, then make the repo public and create release v2.7.0 with `apks/release/starshine-2.7.0.apk`. That APK was built with `STARSHINE_PUBLIC=1`, so it carries no personal tiles or addresses.
   - Rebuild it first if code changed since. The release notes should mention the LeanKeyboard tip for the M5.
3. **Landscape photos for public users:** they aren't in the repo (`recordings/` is ignored). Decide whether to commit them, since they're openly licensed (credits in ATTRIBUTION.md), or attach them to the release as a separate zip.
4. **TV Streamer:** install the new build once it's online. It wasn't reachable at its usual address today.
5. **Render settings, global vs per scene** (agreed approach, not built yet): device settings (antialiasing, resolution, FPS) stay global; creative effects (bloom, grain, colour grade…) can be saved with a scene as its own "look" that overrides the global one while it plays.
6. **Video landscape loops:** after downloading the clips into `recordings/bg-review/video/raw/`, run `makeloops.sh`, push them with **Landscape backgrounds**, and add video Landscape scenes.
7. **Demo in Settings:** recordable, 15–30 s per section (2D/3D scenes, moods, Meditation builder, other features). Not started.
8. **Same controls on every player card** (BUILT 2026-11-20, see "To test"; installed on the TV Streamer, not yet on the pod) (Quick settings player block, left-edge card, Home Now Playing card):
   - Album art of the current track, ⏮ Back, ⏯ Play/Pause, ⏭ Forward.
   - A drop-down to choose what plays, filled in this order of preference:
     1. The player's library (playlists), where it agrees to share it. YouTube Music refuses Starshine (tested 2026-11-19); Spotify and others still to test with `--es browseTest <package>`.
     2. Otherwise the session's current queue (Android's media queue): pick a track to jump to it.
     3. Otherwise a "Choose in <app>" button that opens the app.
   - Build it as one shared card component, so the three cards can't drift apart again.
9. **Remove the `browseTest` hook and the `StarshineLaunch` stack logging** once item 1 is solved, if they're no longer useful.

## To test on the pod (built and installed, not yet checked on screen)

| Change | What to check |
|---|---|
| Landscapes no longer turn | Browse the Landscapes group for a minute or more: mist, light rays and stars stay still; only the gentle sway and flicker move. |
| Spin doesn't carry over | Browse from a spinning geometry scene straight to a photo or landscape: the photo starts upright. |
| Image pivot | Photo layer → Pivot (Centre, the 4 corners, top, bottom, left, right). Change Rotate Z and Size: the picture turns and grows from that point. |
| Long-press preview (Meditation) | Hold OK on a journey in the right column: the preview window shows that journey's first scene and plays its sound. Close the menu: the selected journey comes back. |
| Dock magnify | Move along the dock: the enlarged circle grows upward and never hides the name under it, or the floating name above it. |
| Antialiasing | Render tab → Edges → Antialiasing at 50–100% on a line or geometry scene: edges look smoother. Check the frame rate stays acceptable. |
| Picture sharpness | With render effects on, scenes look as sharp as with effects off (the effects input is no longer shrunk below 960 wide). |
| Meditation split layout | Preview doesn't run under the Journeys column. → from the left menu goes to the selected journey, ← on the first icon comes back to Play. Switching Layout keeps the selection on the Layout row. |
| BG/MOODS entry | Opens on the scene list, not inside an editor; Back goes to the BG/MOODS tab, then HOME. |
| Bamboo draft | The unsaved "Arashiyama bamboo" draft: check no stray Mandelbrot layer was left in it. |
| CT 102 tile | Opens the Bliss OS VM's screen (noVNC, scaled to fit) after logging in once through the C64 tile. |
| M5 typing | With LeanKeyboard active, a mouse click on a letter types it, and D-pad + OK still types the highlighted key. |
| Songsterr app | Opens; Restore purchase / sign in brings Premium back. The dock tile now opens the app instead of the website. |
| Player cards (all three) | Quick settings block, left-edge card and Home Now Playing card each show the track's picture, ⏮ ⏯ ⏭ and ☰. ☰ drops down a list: a player's playlists if it shares them (OK opens a folder, OK on a track plays it), otherwise its queue (OK jumps to that track), always ending with "Choose in <app>". Back closes the list or goes up a folder. |
| YouTube Music (phone app) | Plays in the background with Premium; the Quick settings player and Now Playing card control it. |

## Done today

- Songsterr 7.2.4 (Android 7+ build) and YouTube Music 9.46.51 (Android 9+ build) installed; both Play builds need Android 12L.
- SmartTube's current package name used everywhere.
- LeanKeyboard installed and active.
- CT 102 tile points at the Bliss OS VM console.
- BG/MOODS no longer drops into an editor on entry.
- Up from Meditation no longer jumps to HOME after a layout switch.
- Picture quality restored.
- Landscape overlay spin removed.
- Image pivot, long-press preview, dock magnify, antialiasing.
- Committed and pushed as 2.7.0 (`0a21f3e`).
