// Connect to the Rokid Station over Wi-Fi ADB and print a device report.
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
const adb = (process.platform === "win32" ? "" : "./") + "adb"
module.exports = {
  run: [{
    method: "input",
    params: {
      title: "Connect to Rokid Station",
      description: "Find the IP in Settings > Network & Internet > (your Wi-Fi). If Wi-Fi ADB is refused, plug in USB once and tick 'Enable tcpip' — the pod will then accept Wi-Fi connections until reboot.",
      form: [
        { key: "host", title: "Pod IP address", default: POD.split(":")[0], required: true },
        { key: "tcpip", type: "checkbox", title: "USB cable connected? Enable tcpip 5555 first (leave OFF for Wi-Fi only)", default: false }
      ]
    }
  }, {
    // keep form values: `input` is replaced by every following step
    method: "local.set",
    params: { host: "{{input.host}}", tcpip: "{{input.tcpip}}" }
  }, {
    // every other script reads the pod's address from here
    method: "json.set",
    params: { "config.local.json": { pod: "{{local.host}}:5555" } }
  }, {
    when: "{{local.tcpip}}",
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: "{{local.host}}:5555" },
      message: `${adb} tcpip 5555`,
      // no USB device -> "error: no devices" is expected; keep going and try Wi-Fi anyway
      on: [{ event: "/error:/i", break: false }]
    }
  }, {
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: "{{local.host}}:5555" },
      message: [
        `${adb} connect {{local.host}}:5555`,
        `${adb} devices -l`,
        `${adb} shell getprop ro.build.fingerprint`,
        `${adb} shell getprop ro.product.model`,
        `${adb} shell getprop ro.build.version.release`,
        `${adb} shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME`,
        `${adb} shell pm list packages -3`
      ],
      // e.g. "error: device unauthorized" -> show the full report instead of halting
      on: [{ event: "/error:/i", break: false }]
    }
  }]
}
