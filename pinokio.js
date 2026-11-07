module.exports = {
  version: "7.0",
  title: "Rokid TV Toolkit",
  description: "Mirror, sideload, diagnose and redesign the Rokid Station (Android TV 12) from your PC",
  menu: async (kernel, info) => {
    const installed = info.exists("tools/scrcpy")
    const running = {
      install: info.running("install.js"),
      mirror: info.running("mirror.js"),
    }
    const preview = { icon: "fa-solid fa-star", text: "Launcher Prototype", href: "prototype/index.html?raw=true" }
    const extra = info.exists("PLAN.md") ? [{ icon: "fa-solid fa-map", text: "Plan", href: "PLAN.md?raw=true" }] : []
    const help = { icon: "fa-solid fa-circle-question", text: "Help", href: "HELP.md?raw=true" }

    if (running.install) {
      return [{ default: true, icon: "fa-solid fa-plug", text: "Installing", href: "install.js" }]
    }
    if (!installed) {
      return [
        { default: true, icon: "fa-solid fa-plug", text: "Install (adb + scrcpy)", href: "install.js" },
        help, preview, ...extra
      ]
    }
    return [
      { default: !running.mirror, icon: "fa-solid fa-wifi", text: "Connect (Wi-Fi ADB)", href: "connect.js" },
      { default: running.mirror, icon: "fa-solid fa-display", text: running.mirror ? "Mirroring" : "Mirror on PC", href: "mirror.js" },
      { icon: "fa-solid fa-clipboard-list", text: "Device Report", href: "report.js" },
      { icon: "fa-solid fa-memory", text: "RAM Report", href: "ram.js" },
      { icon: "fa-solid fa-xmark", text: "Force Close Apps", href: "forceclose.js" },
      { icon: "fa-solid fa-sitemap", text: "Navigation Test", href: "navtest.js" },
      { icon: "fa-brands fa-usb", text: "USB Driver Doctor", href: "usb.js" },
      { icon: "fa-solid fa-box-open", text: "Sideload APK", href: "sideload.js" },
      { icon: "fa-solid fa-film", text: "Send Video to Pod", href: "sendvideo.js" },
      { icon: "fa-solid fa-store", text: "Install Recommended Apps", href: "apps.js" },
      { icon: "fa-solid fa-stethoscope", text: "Jellyfin Net Check", href: "netcheck.js" },
      { icon: "fa-solid fa-file-lines", text: "Jellyfin App Log", href: "jflog.js" },
      { icon: "fa-solid fa-computer-mouse", text: "M5 Remote / Pointer", href: "remote.js" },
      { icon: "fa-solid fa-microphone", text: "Voice Setup", href: "voice.js" },
      ...(require("./config").bliss ? [{ icon: "fa-solid fa-display", text: "Bliss OS", href: "blissos.js" }] : []),
      { icon: "fa-solid fa-star", text: "Get NASA Nebula Photos", href: "nebulae.js" },
      { icon: "fa-solid fa-sliders", text: "Tweaks", href: "tweaks.js" },
      { icon: "fa-solid fa-heart", text: "Enter Support Key", href: "supportkey.js" },
      // maintainer only: appears on the PC that holds the private signing key
      ...(require("fs").existsSync(require("path").join(require("os").homedir(), ".starshine", "support-key.pem")) ? [{ icon: "fa-solid fa-key", text: "Issue Support Key", href: "issuekey.js" }] : []),
      info.exists("tools/android-sdk/build-tools")
        ? { icon: "fa-solid fa-hammer", text: info.running("build.js") ? "Building Starshine…" : "Build & Install Starshine", href: "build.js" }
        : { icon: "fa-solid fa-toolbox", text: info.running("build-setup.js") ? "Installing build tools…" : "Install APK Build Tools", href: "build-setup.js" },
      help, preview, ...extra,
      { icon: "fa-solid fa-rotate", text: "Update", href: "update.js" },
      { icon: "fa-regular fa-circle-xmark", text: "Reset", href: "reset.js" }
    ]
  }
}
