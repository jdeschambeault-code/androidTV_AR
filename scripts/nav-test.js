// Hierarchy test for Starshine's navigation.
//
// The rule it checks: Top menu > tab > panel > item > setting. Back goes up exactly one level and lands on what you came from.
// Arrows stay inside their level and loop at its ends. Each branch below is one level of that tree. A branch starts
// from the Home tab, and a failure ends only that branch.
//
// Steps: [key, expected focus] or { until: key, expect, max }. Expected focus is a regular expression matched against the
// id Starshine logs when the selection moves. Keys: L R U D ok hold back.
const { spawn, spawnSync } = require("child_process")
const fs = require("fs")
const path = require("path")

const ROOT = path.join(__dirname, "..")
const POD = require(path.join(ROOT, "config")).pod
const ADB = path.join(ROOT, "tools", "scrcpy", process.platform === "win32" ? "adb.exe" : "adb")
const KEYS = { L: "21", R: "22", U: "19", D: "20", ok: "23", back: "4" }
const SETTLE = 750
const sleep = ms => new Promise(r => setTimeout(r, ms))
const adb = (...a) => spawnSync(ADB, ["-s", POD, ...a], { encoding: "utf8", timeout: 15000 })

let focus = ""
// Anything this test didn't send (a remote, the air mouse, a click in the Mirror window) makes the results meaningless,
// and the next OK could act on the wrong item, so the test stops at once.
let sent = 0, seen = 0, outside = ""
function press(k) {
  if (outside) return sleep(0)
  sent++
  if (k === "hold") adb("shell", "input", "keyevent", "--longpress", KEYS.ok)
  else adb("shell", "input", "keyevent", KEYS[k])
  return sleep(SETTLE)
}

const tab = t => new RegExp("^tab:" + t + "$")
const toTab = t => ({ until: "R", expect: tab(t), max: 8 })

// Into the Backgrounds list: tab, then the Backgrounds / Moods tabs, then the list.
const bgRows = [toTab("Bg"), ["ok", tab("Bg")], ["D", /^sub:/], { until: "L", expect: /^sub:0$/, max: 3 }, ["ok", /^sub:0$/], ["D", /^row:bgt:apply$/]]

const CATS = ["Display & position", "Theme", "Drone", "Files", "Sound", "Voice setup", "M5 air mouse", "Gestures", "Web & Jellyfin", "Screensaver", "Dock", "System", "About & exit"]
const exact = s => new RegExp("^" + s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "$")
const settingsSteps = []
CATS.forEach((c, i) => {
  if (i > 0) {
    if (i % 4 === 0) { settingsSteps.push(["L", /^chip:cat:/], ["L", /^chip:cat:/], ["L", /^chip:cat:/], ["D", exact("chip:cat:" + c)]) }
    else settingsSteps.push(["R", exact("chip:cat:" + c)])
  }
  settingsSteps.push(["ok", /^(?!chip:cat:|chip:q:|tab:)/], ["back", exact("chip:cat:" + c)])
})

