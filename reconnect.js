// Reconnects adb to the pod after a Wi-Fi drop ("device offline").
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
module.exports = { run: [{ method: "shell.run", params: { path: "tools/scrcpy", message: [`${adb} disconnect ${POD}`, `${adb} connect ${POD}`, `${adb} devices`] } }] }
