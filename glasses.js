// Read-only: what does the pod know about the Rokid glasses? (motion sensors / IMU, USB devices, display)
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const sh = (c) => `${adb} shell "${c}"`
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      path: "tools/scrcpy", env: { ANDROID_SERIAL: POD }, conda: { skip: true },
      on: [{ event: "/error:/i", break: false }],
      message: [
        `echo ===== Android sensors (looking for gyroscope / rotation vector / head tracking) =====`,
        sh("dumpsys sensorservice | grep -i -E '^[0-9a-fx]+\\)|handle|gyro|rotation|accel|magnet|rokid|hmd' | head -n 30"),
        `echo ===== USB devices (glasses IMU usually appears as a USB HID device) =====`,
        sh("dumpsys usb | grep -i -E 'device_name|product|manufacturer|vendor|rokid|hid' | head -n 30"),
        sh("ls /dev/hidraw* /dev/input/ 2>&1 | head -n 20"),
        `echo ===== Displays =====`,
        sh("dumpsys display | grep -E 'mDisplayId|uniqueId|PhysicalDisplayInfo|DisplayDeviceInfo' | head -n 10"),
        `echo ===== Rokid services =====`,
        sh("dumpsys activity services com.android.hmdservice | head -n 15")
      ]
    }
  }]
}
