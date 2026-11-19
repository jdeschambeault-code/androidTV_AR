// Read-only: lists the pod's installed packages that matter for Starshine's tiles and player, to recordings/packages.txt.
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [`${adb} shell pm list packages > ../../recordings/packages.txt 2>&1`]
    }
  }]
}
