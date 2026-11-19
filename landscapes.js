// Copies the landscape photos and video loops staged in recordings/landscapes/ to the pod, where Starshine's
// Landscapes scenes find them (Android/data/com.starshine.launcher/files/landscapes). Safe to run again: files are replaced.
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const DIR = "/sdcard/Android/data/com.starshine.launcher/files/landscapes"
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [`${adb} shell mkdir -p ${DIR}`, `${adb} push ../../recordings/landscapes/. ${DIR}/`, `${adb} shell ls -la ${DIR}`]
    }
  }]
}
