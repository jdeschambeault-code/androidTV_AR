// Read-only: saves the pod's audio volume policy (fixed / full-volume outputs, per-device music index) to recordings/audiodiag.txt.
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const out = "../../recordings/audiodiag.txt"
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [`${adb} shell "dumpsys audio | grep -i -E 'FixedVolume|FullVolume|AbsVolume|Current:|STREAM_MUSIC:' -A3 | head -40" > ${out} 2>&1`]
    }
  }]
}
