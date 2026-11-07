// Read-only: dump Jellyfin-client logcat + DNS state from the pod (login/session debugging).
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const sh = (c) => `${adb} shell "${c}"`
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [
        `echo ===== Jellyfin-related packages =====`,
        sh("pm list packages | grep -i -E 'jellyfin|moonfin|wholphin|kodi|findroid'"),
        `echo ===== DNS =====`,
        sh("dumpsys connectivity | grep -o -m 1 'DnsAddresses: \\[[^]]*\\]'"),
        `echo ===== Jellyfin logcat (errors/auth/http) =====`,
        sh("logcat -d -v time | grep -E 'jellyfin|moonfin|Unauthorized|UnknownHost|SSLHandshake|AndroidRuntime' | grep -v -E 'Items/Latest|NextUp|Resume|UserViews|KeepAlive|raw. message|^[^0-9]' | tail -n 120"),
        `echo ===== Enlarging log buffer to 8 MB so the next repro is kept (resets on reboot) =====`,
        sh("logcat -G 8M; logcat -g")
      ]
    }
  }]
}
