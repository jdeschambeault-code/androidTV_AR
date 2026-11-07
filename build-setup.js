// One-time Android build toolchain: JDK 17 (conda env), Gradle 8.10.2, Android SDK (platform 35 + build-tools 35).
const p = process.platform
const os = p === "win32" ? "win" : p === "darwin" ? "mac" : "linux"
const CLT = `commandlinetools-${os}-11076708_latest.zip`
const tar = p === "win32" ? "%SystemRoot%\\System32\\tar.exe" : "tar"   // conda's GNU tar can't read .zip on Windows
const sdkmanager = p === "win32" ? "tools\\android-sdk\\cmdline-tools\\latest\\bin\\sdkmanager.bat" : "tools/android-sdk/cmdline-tools/latest/bin/sdkmanager"
const mk = (d) => p === "win32" ? `if not exist ${d.replace(/\//g, "\\")} mkdir ${d.replace(/\//g, "\\")}` : `mkdir -p ${d}`

module.exports = {
  run: [{
    method: "shell.run",
    params: { conda: { path: "tools/jdk" }, message: "conda install -y -c conda-forge openjdk=17" }
  }, {
    method: "fs.download",
    params: {
      uri: ["https://services.gradle.org/distributions/gradle-8.10.2-bin.zip", `https://dl.google.com/android/repository/${CLT}`],
      dir: "tools/download"
    }
  }, {
    method: "fs.write",
    params: { path: "tools/download/yes.txt", text: "y\n".repeat(30) }
  }, {
    method: "shell.run",
    params: {
      message: [
        `${tar} -xf tools/download/gradle-8.10.2-bin.zip -C tools`,
        mk("tools/android-sdk/cmdline-tools/latest"),
        `${tar} -xf tools/download/${CLT} -C tools/android-sdk/cmdline-tools/latest --strip-components=1`
      ]
    }
  }, {
    // JAVA_HOME is set by activating the openjdk conda env
    method: "shell.run",
    params: {
      conda: { path: "tools/jdk" },
      message: [
        `${sdkmanager} --sdk_root=tools/android-sdk --licenses < tools/download/yes.txt`,
        `${sdkmanager} --sdk_root=tools/android-sdk "platforms;android-35" "build-tools;35.0.0"`
      ]
    }
  }, {
    method: "fs.rm",
    params: { path: "tools/download" }
  }, {
    method: "notify",
    params: { html: "Android build tools ready. Click 'Build & Install Starshine'." }
  }]
}
