// Reversible system tweaks via adb (no root). Each option can be undone with the matching "restore" choice.
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const CMDS = {
  dns_off:      [`${adb} shell settings put global private_dns_mode off`],
  dns_restore:  [`${adb} shell settings put global private_dns_mode opportunistic`],
  anim_fast:    [`${adb} shell settings put global window_animation_scale 0.5`, `${adb} shell settings put global transition_animation_scale 0.5`, `${adb} shell settings put global animator_duration_scale 0.5`],
  anim_restore: [`${adb} shell settings put global window_animation_scale 1`, `${adb} shell settings put global transition_animation_scale 1`, `${adb} shell settings put global animator_duration_scale 1`],
  home_starshine: [`${adb} shell cmd package set-home-activity com.starshine.launcher/.MainActivity`, `${adb} shell input keyevent KEYCODE_HOME`, `${adb} shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`],
  home_hard:      [`${adb} shell pm disable-user --user 0 com.google.android.tvlauncher`, `${adb} shell input keyevent KEYCODE_HOME`, `${adb} shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`],
  home_google:    [`${adb} shell pm enable com.google.android.tvlauncher`, `${adb} shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity`, `${adb} shell input keyevent KEYCODE_HOME`, `${adb} shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`],
  saver_on:     [`${adb} shell settings put secure screensaver_components com.starshine.launcher/.StarshineDream`, `${adb} shell settings put secure screensaver_enabled 1`, `${adb} shell settings get secure screensaver_components`],
  saver_test:   [`${adb} shell cmd dreams start-dreaming || ${adb} shell am start -n com.android.systemui/.Somnambulator`],
  launcher:     [`${adb} shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME`],
  screenshot:   [`${adb} exec-out screencap -p > ../../recordings/screenshot-${Date.now()}.png`]
}
module.exports = {
  run: [{
    when: "{{!(args && args.op)}}",
    method: "input",
    params: {
      title: "Rokid Station tweaks",
      form: [{
        key: "op", type: "select", title: "Action", default: "dns_off",
        items: [
          { text: "Jellyfin fix: turn Private DNS off (lets your router resolve private names)", value: "dns_off" },
          { text: "Restore Private DNS (automatic)", value: "dns_restore" },
          { text: "Snappier UI: animations 0.5x", value: "anim_fast" },
          { text: "Restore animations 1x", value: "anim_restore" },
          { text: "Home = Starshine launcher", value: "home_starshine" },
          { text: "Home = Starshine, strong: disable Google TV launcher (undo with the next option)", value: "home_hard" },
          { text: "Home = Google TV (re-enable + restore stock)", value: "home_google" },
          { text: "Screensaver = Starshine", value: "saver_on" },
          { text: "Start screensaver now (preview)", value: "saver_test" },
          { text: "List installed home launchers", value: "launcher" },
          { text: "Screenshot to recordings/", value: "screenshot" }
        ]
      }]
    }
  }, {
    method: "local.set",
    params: { op: "{{(args && args.op) || input.op}}" }
  }, {
    when: "{{local.op === 'screenshot'}}",
    method: "shell.run",
    params: { message: process.platform === "win32" ? "if not exist recordings mkdir recordings" : "mkdir -p recordings" }
  },
  // one guarded step per action; local.op decides which one runs
  ...Object.entries(CMDS).map(([op, message]) => ({
    when: `{{local.op === '${op}'}}`,
    method: "shell.run",
    params: { path: "tools/scrcpy", env: { ANDROID_SERIAL: POD }, message }
  }))]
}
