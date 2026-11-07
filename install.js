// Downloads scrcpy (ships with adb) into ./tools/scrcpy for the current OS.
const V = "5.0.1"
const p = process.platform, a = process.arch
const asset =
  p === "win32"  ? (a === "arm64" ? `scrcpy-winarm64-v${V}.zip` : `scrcpy-win64-v${V}.zip`) :
  p === "darwin" ? (a === "arm64" ? `scrcpy-macos-aarch64-v${V}.tar.gz` : `scrcpy-macos-x86_64-v${V}.tar.gz`) :
                   `scrcpy-linux-x86_64-v${V}.tar.gz`

module.exports = {
  run: [{
    method: "fs.download",
    params: {
      uri: `https://github.com/Genymobile/scrcpy/releases/download/v${V}/${asset}`,
      dir: "tools/download"
    }
  }, {
    method: "shell.run",
    params: {
      message: [
        p === "win32" ? "if not exist tools\\scrcpy mkdir tools\\scrcpy" : "mkdir -p tools/scrcpy",
        // Windows: use the OS bsdtar (reads .zip); conda's GNU tar on PATH cannot
        `${p === "win32" ? "%SystemRoot%\\System32\\tar.exe" : "tar"} -xf tools/download/${asset} -C tools/scrcpy --strip-components=1`
      ]
    }
  }, {
    method: "fs.rm",
    params: { path: "tools/download" }
  }, {
    method: "shell.run",
    params: {
      path: "tools/scrcpy",
      message: [ (p === "win32" ? "" : "./") + "adb version" ]
    }
  }, {
    method: "notify",
    params: { html: "scrcpy + adb installed. Plug in the Rokid Station over USB or click Connect (Wi-Fi)." }
  }]
}
