// Build the Starshine launcher APK (app/starshine), install it on the connected pod, and grant its optional permissions.
// All grants are reversible in Android Settings (Apps / Accessibility / Notification access).
const POD = require("./config").pod
const p = process.platform
const gradle = p === "win32" ? "..\\..\\tools\\gradle-8.10.2\\bin\\gradle.bat" : "../../tools/gradle-8.10.2/bin/gradle"
const adb = p === "win32" ? "..\\..\\tools\\scrcpy\\adb" : "../../tools/scrcpy/adb"
const PKG = "com.starshine.launcher"
const KEYSVC = `${PKG}/${PKG}.KeyService`
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "app/starshine",
      conda: { path: "../../tools/jdk" },
      env: { ANDROID_HOME: "{{path.resolve(cwd, 'tools', 'android-sdk')}}", GRADLE_OPTS: "-Xmx2g -Dfile.encoding=UTF-8", ANDROID_SERIAL: POD },
      message: [`${gradle} assembleDebug --no-daemon --console=plain`],
      // a failed build ends the script here, so the install step below can never put an old APK back
      on: [{ event: "/FAILURE: Build failed|BUILD FAILED/", break: true }]
    }
  }, {
    method: "shell.run",
    params: {
      path: "app/starshine",
      env: { ANDROID_SERIAL: POD },
      message: [`(${adb} disconnect ${POD} || echo not-connected) && ${adb} connect ${POD} && ${adb} install -r app/build/outputs/apk/debug/app-debug.apk`],
      on: [{ event: "/Failure|device offline|failed to connect/", break: true }]
    }
  }, {
    method: "shell.run",
    params: {
      path: "app/starshine",
      env: { ANDROID_SERIAL: POD },
      on: [{ event: "/error:/i", break: false }],
      message: [
        `echo ===== granting optional permissions =====`,
        `${adb} shell pm grant ${PKG} android.permission.BLUETOOTH_CONNECT`,
        `${adb} shell pm grant ${PKG} android.permission.RECORD_AUDIO`,
        `${adb} shell pm grant ${PKG} android.permission.READ_EXTERNAL_STORAGE`,
        `${adb} shell pm grant ${PKG} android.permission.ACCESS_FINE_LOCATION`,
        `${adb} shell appops set ${PKG} MANAGE_EXTERNAL_STORAGE allow`,
        `${adb} shell appops set ${PKG} GET_USAGE_STATS allow`,
        `${adb} shell appops set ${PKG} WRITE_SETTINGS allow`,
        `${adb} shell cmd notification allow_listener ${PKG}/${PKG}.MediaListener`,
        // compile ahead of time, so sound and menus run at full speed from the first launch
        `${adb} shell cmd package compile -m speed -f ${PKG}`,
        // append the long-press-Home service to any existing accessibility services (no-op if already there)
        `${adb} shell "cur=$(settings get secure enabled_accessibility_services); case $cur in *KeyService*) ;; null|'') settings put secure enabled_accessibility_services ${KEYSVC} ;; *) settings put secure enabled_accessibility_services $cur:${KEYSVC} ;; esac; settings put secure accessibility_enabled 1; settings get secure enabled_accessibility_services"`
      ]
    }
  }, {
    method: "notify",
    params: { html: "Starshine updated on the pod (open it from the app list, or Tweaks → 'Home = Starshine')." }
  }]
}