const TREE = [
  { name: "Top menu: left and right loop", steps: [
    ["L", /^clock$/], ["L", /^pill:apps$/], ["R", /^clock$/], ["R", tab("Home")] ] },
  { name: "Top menu > quick panel: Back returns to the clock", steps: [
    ["L", /^clock$/], ["ok", /q:wifi/], ["back", /^clock$/] ] },
  { name: "Home > dock: loops, Back goes up", steps: [
    ["D", /^tile:/], { until: "L", expect: /^tile:\+add$/, max: 30 }, ["R", /^tile:(?!\+add)/], ["back", tab("Home")], ["U", /^tile:/], ["back", tab("Home")] ] },
  { name: "Apps > grid: Back goes to the Apps icon, then Home", steps: [
    toTab("Apps"), ["ok", tab("Apps")], ["D", /^tile:/], ["back", tab("Apps")], ["back", tab("Home")] ] },
  { name: "Generator > Backgrounds list: loops, Back goes up", steps: [
    ...bgRows, ["back", tab("Bg")], ["back", tab("Home")] ] },
  { name: "Generator > scene > hold OK menu: Back returns to the scene", steps: [
    ...bgRows, { seek: /^row:sec:g:Elements$/ }, ["D", /^row:scene:/],
    ["hold", /^row:im:a0$/], ["back", /^row:scene:/], ["R", /^row:scene:/], ["L", /^row:scene:/], ["U", /Elements/], ["back", tab("Bg")], ["back", tab("Home")] ] },
  { name: "Generator > scene builder > layer > settings, and all the way back", steps: [
    ...bgRows, { seek: /^row:bgt:build$/ }, ["ok", /^ltile:/],
    ["R", /^ltile:/], ["L", /^ltile:/], ["L", /^ltile:/],
    ["U", /^adj:Layer$/], ["D", /^adj:Preview$/], ["D", /^row:sec:Basics$/], ["D", /^(row:|adj:)/], ["D", /^(row:|adj:)/], ["D", /^(row:|adj:)/],
    ["back", /^ltile:/], ["ok", /^adj:Layer$/], ["back", /^ltile:/], ["R", /^ltile:/], ["U", /^adj:Layer$/], ["back", /^ltile:/],
    ["back", /^row:ed:apply$/], ["back", /^row:bgt:build$/], ["back", tab("Bg")], ["back", tab("Home")] ] },
  { name: "Scene builder > Add layer > icons: stays in the grid, Back closes it", steps: [
    ...bgRows, { seek: /^row:bgt:build$/ }, ["ok", /^ltile:/], ["back", /^row:ed:apply$/],
    { until: "D", expect: /^row:ed:addmenu$/, max: 5 }, ["ok", /^row:ed:addmenu$/], ["D", /^row:gh:/], ["ok", /^row:gh:/], ["D", /^gen:/],
    ["R", /^gen:/], ["L", /^gen:/], ["L", /^gen:/], ["back", /^row:ed:addmenu$/], ["back", /^row:bgt:build$/], ["back", tab("Bg")], ["back", tab("Home")] ] },
  { name: "Generator > Moods builder: Back goes up", steps: [
    toTab("Bg"), ["ok", tab("Bg")], ["D", /^sub:/], { until: "R", expect: /^sub:1$/, max: 3 }, ["ok", /^sub:1$/], ["D", /^row:mt:apply$/], { until: "D", expect: /^row:mt:build$/, max: 6 }, ["ok", /^(mtile:|row:md2:play$)/],
    { until: "back", expect: /^row:mt:build$/, max: 4 }, ["back", tab("Bg")], ["D", /^sub:1$/], { until: "L", expect: /^sub:0$/, max: 3 }, ["ok", /^sub:0$/], ["back", tab("Bg")], ["back", tab("Home")] ] },
  { name: "Meditation > journey > step: Back goes up one level at a time", steps: [
    toTab("Med"), ["ok", tab("Med")], ["D", /^adj:Journey$/], ["back", tab("Med")], ["D", /^adj:Journey$/], { until: "D", expect: /^row:md:st:0$/, max: 6 }, ["ok", /^adj:Scene$/], ["back", /^row:md:st:0$/], ["back", tab("Med")], ["back", tab("Home")] ] },
  { name: "Quick edit > every setting: Back returns to its tile, then the clock", steps: [
    ["L", /^clock$/], ["ok", /q:wifi/], { until: "D", expect: /^chip:cat:Display & position$/, max: 10 }, ...settingsSteps, ["back", /^clock$/], ["back", tab("Home")] ] }
]

async function reset() {
  for (let i = 0; i < 8 && focus !== "tab:Home"; i++) await press("back")
  return focus === "tab:Home"
}

