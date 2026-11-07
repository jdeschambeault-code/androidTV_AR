// Windows only: diagnose why the Rokid Station is not seen over USB, optionally install the Google ADB driver.
// NOTE: "Device Descriptor Request Failed" (VID_0000) is an electrical/handshake failure -> fix cable/port, a driver cannot help.
const ps = (cmd) => `powershell -NoProfile -Command "${cmd}"`
module.exports = {
  run: [{
    when: "{{platform !== 'win32'}}",
    method: "notify",
    params: { html: "USB driver tooling is only needed on Windows. macOS/Linux use adb without drivers." }
  }, {
    when: "{{platform === 'win32'}}",
    method: "shell.run",
    params: {
      message: [
        "echo ===== Android / Rokid / failing USB devices =====",
        ps("Get-PnpDevice -PresentOnly | Where-Object { $_.Status -ne 'OK' -or $_.FriendlyName -match 'ADB|Android|Rokid|Composite ADB' } | Format-Table Status,Class,FriendlyName,InstanceId -AutoSize | Out-String -Width 220"),
        "echo.",
        "echo HOW TO READ THIS:",
        "echo  - VID_0000 + 'Descriptor Request Failed' = cable/port problem: use a DATA USB-C cable, a rear motherboard port, and the pod's DATA/OTG port (not the glasses port).",
        "echo  - 'ADB Interface' with Error / yellow icon = driver problem: run this script again and choose to install the Google driver.",
        "echo  - Nothing at all = pod USB is host-only / charging-only. Skip USB and use Connect (Wi-Fi ADB)."
      ]
    }
  }, {
    when: "{{platform === 'win32'}}",
    method: "input",
    params: {
      title: "Install Google ADB USB driver?",
      description: "Only useful if the list showed an 'ADB Interface' / Android device with an error. Requires admin (UAC prompt).",
      form: [{ key: "install", type: "checkbox", title: "Download + install Google USB driver r13", default: false }]
    }
  }, {
    when: "{{platform === 'win32'}}",
    method: "local.set",
    params: { install: "{{input.install}}" }
  }, {
    when: "{{platform === 'win32' && local.install}}",
    method: "fs.download",
    params: { uri: "https://dl.google.com/android/repository/usb_driver_r13-windows.zip", dir: "tools/download" }
  }, {
    when: "{{platform === 'win32' && local.install}}",
    method: "shell.run",
    params: {
      message: [
        "tar -xf tools/download/usb_driver_r13-windows.zip -C tools",
        "pnputil /add-driver tools\\usb_driver\\android_winusb.inf /install"
      ],
      sudo: true
    }
  }]
}
