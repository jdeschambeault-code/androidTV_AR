// Read-only: saves Starshine's shader compile/link errors since the app started to recordings/shaderlog.txt.
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [`${adb} logcat -d -v time -s StarshineBg:E > ../../recordings/shaderlog.txt 2>&1`]
    }
  }]
}
