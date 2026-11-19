// Samsung TV, step 2: connect to the TV (developer mode) and open Samsung's Certificate Manager
// to create the signing certificate that retail Samsung TVs require (tied to your Samsung account + this TV).
const { spawn } = require("child_process")
const path = require("path")
const tv = require("./scripts/samsung-tv")
const sdb = process.platform === "win32" ? "tools\\tizen-studio\\tools\\sdb.exe" : "tools/tizen-studio/tools/sdb"
const certManager = process.platform === "win32" ? "certificate-manager.exe" : "certificate-manager"

module.exports = {
  run: [{
    method: async (req, ondata) => {
      const t = await tv.find((s) => ondata({ raw: s }))
      tv.requireDevMode(t)
      return t
    }
  }, {
    method: "local.set",
    params: { tv: "{{input.ip}}" }
  }, {
    method: "shell.run",
    params: {
      message: [`${sdb} connect {{local.tv}}`, `${sdb} devices`],
      on: [{ event: "/failed to connect|unable to connect/i", break: true }]
    }
  }, {
    method: async (req) => {
      const dir = path.join(req.cwd, "tools", "tizen-studio", "tools", "certificate-manager")
      spawn(path.join(dir, certManager), [], { cwd: dir, detached: true, stdio: "ignore" }).unref()
    }
  }, {
    method: "log",
    params: {
      raw: [
        "",
        "Certificate Manager is opening (it can take ~20 s). In it:",
        "  1. Click  +  (Create a new certificate profile) → choose  Samsung  → Next.",
        "  2. Device type:  TV  → Next.",
        "  3. Profile name:  starshine  → Next.",
        "  4. Author certificate: Create a new author certificate → name + password → Next",
        "     → Sign in with Samsung account (a browser window opens) → Next.",
        "  5. Distributor certificate: keep 'Public' privilege; the TV's DUID is already listed",
        "     (it is connected) → Next → Finish.",
        "  6. Make sure the 'starshine' profile is the active one (checkmark), then close the window.",
        "",
        "Then click 'Samsung TV: Install TV Hub'.",
        ""
      ].join("\r\n")
    }
  }]
}
