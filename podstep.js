// One step on the pod, then a screenshot to recordings/pod.png: pterm start podstep.js -- --cmd="input keyevent 23"
// (always one step at a time, checking the screenshot before the next).
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
module.exports = {
  run: [{
    when: "{{args.cmd}}",
    method: "shell.run",
    params: { path: "tools/scrcpy", env: { ANDROID_SERIAL: POD }, message: [`${adb} shell {{args.cmd}}`] }
  }, {
    method: "shell.run",
    params: { path: "tools/scrcpy", env: { ANDROID_SERIAL: POD }, message: [`${adb} shell sleep {{args.wait || 3}}`, `${adb} exec-out screencap -p > ../../recordings/pod.png`] }
  }]
}
