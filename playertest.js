// Player test: with -- --pkg=<package>, chooses that player and presses ▶ the way Quick settings does; then (always) saves
// the media sessions' states, the app in front and Starshine's player log to recordings/playertest.txt.
// Usage: pterm start playertest.js -- --pkg=com.google.android.youtube.tvmusic --wait=12   (no --pkg = report only)
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
module.exports = {
  run: [{
    method: "shell.run",
    params: { path: "tools/scrcpy", env: { ANDROID_SERIAL: POD }, message: [`${adb} logcat -c`] }
  }, {
    when: "{{args.pkg}}",
    method: "shell.run",
    params: { path: "tools/scrcpy", env: { ANDROID_SERIAL: POD }, message: [`${adb} shell am start -n com.starshine.launcher/.MainActivity --es playerTest {{args.pkg}}`] }
  }, {
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: [
        `(${adb} shell sleep {{args.wait || 12}} & echo ===== sessions & ${adb} shell "dumpsys media_session | grep -E 'package=|state=PlaybackState'" & ` +
        `echo ===== in front & ${adb} shell "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -3" & ` +
        `echo ===== player log & ${adb} logcat -d -v time -s StarshinePlayer:I) > ../../recordings/playertest.txt 2>&1`
      ]
    }
  }]
}
