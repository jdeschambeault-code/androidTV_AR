// AOW M5 air mouse / remotes: detect input devices, tune pointer speed ("velocity"), large AR-friendly pointer.
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
// All settings are reversible (pointer speed 0 = Android default; large pointer off).
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const sh = (c) => `${adb} shell "${c}"`
const CMDS = {
  detect: [
    `echo ===== Input devices (look for a Mouse / Air mouse / M5 / 2.4G receiver) =====`,
    sh("dumpsys input | grep -E '^    [0-9]+: |Sources:|KeyboardType' | grep -v -i -E 'virtual|gpio|cec|ir'"),
    `echo ===== Bluetooth devices bonded =====`,
    sh("dumpsys bluetooth_manager | grep -A1 -i -E 'Bonded devices' | head -n 20"),
    `echo ===== Current pointer settings =====`,
    sh("echo pointer_speed=$(settings get system pointer_speed) large_pointer=$(settings get secure accessibility_large_pointer_icon)")
  ],
  pair:   [sh("am start -n com.android.tv.settings/.accessories.AddAccessoryActivity || am start -a android.settings.BLUETOOTH_SETTINGS")],
  slow:   [sh("settings put system pointer_speed -3")],
  normal: [sh("settings put system pointer_speed 0")],
  fast:   [sh("settings put system pointer_speed 3")],
  faster: [sh("settings put system pointer_speed 5")],
  max:    [sh("settings put system pointer_speed 7")],
  large_on:  [sh("settings put secure accessibility_large_pointer_icon 1")],
  large_off: [sh("settings put secure accessibility_large_pointer_icon 0")]
}
module.exports = {
  run: [{
    when: "{{!(args && args.op)}}",
    method: "input",
    params: {
      title: "M5 remote / air mouse",
      description: "Pair the M5 first (Bluetooth: Settings → Remotes & Accessories → Add accessory; 2.4 GHz: plug the dongle into a USB-C OTG hub). Then tune the cursor here.",
      form: [{
        key: "op", type: "select", title: "Action", default: "detect",
        items: [
          { text: "Detect remotes and mice", value: "detect" },
          { text: "Open pairing screen on the pod (then put the M5 in pairing mode)", value: "pair" },
          { text: "Pointer speed: slow (-3)", value: "slow" },
          { text: "Pointer speed: normal (0, Android default)", value: "normal" },
          { text: "Pointer speed: fast (+3)", value: "fast" },
          { text: "Pointer speed: faster (+5)", value: "faster" },
          { text: "Pointer speed: max (+7)", value: "max" },
          { text: "Large pointer ON (easier to see in AR)", value: "large_on" },
          { text: "Large pointer OFF", value: "large_off" }
        ]
      }]
    }
  }, {
    method: "local.set",
    params: { op: "{{(args && args.op) || input.op}}" }
  },
  ...Object.entries(CMDS).map(([op, message]) => ({
    when: `{{local.op === '${op}'}}`,
    method: "shell.run",
    params: { path: "tools/scrcpy", env: { ANDROID_SERIAL: POD }, message, on: [{ event: "/error:/i", break: false }] }
  })),
  {
    method: "shell.run",
    params: { path: "tools/scrcpy", env: { ANDROID_SERIAL: POD }, message: [sh("echo now: pointer_speed=$(settings get system pointer_speed) large_pointer=$(settings get secure accessibility_large_pointer_icon)")] }
  }]
}
