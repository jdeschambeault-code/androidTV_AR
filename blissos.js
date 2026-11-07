// Optional: a Bliss OS VM reachable on ADB, set as "bliss" in config.local.json: check, mirror on the PC, or view it on the Rokid pod via Easycontrol.
const p = process.platform
const adb = (p === "win32" ? "" : "./") + "adb"
const scrcpy = (p === "win32" ? "" : "./") + "scrcpy"
const POD = require("./config").pod
const HOST = require("./config").bliss || "bliss-os.local:5555"
const EASY = "https://github.com/mingzhixian/Easycontrol/releases/download/10507/V1.5.7.apk"
module.exports = {
  run: [{
    when: "{{!(args && args.op)}}",
    method: "input",
    params: {
      title: "Bliss OS",
      description: "Bliss OS answers on ADB port 5555.",
      form: [{
        key: "op", type: "select", title: "Action", default: "check",
        items: [
          { text: "Check Bliss OS ADB connection (accept the prompt on Bliss OS the first time)", value: "check" },
          { text: "Mirror + control Bliss OS on this PC", value: "mirror" },
          { text: "Install Easycontrol on the Rokid pod (view Bliss OS in the glasses)", value: "easy" }
        ]
      }]
    }
  }, {
    method: "local.set",
    params: { op: "{{(args && args.op) || input.op}}" }
  }, {
    when: "{{local.op === 'check'}}",
    method: "shell.run",
    params: { path: "tools/scrcpy", conda: { skip: true }, on: [{ event: "/error:/i", break: false }],
      message: [`${adb} connect ${HOST}`, `${adb} devices -l`, `${adb} -s ${HOST} shell getprop ro.build.display.id`, `${adb} -s ${HOST} shell wm size`] }
  }, {
    when: "{{local.op === 'mirror'}}",
    method: "shell.run",
    params: { path: "tools/scrcpy", conda: { skip: true },
      message: [`${adb} connect ${HOST}`, `${scrcpy} -s ${HOST} --window-title "Bliss OS" --force-adb-forward --no-audio`] }
  }, {
    when: "{{local.op === 'easy'}}",
    method: "fs.download",
    params: { uri: EASY, dir: "apks" }
  }, {
    when: "{{local.op === 'easy'}}",
    method: "shell.run",
    params: { path: "tools/scrcpy", conda: { skip: true }, on: [{ event: "/error:/i", break: false }],
      message: [
        `${adb} -s ${POD} install -r -g ../../apks/V1.5.7.apk`,
        `${adb} -s ${POD} shell "pm list packages -3 | grep -i -E 'easy|control|saymzx'"`
      ] }
  }]
}
