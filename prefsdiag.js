// Read-only: Starshine's saved display/render settings and any overlay windows, to recordings/prefs.txt (for performance checks).
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const PKG = "com.starshine.launcher"
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [
        `(${adb} shell run-as ${PKG} cat shared_prefs/starshine.xml & echo ===== windows & ${adb} shell dumpsys SurfaceFlinger --list) > ../../recordings/prefs.txt 2>&1`
      ]
    }
  }]
}
