// Read-only: saves Starshine's recent selection and key log from the pod to recordings/focuslog.txt (for tracing menu bugs).
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const out = process.platform === "win32" ? "..\\..\\recordings\\focuslog.txt" : "../../recordings/focuslog.txt"
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [`${adb} logcat -d -v time -s StarshineInput:I > ${out} 2>&1`]
    }
  }]
}
