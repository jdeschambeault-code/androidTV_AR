# TV Hub (Samsung Tizen)

A black screen with three big tiles: **TV**, **Inputs**, **Settings**. Use it instead of Samsung's Smart Hub navigation.

| Remote | Action |
|---|---|
| ◀ ▶ | move |
| OK | choose |
| Back | back to the three tiles (inside an input shown by the app, Back closes it) |
| RED | diagnostics: which methods work on this TV, sources found, Samsung app IDs |

## Tips

- **Name your inputs:** in Inputs, hold OK (or press YELLOW) on an HDMI tile. The last input used is marked, and the Inputs tile shows it.
- **Source list** opens Samsung's own list, which shows the device names it detected.
- **Switching straight to an HDMI port or to TV** needs **IP Remote** turned on: Settings → Connection → Network → Expert Settings → IP Remote → On. The first time, choose Allow on the "TV Hub" prompt.
- **Open on power-on:** Settings → General & Privacy → Start Screen Option → **Autorun Last App** → On. Leave TV Hub open when you turn the TV off.

## How it switches (fallback order)

Samsung restricts source switching and opening Settings to its partners, so each action tries these methods in order:

1. **Remote-control socket** on `127.0.0.1:8002` (wss) / `8001` (ws). The app sends real remote keys: `KEY_TV`, `KEY_HDMI1-4`, `KEY_MENU` and `KEY_SOURCE`. The first time it runs, the TV asks whether to **Allow "TV Hub"**. The token is then saved.
2. **`tizen.tvwindow`**: shows the source full screen *inside* the app.
3. **`tizen.application.launch`**: opens Samsung's Settings / Live TV app, found by name.

## Install on the TV (developer mode, one time)

1. On the PC, install **Tizen Studio**. In its Package Manager add **Samsung TV Extensions** and **Samsung Certificate Extension**.
2. On the TV, open **Apps** and press **1 2 3 4 5** on the remote. Turn **Developer mode ON**, enter the PC's IP, then restart the TV (hold Power until it reboots).
3. Connect the TV: `sdb connect <TV_IP>`
4. In Tizen Studio, open **Certificate Manager**. Create a **Samsung** certificate (TV), sign in with a Samsung account, and include the connected TV's DUID.
5. Package and install:
   ```
   tizen package -t wgt -s <certificate-profile> -- app/tizen-hub
   tizen install -n "TV Hub.wgt" -t <device-name-from-sdb-devices>
   ```

Desktop preview: open `index.html` in a browser. Arrows, Enter, Esc, and **R** for diagnostics.
