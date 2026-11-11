// Fully closes every app you installed (not system services, not Starshine), using adb's force-stop.
// Nothing is uninstalled and no data is removed; apps reopen normally the next time you start them.
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const cmd = "grep -E 'MemAvailable|SwapFree' /proc/meminfo; for p in $(pm list packages -3 | cut -d: -f2); do if [ \\\"$p\\\" != com.starshine.launcher ]; then am force-stop $p; echo closed $p; fi; done; sleep 3; grep -E 'MemAvailable|SwapFree' /proc/meminfo"
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: `${adb} connect ${POD} && ${adb} shell "${cmd}"`
    }
  }]
}
