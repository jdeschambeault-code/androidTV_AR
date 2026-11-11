// Maintainer tool behind Pinokio's "Issue Support Key". Reads SK_USER and SK_ISSUE from the environment.
// Always prints the key. With an issue number it also posts the key as a comment there, which anyone who can read the issue can see.
const crypto = require("crypto")
const fs = require("fs")
const path = require("path")
const os = require("os")
const { spawnSync } = require("child_process")
const REPO = "jdeschambeault-code/Rokit-TV-Toolkit"
const user = (process.env.SK_USER || "").trim().replace(/^@/, "")
const issue = (process.env.SK_ISSUE || "").trim()
// GitHub usernames: letters, digits and single hyphens
if (!/^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$/.test(user)) { console.error("That doesn't look like a GitHub username: " + user); process.exit(1) }
if (issue && !/^\d+$/.test(issue)) { console.error("The issue must be a number: " + issue); process.exit(1) }
const pem = fs.readFileSync(path.join(os.homedir(), ".starshine", "support-key.pem"), "utf8")
const key = user + "." + crypto.sign("sha256", Buffer.from(user), crypto.createPrivateKey(pem)).toString("base64url")
console.log("\n===== Support key for " + user + " =====\n" + key + "\n")
if (!issue) { console.log("Not posted. Send it to them yourself."); process.exit(0) }
const body = `@${user} thank you for supporting Starshine! Here is your support key:\n\n\`\`\`\n${key}\n\`\`\`\n\nEnter it in Starshine under Settings → About & exit, or from a PC:\n\n\`\`\`\nadb shell am start -n com.starshine.launcher/.MainActivity --es supportKey "${key}"\n\`\`\``
const r = spawnSync("gh", ["issue", "comment", issue, "--repo", REPO, "--body", body], { encoding: "utf8", shell: false })
if (r.error || r.status !== 0) { console.error("Couldn't post it. Run 'gh auth login' first if needed. The key above is still valid.\n" + (r.stderr || r.error)); process.exit(1) }
console.log("Posted: " + r.stdout.trim())
