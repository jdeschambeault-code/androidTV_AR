// Install one or more APKs on the pod (Songsterr, browsers, launchers, players...).
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
const adb = (process.platform === "win32" ? "" : "./") + "adb"
module.exports = {
  run: [{
    method: "filepicker.open",
    params: {
      title: "Pick APK file(s) to install on the Rokid Station",
      filetypes: [["Android packages", "*.apk"]],
      multiple: true
    }
  }, {
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: `{{input.paths.map(f => '${adb} install -r -g "' + f + '"').join(' && ')}}`
    }
  }]
}
