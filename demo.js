// Records Starshine's demo tour (Settings → About → Demo, about 2½ minutes): starts the tour, records the screen while it
// plays, then saves recordings/demo/starshine-demo.mp4 and the section start times (chapters.txt).
// Another device than the pod: pterm start demo.js -- --serial=192.168.0.142:5555
const POD = require("./config").pod
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const SERIAL = `{{args.serial || '${POD}'}}`
module.exports = {
  run: [{
    method: "fs.write",
    params: { path: "recordings/demo/README.txt", text: "Starshine demo recordings: starshine-demo.mp4 and its chapters (chapters.txt).\n" }
  }, {
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: SERIAL },
      message: [
        `${adb} logcat -c`,
        // the tour waits 2.5 s before its first section, so the recording below starts in time
        `${adb} shell am start -n com.starshine.launcher/.MainActivity --ez demo true`,
        // in the foreground until its time limit, so the file is closed properly (the tour lasts about 158 s)
        `${adb} shell screenrecord --time-limit 170 /sdcard/starshine-demo.mp4`,
        `${adb} pull /sdcard/starshine-demo.mp4 ../../recordings/demo/starshine-demo.mp4`,
        `${adb} shell rm /sdcard/starshine-demo.mp4`,
        `${adb} logcat -d -v time -s StarshineDemo:I > ../../recordings/demo/chapters.txt`
      ]
    }
  }]
}
