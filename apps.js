// Download recommended apps from their OFFICIAL release channels and install them on the pod.
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
// RES102 is 32-bit only (armeabi-v7a), so ABI-specific builds use v7a.
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const APPS = [
  { name: "SmartTube 32.68",    url: "https://github.com/yuliskov/SmartTube/releases/download/32.68s/SmartTube_stable_32.68_armeabi-v7a.apk" },
  { name: "Moonlight 12.2",     url: "https://github.com/moonlight-stream/moonlight-android/releases/download/v12.2/app-nonRoot-release.apk" },
  { name: "Aurora Store 4.8.4", url: "https://f-droid.org/repo/com.aurora.store_76.apk" },
  { name: "Firefox 159.0",      url: "https://archive.mozilla.org/pub/fenix/releases/159.0/android/fenix-159.0-android-armeabi-v7a/fenix-159.0.multi.android-armeabi-v7a.apk" }
]
const file = (u) => u.split("/").pop()
module.exports = {
  run: [{
    method: "fs.download",
    params: { uri: APPS.map(a => a.url), dir: "apks" }
  }, {
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: APPS.flatMap(a => [`echo ===== ${a.name} =====`, `${adb} install -r -g "../../apks/${file(a.url)}"`]),
      on: [{ event: "/error:/i", break: false }]
    }
  }, {
    method: "notify",
    params: { html: "SmartTube, Moonlight, Aurora Store and Firefox installed. Open Aurora Store (anonymous login) to get Songsterr." }
  }]
}
