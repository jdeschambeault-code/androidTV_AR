// Walks Starshine's menus level by level with the arrows and Back and checks where the selection lands.
// It refuses to start unless Starshine is on screen. It only presses OK on tabs and menu rows that open a menu, and never on apps.
// Don't touch the remote while it runs. The result is written to nav-test-report.md.
module.exports = {
  run: [{
    method: "shell.run",
    params: {
      message: "node scripts/nav-test.js"
    }
  }]
}
