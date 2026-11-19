// Opens Starshine's Quick settings panel on the pod (launch extra, no key presses) and saves a screenshot to recordings/quick.png.
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [`(${adb} shell am start -n com.starshine.launcher/.MainActivity --ez quick true & ${adb} shell sleep 3 & ${adb} exec-out screencap -p > ../../recordings/quick.png) > ../../recordings/quick.txt 2>&1`]
    }
  }]
}
