// Read-only RAM diagnostic: totals, per-process usage (PSS), grouped by importance, plus low-memory kills.
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const sh = (c) => `${adb} shell "${c}"`
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD },
      on: [{ event: "/error:/i", break: false }],
      message: [
        `echo ===== TOTALS =====`,
        sh("dumpsys meminfo | grep -E 'Total RAM|Free RAM|Used RAM|Lost RAM|ZRAM'"),
        `echo ===== TOP PROCESSES BY MEMORY (PSS) =====`,
        sh("dumpsys meminfo | sed -n '/Total PSS by process/,/Total PSS by OOM/p' | head -n 40"),
        `echo ===== BY IMPORTANCE (what Android may kill first is at the bottom) =====`,
        sh("dumpsys meminfo | sed -n '/Total PSS by OOM adjustment/,/Total PSS by category/p' | grep -E '^ +[0-9,]+K: [A-Z]'"),
        `echo ===== LOW-MEMORY KILLS SINCE BOOT =====`,
        sh("logcat -d -b events | grep -c am_kill"),
        sh("logcat -d -b events -v time | grep am_kill | tail -n 8")
      ]
    }
  }]
}
