// Samsung TV, step 3: build, sign (active certificate profile), install and launch TV Hub (app/tizen-hub) on the TV.
const tv = require("./scripts/samsung-tv")
const win = process.platform === "win32"
const sdb = win ? "tools\\tizen-studio\\tools\\sdb.exe" : "tools/tizen-studio/tools/sdb"
const tizen = win ? "tools\\tizen-studio\\tools\\ide\\bin\\tizen.bat" : "tools/tizen-studio/tools/ide/bin/tizen"
const APP_ID = "SSTVHub001.TVHub"
const OUT = "app/tizen-hub/.buildResult"

module.exports = {
  run: [{
    method: async (req, ondata) => {
      const t = await tv.find((s) => ondata({ raw: s }))
      tv.requireDevMode(t)
      const profile = tv.activeProfile()
      if (!profile) throw new Error("No certificate profile yet. Run 'Samsung TV: Certificate' first.")
      ondata({ raw: `Signing with certificate profile "${profile}".\r\n` })
      return Object.assign(t, { profile })
    }
  }, {
    method: "local.set",
    params: { tv: "{{input.ip}}", profile: "{{input.profile}}" }
  }, {
    method: "shell.run",
    params: {
      message: [
        win ? "set NoDefaultCurrentDirectoryInExePath=" : "unset NoDefaultCurrentDirectoryInExePath",
        `${sdb} connect {{local.tv}}`,
        `${tizen} build-web -- app/tizen-hub`,
        `${tizen} package -t wgt -s {{local.profile}} -- ${OUT}`
      ],
      on: [{ event: "/failed to connect|Failed to|\\[ERROR\\]|Error:/i", break: true }]
    }
  }, {
    // the package is named after the app ("TV Hub.wgt"); the space breaks the install transfer
    method: async (req) => {
      const fs = require("fs"), path = require("path")
      const dir = path.join(req.cwd, OUT)
      const wgt = fs.readdirSync(dir).find((f) => f.endsWith(".wgt") && f !== "TVHub.wgt")
      if (wgt) fs.renameSync(path.join(dir, wgt), path.join(dir, "TVHub.wgt"))
    }
  }, {
    method: "shell.run",
    params: {
      message: [
        win ? "set NoDefaultCurrentDirectoryInExePath=" : "unset NoDefaultCurrentDirectoryInExePath",
        `${sdb} connect {{local.tv}}`,
        `${tizen} install -n TVHub.wgt -s {{local.tv}}:26101 -- ${OUT}`,
        `${tizen} run -p ${APP_ID} -s {{local.tv}}:26101`
      ],
      on: [{ event: "/Fail install|install failed|Failed to|\\[Fail\\]/i", break: true }]
    }
  }, {
    method: "notify",
    params: { html: "TV Hub is installed and opening on the Samsung TV. Press RED on the remote for diagnostics." }
  }]
}
