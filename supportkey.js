// Enter a support key on the pod from the PC, so it doesn't have to be typed with the remote.
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
const adb = (process.platform === "win32" ? "" : "./") + "adb"
module.exports = {
  run: [{
    method: "input",
    params: {
      title: "Enter Support Key",
      description: "Paste the key you received after sponsoring. It removes the watermarks.",
      form: [{ key: "key", title: "Support key", required: true }]
    }
  }, {
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: `${adb} connect ${POD} && ${adb} shell am start -n com.starshine.launcher/.MainActivity --es supportKey "{{input.key}}"`
    }
  }]
}
