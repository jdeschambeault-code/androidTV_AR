// One-click, read-only device report (no prompts). Uses the already-connected pod.
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const sh = (c) => `${adb} shell "${c}"`
const hdr = (t) => `echo ===== ${t} =====`
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [
        `${adb} devices -l`,
        hdr("System"),
        sh("getprop ro.build.fingerprint; getprop ro.build.version.sdk; wm size; wm density"),
        hdr("Home launchers (current default marked by resolve-activity)"),
        sh("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME"),
        sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"),
        hdr("Browsers that can open http links"),
        sh("cmd package query-activities --brief -a android.intent.action.VIEW -d http://example.com"),
        hdr("User-installed apps"),
        sh("pm list packages -3"),
        hdr("DNS / Private DNS"),
        sh("settings get global private_dns_mode; settings get global private_dns_specifier"),
        sh("dumpsys connectivity | grep -m 3 -i -E 'DnsAddresses|dns servers'"),
        hdr("Wi-Fi link"),
        sh("dumpsys wifi | grep -m 3 -E 'mWifiInfo'"),
        hdr("Jellyfin by name, then by IP"),
        ...(require("./config").jellyfinHost ? [sh("ping -c 4 -W 2 " + require("./config").jellyfinHost)] : []),
        ...(require("./config").jellyfinIp ? [sh("ping -c 4 -W 2 " + require("./config").jellyfinIp)] : []),
        hdr("Input devices (remotes)"),
        sh("dumpsys input | grep -E '^ +[0-9]+: ' ")
      ],
      on: [{ event: "/error:/i", break: false }]
    }
  }]
}