async function main() {
  const front = adb("shell", "dumpsys", "window").stdout || ""
  if (!/mCurrentFocus=.*com\.starshine\.launcher/.test(front)) {
    console.log("Starshine isn't on screen on the pod. Open it, then run this again. Nothing was pressed.")
    return
  }
  adb("logcat", "-c")
  const log = spawn(ADB, ["-s", POD, "logcat", "-v", "raw", "-s", "StarshineInput:I"])
  let tail = ""
  log.stdout.on("data", d => {
    tail += d; const lines = tail.split(/\r?\n/); tail = lines.pop()
    for (const l of lines) {
      const m = l.match(/^focus (.+)$/); if (m) focus = m[1]
      const key = l.match(/^raw key (\S+) scan=(\d+) from (.*)$/)
      if (key) {
        seen++
        if (key[3] !== "Virtual" || key[2] !== "0") outside = outside || key[1] + " from " + key[3]
        else if (seen > sent) outside = outside || "an extra " + key[1]
      }
      if (/^raw click/.test(l)) outside = outside || "a click on the screen"
    }
  })
  await sleep(700)
  // whatever is selected now gets logged by moving away and back
  await press("back")
  if (!focus) { await press("back"); await press("U") }
  const report = ["# Starshine navigation test", "", new Date().toString(), ""]
  let pass = 0, fail = 0
  for (const b of TREE) {
    if (outside) break
    console.log("\n" + b.name)
    if (!(await reset())) {
      await press("U"); await reset()
      if (focus !== "tab:Home") { fail++; report.push("- ✗ **" + b.name + "**: couldn't get back to the Home tab (selection on " + (focus || "nothing") + ")"); console.log("  can't reach Home"); continue }
    }
    const lines = []; let ok = true
    for (const s of b.steps) {
      if (outside) { ok = false; break }
      let label, good
      if (s.seek) {
        // the row may be above or below the remembered one: up to the top of the list, then down
        label = "seek " + s.seek
        good = s.seek.test(focus)
        for (let i = 0; i < 40 && !good && !/^(sub:|tab:)/.test(focus); i++) { await press("U"); good = s.seek.test(focus) }
        for (let i = 0; i < 60 && !good; i++) { await press("D"); good = s.seek.test(focus) }
      } else if (s.until) {
        label = "repeat " + s.until + " to " + s.expect
        good = s.expect.test(focus)
        for (let i = 0; i < s.max && !good; i++) { await press(s.until); good = s.expect.test(focus) }
      } else {
        const [k, want] = s
        label = k + " → " + want
        await press(k)
        // an unsaved draft reopens its builder the first time the tab opens: that's a feature, so close it and carry on
        if (k === "ok" && String(want) === String(tab("Bg")) && /^(ltile:|mtile:|row:ed:|row:md2:)/.test(focus)) {
          console.log("  note  a draft reopened its builder; closing it")
          for (let i = 0; i < 5 && focus !== "tab:Bg"; i++) await press("back")
        }
        good = want.test(focus) && focus !== "none"
      }
      lines.push("  - " + (good ? "✓" : "✗") + " `" + label + "` landed on `" + focus + "`")
      console.log((good ? "  ok    " : "  FAIL  ") + label + "  landed on " + focus)
      if (!good) { ok = false; break }
    }
    if (ok) pass++; else fail++
    report.push("- " + (ok ? "✓" : "✗") + " **" + b.name + "**", ...(ok ? [] : lines))
  }
  if (!outside) await reset()
  log.kill()
  const stopped = outside ? "**Stopped early: " + outside + " came from outside the test, so these results don't count.** " : ""
  if (outside) console.log("\nSTOPPED: " + outside + " came from outside the test")
  report.splice(3, 0, stopped + `**${pass} branches passed, ${fail} failed.**`, "")
  fs.writeFileSync(path.join(ROOT, "nav-test-report.md"), report.join("\n") + "\n")
  console.log(`\n${pass} branches passed, ${fail} failed. See nav-test-report.md`)
}
main()
