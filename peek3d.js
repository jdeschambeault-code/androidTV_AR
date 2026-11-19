// Shows a 3D scene full screen on the pod (a preview: the applied background is not changed), takes a screenshot to
// recordings/peek3d.png and saves any shader errors to recordings/peek3d.txt. Any button on the remote returns.
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const PKG = "com.starshine.launcher"
const win = process.platform === "win32"
const txt = win ? "..\\..\\recordings\\peek3d.txt" : "../../recordings/peek3d.txt"
const png = win ? "..\\..\\recordings\\peek3d.png" : "../../recordings/peek3d.png"
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [
        `(${adb} logcat -c & ${adb} shell am start -n ${PKG}/.MainActivity --es bgPeek {{args.scene || 'Galaxy'}} &${adb} shell sleep 8 & ` +
        `${adb} shell screencap -p /sdcard/peek3d.png & ${adb} pull /sdcard/peek3d.png ${png} & ${adb} shell rm /sdcard/peek3d.png & ` +
        `echo ===== shader and crash errors ===== & ${adb} logcat -d -v time -s StarshineBg:E AndroidRuntime:E) > ${txt} 2>&1`
      ]
    }
  }]
}
