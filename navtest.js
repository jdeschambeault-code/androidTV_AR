// Navigation test: opens Starshine, sends single remote key presses, screenshots each step to recordings/nav-<n>.png
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const STEPS = [
  ["start", null], ["up-to-home-tab", "DPAD_UP"], ["down-to-dock", "DPAD_DOWN"], ["up", "DPAD_UP"], ["r1", "DPAD_RIGHT"], ["r2", "DPAD_RIGHT"], ["r3-settings", "DPAD_RIGHT"],
  ["ok-tab", "DPAD_CENTER"], ["down", "DPAD_DOWN"], ["right-must-stay", "DPAD_RIGHT"], ["down2", "DPAD_DOWN"], ["ok-modal", "DPAD_CENTER"], ["right-must-stay2", "DPAD_RIGHT"], ["back", "BACK"]
]
const msg = [
  `${adb} shell am start -n com.starshine.launcher/.MainActivity`,
  `${adb} shell sleep 3`
]
STEPS.forEach(([name, key], i) => {
  if (key) msg.push(`${adb} shell input keyevent KEYCODE_${key}`, `${adb} shell sleep 1`)
  msg.push(`${adb} exec-out screencap -p > ../../recordings/nav-${i}-${name}.png`)
})
module.exports = {
  run: [{
    method: "shell.run",
    params: { message: process.platform === "win32" ? "if not exist recordings mkdir recordings" : "mkdir -p recordings" }
  }, {
    method: "shell.run",
    params: { path: "tools/scrcpy", env: { ANDROID_SERIAL: POD }, conda: { skip: true }, message: msg, on: [{ event: "/error:/i", break: false }] }
  }]
}
