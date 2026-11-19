// Samsung TV helpers for the tizen-*.js scripts: find the TV on the LAN, read its developer-mode state,
// and read the active Tizen certificate profile.
const http = require("http")
const os = require("os")
const fs = require("fs")
const path = require("path")
const ROOT = path.resolve(__dirname, "..")
const LOCAL = path.join(ROOT, "config.local.json")

// Samsung TVs answer GET :8001/api/v2/ with { device: { OS: "Tizen", modelName, developerMode, developerIP, ... } }
function probe(ip) {
  return new Promise((done) => {
    const req = http.get({ host: ip, port: 8001, path: "/api/v2/", timeout: 1500 }, (res) => {
      let body = ""
      res.on("data", (d) => body += d)
      res.on("end", () => { try { const d = JSON.parse(body).device; done(d && d.OS === "Tizen" ? Object.assign({ ip }, d) : null) } catch (e) { done(null) } })
    })
    req.on("timeout", () => req.destroy())
    req.on("error", () => done(null))
  })
}

function pcAddresses() {
  return Object.values(os.networkInterfaces()).flat()
    .filter((a) => a && a.family === "IPv4" && !a.internal && !a.address.startsWith("169.254")).map((a) => a.address)
}

function readLocal() { try { return JSON.parse(fs.readFileSync(LOCAL, "utf8")) } catch (e) { return {} } }

async function find(say) {
  const local = readLocal()
  let tv = local.samsungTv ? await probe(local.samsungTv) : null
  if (!tv) {
    say(`Looking for the Samsung TV on the network…\r\n`)
    for (const pc of pcAddresses()) {
      const base = pc.split(".").slice(0, 3).join(".")
      const hits = (await Promise.all(Array.from({ length: 254 }, (_, i) => probe(`${base}.${i + 1}`)))).filter(Boolean)
      if (hits.length) { tv = hits[0]; break }
    }
    if (!tv) throw new Error("No Samsung TV found on the network. Is it on and on the same Wi-Fi as this PC?")
    local.samsungTv = tv.ip
    fs.writeFileSync(LOCAL, JSON.stringify(local, null, 2))
  }
  const sameNet = tv.ip.split(".").slice(0, 3).join(".")
  const pcIp = pcAddresses().find((a) => a.startsWith(sameNet + ".")) || pcAddresses()[0]
  const devMode = String(tv.developerMode) === "1"
  say(`Found ${(tv.name || "Samsung TV").replace(/&quot;/g, '"')} (${tv.modelName}) at ${tv.ip}. Developer mode: ${devMode ? "ON → " + tv.developerIP : "OFF"}\r\n`)
  return { ip: tv.ip, model: tv.modelName, pcIp, devMode, devIP: tv.developerIP }
}

// Stops the script with clear instructions unless developer mode points at this PC.
function requireDevMode(tv) {
  if (tv.devMode && tv.devIP === tv.pcIp) return
  const why = tv.devMode ? `Developer mode is ON but points to ${tv.devIP}, not this PC (${tv.pcIp}).` : "Developer mode is OFF on the TV."
  throw new Error(`${why}
Turn it on:
  1. Press Home, open "Apps", then open the Apps page's Settings (gear).
  2. Enter 1 2 3 4 5 there (on this TV it does nothing on the plain Apps page).
     No number keys? Use the remote's "123" button for the on-screen pad, or the
     remote in the SmartThings phone app.
  3. Switch Developer mode ON, Host PC IP = ${tv.pcIp}, OK.
  4. Restart the TV: hold Power until it switches off and on again.
Then run this again.`)
}

// The Tizen certificate profile marked active in Certificate Manager.
function activeProfile() {
  const f = path.join(ROOT, "tools", "tizen-studio-data", "profile", "profiles.xml")
  try { const m = fs.readFileSync(f, "utf8").match(/<profiles[^>]*active="([^"]+)"/); return m ? m[1] : null } catch (e) { return null }
}

module.exports = { find, requireDevMode, activeProfile, probe }
