// Copy videos or images from this PC to the pod's Movies/Starshine folder, where Starshine's Files menu and VLC can see them.
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const DEST = "/sdcard/Movies/Starshine/"
module.exports = {
  run: [{
    method: "filepicker.open",
    params: {
      title: "Pick videos or images to send to the Rokid Station",
      filetypes: [["Video and images", "*.mp4 *.mkv *.webm *.mov *.gif *.webp *.png *.jpg *.jpeg"]],
      multiple: true
    }
  }, {
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      message: `${adb} connect ${POD} && ${adb} shell mkdir -p ${DEST} && {{input.paths.map(f => '${adb} push "' + f + '" ${DEST}').join(' && ')}} && ${adb} shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://${DEST}`
    }
  }, {
    method: "notify",
    params: { html: "Sent. On the pod: Settings → Files → Starshine videos." }
  }]
}
