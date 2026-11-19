// Installs one APK from apks/ on the pod: pterm start installapk.js -- --apk=SmartTube_stable_32.71_universal.apk
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [`${adb} install -r ../../apks/{{args.apk}}`],
      on: [{ event: "/Failure|failed/", break: true }]
    }
  }]
}
