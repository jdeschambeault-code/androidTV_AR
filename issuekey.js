// Maintainer only: creates a support key for a sponsor and can post it on their GitHub issue. Needs your private key on this PC.
module.exports = {
  run: [{
    method: "input",
    params: {
      title: "Issue Support Key",
      description: "GitHub has no private messages: if you enter an issue number, anyone who can read that issue can see the key. Leave it empty to only show the key here.",
      form: [
        { key: "user", title: "Their GitHub username", required: true },
        { key: "issue", title: "Post it on issue number (optional, in Rokit-TV-Toolkit)" }
      ]
    }
  }, {
    method: "shell.run",
    params: {
      env: { SK_USER: "{{input.user}}", SK_ISSUE: "{{input.issue || ''}}" },
      message: "node scripts/send-key.js"
    }
  }]
}
