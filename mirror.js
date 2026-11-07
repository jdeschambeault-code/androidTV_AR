// Mirror + control the pod on the PC (mouse = air mouse, keyboard = remote).
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
// --force-adb-forward: reverse tunnel fails over Wi-Fi ADB on the RES102. Pod has only software encoders.
// Window closes -> script ends. Alt+H = Home, Alt+B = Back, Alt+S = app switch.
const scrcpy = (process.platform === "win32" ? "" : "./") + "scrcpy"
module.exports = {
  run: [{
    method: "input",
    params: {
      title: "Mirror Rokid Station",
      form: [{
        key: "mode", type: "select", title: "Mode", default: "view",
        items: [
          { text: "View + control", value: "view" },
          { text: "View + control + record to recordings/", value: "record" },
          { text: "Low latency (1280px, 30fps)", value: "lite" }
        ]
      }]
    }
  }, {
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [
        (process.platform === "win32" ? "if not exist ..\\..\\recordings mkdir ..\\..\\recordings" : "mkdir -p ../../recordings"),
        `${scrcpy} --window-title "Rokid Station" --stay-awake --no-audio --force-adb-forward ` +
        `{{input.mode === 'record' ? '--record=../../recordings/rokid-${Date.now()}.mp4' : ''}} ` +
        `{{input.mode === 'lite' ? '--max-size=1280 --max-fps=30 --video-bit-rate=4M' : ''}}`
      ]
    }
  }]
}
