// Read-only: screenshot of the pod to recordings/latest.png + Starshine crash check.
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
const p = process.platform
const adb = (p === "win32" ? "" : "./") + "adb"
module.exports = {
  run: [{
    method: "shell.run",
    params: { message: p === "win32" ? "if not exist recordings mkdir recordings" : "mkdir -p recordings" }
  }, {
    // pterm start snap.js -- --open=1  -> bring Starshine to front first
    when: "{{args && args.open}}",
    method: "shell.run",
    params: { path: "tools/scrcpy", env: { ANDROID_SERIAL: POD }, message: [`${adb} shell am start -n com.starshine.launcher/.MainActivity`, `${adb} shell sleep 3`] }
  }, {
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [
        `${adb} exec-out screencap -p > ../../recordings/latest.png`,
        `${adb} shell "pidof com.starshine.launcher || echo STARSHINE NOT RUNNING"`,
        `${adb} shell "logcat -d -b crash -v brief | tail -n 25"`,
        `${adb} shell "logcat -d -v brief | grep -E 'starshine|AndroidRuntime' | grep -E ' E/|FATAL' | tail -n 15"`
      ],
      on: [{ event: "/error:/i", break: false }]
    }
  }]
}
