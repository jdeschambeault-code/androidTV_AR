// VLC on the pod: lets it scan local media. Android 11+ hides "All files access" on TV, so it is granted here
// (reversible in Android Settings → Apps → VLC → Permissions). Run with -- --check=1 to only report.
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const VLC = "org.videolan.vlc"
module.exports = {
  run: [{
    when: "{{!args.check}}",
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      on: [{ event: "/error:/i", break: false }],
      message: [
        `${adb} shell appops set ${VLC} MANAGE_EXTERNAL_STORAGE allow`,
        `${adb} shell pm grant ${VLC} android.permission.READ_EXTERNAL_STORAGE`,
        `${adb} shell pm grant ${VLC} android.permission.WRITE_EXTERNAL_STORAGE`
      ]
    }
  }, {
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [`(${adb} shell appops get ${VLC} MANAGE_EXTERNAL_STORAGE & ${adb} shell "dumpsys package ${VLC} | grep -E 'versionName|EXTERNAL_STORAGE'" & ${adb} shell ls /sdcard/Movies /sdcard/Download) > ../../recordings/vlc.txt 2>&1`]
    }
  }]
}
