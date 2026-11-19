// One-time Samsung TV toolchain, all inside tools/ (nothing system-wide):
//   Tizen Studio CLI (sdb, tizen) + Samsung TV web-app extension + Samsung certificate manager.
// Quirks this works around (found the hard way):
//   - the CLI installer finishes ("Installation result : true") but never exits, so we watch its log and stop it;
//   - package-manager-cli.exe demands admin rights, so we run its .jar instead;
//   - that .jar needs Java 8 (JAXB), and must NOT run on Tizen's own jdk, because it updates that jdk while it's in use;
//   - it also updates itself, so we run a copy (tools/tizen-studio/pm-run; it finds the SDK from ../sdk.info);
//   - its download temp must be a fresh folder (tools/tizen-tmp);
//   - package install scripts are run as "Install.bat" from the current folder, which fails when
//     NoDefaultCurrentDirectoryInExePath is set, so we clear it.
const fs = require("fs")
const path = require("path")
const os = require("os")
const { spawn, execSync } = require("child_process")
const p = process.platform
const V = "6.1"
const INSTALLER = p === "win32" ? `web-cli_Tizen_Studio_${V}_windows-64.exe` : p === "darwin" ? `web-cli_Tizen_Studio_${V}_macos-64.bin` : `web-cli_Tizen_Studio_${V}_ubuntu-64.bin`
const java8 = p === "win32" ? "..\\..\\jdk8\\Library\\bin\\java.exe" : "../../jdk8/bin/java"   // relative to tools/tizen-studio/pm-run
const PKGS = "Certificate-Manager cert-add-on TV-SAMSUNG-Extension-Tools TV-SAMSUNG-Public-WebAppDevelopment"
const NO_CLI = "{{!exists('tools/tizen-studio/tools/ide/bin/tizen.bat') && !exists('tools/tizen-studio/tools/ide/bin/tizen')}}"

module.exports = {
  run: [{
    when: NO_CLI,
    method: "fs.download",
    params: { uri: `https://download.tizen.org/sdk/Installer/tizen-studio_${V}/${INSTALLER}`, dir: "tools/download" }
  }, {
    when: NO_CLI,
    method: async (req, ondata) => {
      const root = req.cwd
      const exe = path.join(root, "tools", "download", INSTALLER)
      const target = path.join(root, "tools", "tizen-studio")
      const logDir = p === "win32" ? path.join(process.env.LOCALAPPDATA || "", ".package-manager", "install-log", "installer")
                                   : path.join(os.homedir(), ".package-manager", "install-log", "installer")
      const before = new Set(fs.existsSync(logDir) ? fs.readdirSync(logDir) : [])
      if (p !== "win32") fs.chmodSync(exe, 0o755)
      ondata({ raw: `Installing Tizen Studio CLI into ${target} (about a minute)…\r\n` })
      // through the shell: spawning the .exe directly fails silently on Windows
      const child = spawn(`"${exe}" --accept-license "${target}"`, { cwd: root, stdio: "ignore", windowsHide: true, shell: true })
      let spawnError = null
      child.on("error", (e) => { spawnError = e })
      const t0 = Date.now()
      let ok = false
      while (Date.now() - t0 < 10 * 60 * 1000) {
        await new Promise((r) => setTimeout(r, 3000))
        if (spawnError) throw new Error("Couldn't start the Tizen installer: " + spawnError.message)
        if (child.exitCode !== null) { ok = fs.existsSync(path.join(target, "sdk.info")); break }
        const fresh = fs.existsSync(logDir) ? fs.readdirSync(logDir).filter((f) => !before.has(f)) : []
        const text = fresh.map((f) => fs.readFileSync(path.join(logDir, f), "utf8")).join("\n")
        if (/Installation result : true/.test(text)) { ok = true; break }
        if (/Installation result : false/.test(text)) break
      }
      await new Promise((r) => setTimeout(r, 4000))
      if (child.exitCode === null) {   // done, but the installer hangs instead of exiting
        try { p === "win32" ? execSync(`taskkill /PID ${child.pid} /T /F`, { stdio: "ignore" }) : child.kill("SIGKILL") } catch (e) {}
      }
      if (!ok) throw new Error("Tizen Studio CLI installation failed. See the installer log in " + logDir)
      ondata({ raw: "Tizen Studio CLI installed.\r\n" })
    }
  }, {
    method: "shell.run",
    params: { conda: { path: "tools/jdk8" }, message: "conda install -y -c conda-forge openjdk=8" }
  }, {
    // fresh copy of the package manager to run from, and a fresh temp folder
    method: async (req) => {
      const ts = path.join(req.cwd, "tools", "tizen-studio")
      fs.rmSync(path.join(ts, "pm-run"), { recursive: true, force: true })
      fs.cpSync(path.join(ts, "package-manager"), path.join(ts, "pm-run"), { recursive: true })
      fs.rmSync(path.join(req.cwd, "tools", "tizen-tmp"), { recursive: true, force: true })
      fs.mkdirSync(path.join(req.cwd, "tools", "tizen-tmp"), { recursive: true })
    }
  }, {
    method: "shell.run",
    params: {
      path: "tools/tizen-studio/pm-run",
      message: [
        p === "win32" ? "set NoDefaultCurrentDirectoryInExePath=" : "unset NoDefaultCurrentDirectoryInExePath",
        `${java8} "-Djava.io.tmpdir={{path.resolve(cwd, 'tools', 'tizen-tmp')}}" -jar PackageManagerV2-cli.jar install --accept-license ${PKGS}`
      ],
      on: [{ event: "/has been failed|Cannot find installed SDK|Exception in thread/i", break: true }]
    }
  }, {
    method: "fs.rm",
    params: { path: "tools/download" }
  }, {
    method: "notify",
    params: { html: "Tizen tools ready. Next: 'Samsung TV: Certificate'." }
  }]
}
