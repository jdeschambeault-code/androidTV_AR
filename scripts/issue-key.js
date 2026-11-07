// Maintainer tool: issues a support key for a sponsor.   node scripts/issue-key.js "Sponsor Name"
// The private key lives outside the repo, in ~/.starshine/support-key.pem, and must never be committed.
const crypto = require("crypto")
const fs = require("fs")
const path = require("path")
const os = require("os")
const name = process.argv[2]
if (!name || name.includes(".")) { console.error('Usage: node scripts/issue-key.js "Sponsor Name" (no dots in the name)'); process.exit(1) }
const pem = fs.readFileSync(path.join(os.homedir(), ".starshine", "support-key.pem"), "utf8")
const sig = crypto.sign("sha256", Buffer.from(name), crypto.createPrivateKey(pem))
console.log(name + "." + sig.toString("base64url"))
