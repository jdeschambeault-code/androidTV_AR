// Read-only: saves the pod's recent crash reports for Starshine to recordings/crashlog.txt.
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const out = process.platform === "win32" ? "..\\..\\recordings\\crashlog.txt" : "../../recordings/crashlog.txt"
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [`${adb} logcat -d -b crash -v time > ${out} 2>&1`]
    }
  }]
}
