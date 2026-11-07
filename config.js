// Toolkit settings. Your own values go in config.local.json (never committed); "Connect" saves the pod address there.
const fs = require("fs")
const path = require("path")
let local = {}
try { local = JSON.parse(fs.readFileSync(path.join(__dirname, "config.local.json"), "utf8")) } catch (e) {}
module.exports = {
  pod: local.pod || "192.168.1.50:5555",   // the pod's adb address
  bliss: local.bliss || "",                // optional Bliss OS VM on adb, "host:5555"
  jellyfinHost: local.jellyfinHost || "",  // optional, for the network checks
  jellyfinIp: local.jellyfinIp || ""
}
