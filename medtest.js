// Meditation test on the pod: plays the first journey that has several steps, shortened (each step at 12% of its time),
// with Starshine's audio test running. Then saves the audio-test report and the background log to recordings/medtest.txt.
// It takes over the pod's screen and sound for about 2.5 minutes. Read-only otherwise: nothing is changed or deleted.
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const PKG = "com.starshine.launcher"
const out = process.platform === "win32" ? "..\\..\\recordings\\medtest.txt" : "../../recordings/medtest.txt"
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      // one message, so the whole result lands in one file
      message: [
        `(${adb} logcat -c & ${adb} shell am start -n ${PKG}/.MainActivity --es journeyTest first --ef journeyScale {{args.scale || 0.12}} {{args.nofx ? '--ez noFx true' : ''}} & ${adb} shell sleep 150 & ` +
        `echo ===== audio test report ===== & ${adb} shell cat /sdcard/Android/data/${PKG}/files/audiotest/report.txt & echo ===== second by second ===== & ${adb} shell cat /sdcard/Android/data/${PKG}/files/audiotest/seconds.csv & ` +
        `echo ===== background changes and errors ===== & ${adb} logcat -d -v time -s StarshineMed:I StarshineBg:E AndroidRuntime:E & ` +
        `echo ===== screen restarts ===== & ${adb} logcat -d -v time -s StarshineInput:I | findstr /C:"screen created") > ${out} 2>&1`
      ]
    }
  }]
}
