// Voice setup for Starshine: grants the microphone permission and reports the recognizer state.
// Reversible: Android Settings -> Apps -> Starshine -> Permissions. It does not change the system's default recognizer.
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const report = [
  "echo == recognizers installed; cmd package query-services -a android.speech.RecognitionService | grep -E 'packageName=' | sort -u",
  "echo == system default; settings get secure voice_recognition_service",
  "echo == Starshine permission; dumpsys package com.starshine.launcher | grep -E 'RECORD_AUDIO: granted'",
  "echo == last voice attempts; logcat -d -v time -s StarshineInput | grep -E 'listen with|ready|error|result' | tail -n 15"
].join("; ")
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      on: [{ event: "/error:/i", break: false }],
      message: `${adb} shell "pm grant com.starshine.launcher android.permission.RECORD_AUDIO; ${report}"`
    }
  }, {
    method: "notify",
    params: { html: "Voice setup done. On the pod, test it in Starshine → Settings → Voice setup." }
  }]
}
