// Diagnose the Jellyfin connection FROM the pod (DNS, latency, Wi-Fi band/signal, Private DNS).
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
const adb = (process.platform === "win32" ? "" : "./") + "adb"
module.exports = {
  run: [{
    method: "input",
    params: {
      title: "Jellyfin connection check",
      form: [
        { key: "host", title: "Jellyfin hostname", default: require("./config").jellyfinHost || "jellyfin.local" },
        { key: "ip", title: "Jellyfin server LAN IP (optional, to compare DNS vs IP)", default: require("./config").jellyfinIp }
      ]
    }
  }, {
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [
        `echo ===== Private DNS (should be off or opportunistic for private names) =====`,
        `${adb} shell settings get global private_dns_mode`,
        `${adb} shell settings get global private_dns_specifier`,
        `echo ===== Name resolution + latency to {{input.host}} ("unknown host" = DNS problem) =====`,
        `${adb} shell ping -c 10 -i 0.3 {{input.host}}`,
        `{{input.ip ? '${adb} shell ping -c 10 -i 0.3 ' + input.ip : 'echo (no IP given, skipping direct-IP ping)'}}`,
        `echo ===== Wi-Fi link (RSSI above -65 and 5GHz frequency 5xxx wanted) =====`,
        `${adb} shell "dumpsys wifi | grep -m 6 -i -E 'mWifiInfo|RSSI:|Frequency'"`,

      ]
    }
  }]
}
