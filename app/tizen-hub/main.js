/* TV Hub: a black screen with three choices (TV, Inputs, Settings) for Samsung Tizen TVs.
 *
 * Samsung keeps "switch source" and "open Settings" to its partners, so each action
 * tries several methods in order and uses the first one that works:
 *   1. The TV's own remote-control socket on 127.0.0.1 (the same one phone remotes use).
 *      It sends real remote keys (KEY_TV, KEY_HDMI1, KEY_MENU). The TV asks "Allow?" once.
 *   2. Tizen APIs: tvwindow shows the source full screen inside this app, and
 *      application.launch opens Samsung's Settings / Live TV app if it's allowed.
 * Press the RED button to see which methods work on this TV (diagnostics).
 */
(function () {
  "use strict";
  var hasTizen = typeof tizen !== "undefined";
  var KEY = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 10009, RED: 403, YELLOW: 405, ESC: 27, BKSP: 8 };
  var log = [];
  function note(s) { log.push(new Date().toLocaleTimeString() + "  " + s); if (log.length > 60) log.shift(); console.log(s); }
  function status(s) { document.getElementById("status").textContent = s || ""; }
  function store(k, v) { try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { return null; } }

  /* ---------- icons ---------- */
  var ICON = {
    tv: '<svg viewBox="0 0 24 24"><rect x="2" y="5" width="20" height="13" rx="2"/><path d="M8 21h8M12 18v3"/></svg>',
    input: '<svg viewBox="0 0 24 24"><path d="M3 12h11M10 8l4 4-4 4"/><path d="M14 4h5a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-5"/></svg>',
    gear: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>',
    hdmi: '<svg viewBox="0 0 24 24"><path d="M3 8h18v5l-3 3H6l-3-3z"/><path d="M7 11h10"/></svg>',
    apps: '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg>',
    more: '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="19" cy="12" r="1.5"/></svg>'
  };

  /* ---------- method 1: the TV's remote-control socket ---------- */
  var Remote = {
    ws: null, ready: false, failed: false, queue: [],
    urls: function () {
      var name = btoa("TV Hub"), tok = store("tvhub.token");
      var path = "/api/v2/channels/samsung.remote.control?name=" + name;
      return ["ws://127.0.0.1:8001" + path, "wss://127.0.0.1:8002" + path + (tok ? "&token=" + tok : "")];
    },
    connect: function (i) {
      var self = this, urls = this.urls(); i = i || 0;
      if (i >= urls.length) { self.failed = true; note("remote socket: unavailable"); self.flush(false); return; }
      var ws, timer, opened = false;
      try { ws = new WebSocket(urls[i]); } catch (e) { note("remote socket " + i + " threw " + e); return self.connect(i + 1); }
      // first pairing waits for "Allow" on screen, so give it time
      // (with IP Remote off, port 8001 accepts the connection and then stays silent)
      timer = setTimeout(function () { if (!self.ready) { note("remote socket " + i + ": timeout"); status(""); try { ws.close(); } catch (e) {} } }, 30000);
      ws.onopen = function () { opened = true; note("remote socket " + i + ": open, waiting for pairing"); status("If the TV asks to allow \"TV Hub\", choose Allow."); };
      ws.onmessage = function (m) {
        var d; try { d = JSON.parse(m.data); } catch (e) { return; }
        if (d.event === "ms.channel.connect") {
          clearTimeout(timer); self.ws = ws; self.ready = true; status("");
          if (d.data && d.data.token) store("tvhub.token", d.data.token);
          note("remote socket " + i + ": paired"); self.flush(true);
        } else if (d.event === "ms.channel.unauthorized") { note("remote socket: refused on TV"); status("Remote access was refused on the TV."); }
      };
      ws.onclose = function () {
        clearTimeout(timer);
        if (self.ws === ws) { self.ws = null; self.ready = false; self.connecting = false; note("remote socket closed"); return; }
        if (!self.ready) self.connect(i + 1);
      };
      ws.onerror = function () { note("remote socket " + i + ": error" + (opened ? "" : " (not reachable)")); };
    },
    flush: function (ok) { var q = this.queue; this.queue = []; q.forEach(function (f) { f(ok); }); },
    /* send(key, cb(ok)) */
    send: function (key, cb) {
      var self = this;
      if (self.ready && self.ws) {
        self.ws.send(JSON.stringify({ method: "ms.remote.control",
          params: { Cmd: "Click", DataOfCmd: key, Option: "false", TypeOfRemote: "SendRemoteKey" } }));
        note("sent " + key); return cb && cb(true);
      }
      // not paired: never make the user wait; use the next method, and retry pairing quietly
      if (!self.connecting) { self.failed = false; self.connecting = true; self.connect(0); }
      return cb && cb(false);
    }
  };

  /* Samsung's own apps on this TV (from `sdb shell 0 applist`, QA50QEF1 / Tizen 10) */
  var APP = {
    settings: "com.samsung.tv.coba.setting",
    sources: "com.samsung.tv.coba-source-native",
    viewer: "org.tizen.tv-viewer"
  };
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }

  /* ---------- method 2: Tizen APIs ---------- */
  var Tz = {
    sources: null, apps: [], winShown: false,
    loadSources: function (cb) {
      if (!hasTizen) return cb([]);
      try {
        tizen.systeminfo.getPropertyValue("VIDEOSOURCE", function (v) {
          var list = [];
          (v.connected || []).forEach(function (s) { list.push({ type: s.type, number: s.number, on: true, raw: s }); });
          (v.disconnected || []).forEach(function (s) { list.push({ type: s.type, number: s.number, on: false, raw: s }); });
          Tz.sources = list; note("sources: " + list.map(function (s) { return s.type + s.number + (s.on ? "*" : ""); }).join(" ")); cb(list);
        }, function (e) { note("VIDEOSOURCE error: " + e.message); cb([]); });
      } catch (e) { note("VIDEOSOURCE threw: " + e.message); cb([]); }
    },
    loadApps: function () {
      if (!hasTizen) return;
      try {
        tizen.application.getAppsInfo(function (a) { Tz.apps = a; note("apps listed: " + a.length); if (cur === "apps") show("apps"); },
          function (e) { note("getAppsInfo error: " + e.message); });
      } catch (e) { note("getAppsInfo threw: " + e.message); }
    },
    // launch by exact id (a name search once opened "Google Cast Settings" instead of Settings)
    launchId: function (id, cb) {
      if (!hasTizen) { note("(preview) launch " + id); return cb(false); }
      try { tizen.application.launch(id, function () { note("launched " + id); cb(true); },
              function (e) { note("launch " + id + " failed: " + e.message); cb(false); }); }
      catch (e) { note("launch " + id + " threw: " + e.message); cb(false); }
    },
    showSource: function (src, cb) {
      if (!hasTizen || !tizen.tvwindow) return cb(false);
      try {
        tizen.tvwindow.setSource(src.raw || { type: src.type, number: src.number }, function () {
          tizen.tvwindow.show(function () { Tz.winShown = true; note("tvwindow showing " + src.type + src.number); cb(true); },
            function (e) { note("tvwindow.show failed: " + e.message); cb(false); },
            ["0px", "0px", "1920px", "1080px"], "MAIN");
        }, function (e) { note("tvwindow.setSource failed: " + e.message); cb(false); }, "MAIN");
      } catch (e) { note("tvwindow threw: " + e.message); cb(false); }
    },
    hideWindow: function () {
      try { tizen.tvwindow.hide(function () {}, function () {}, "MAIN"); } catch (e) {}
      Tz.winShown = false; document.body.style.background = "#000";
    }
  };

  /* try each step in turn until one says ok */
  function attempt(steps, failMsg) {
    var i = 0;
    (function next() {
      if (i >= steps.length) return status(failMsg);
      steps[i++](function (ok) { if (!ok) next(); else status(""); });
    })();
  }

  /* ---------- actions ---------- */
  function keyFor(src) {
    if (src.type === "TV") return "KEY_TV";
    if (src.type === "HDMI") return "KEY_HDMI" + src.number;
    if (src.type === "AV") return "KEY_AV" + src.number;
    if (src.type === "COMP") return "KEY_COMPONENT" + src.number;
    return "KEY_SOURCE";
  }
  function goSource(src) {
    if (src.type !== "TV") store("tvhub.last", srcKey(src));
    status("Switching to " + (nameOf(src) || label(src)) + "…");
    attempt([
      function (cb) { Remote.send(keyFor(src), cb); },
      function (cb) { Tz.showSource(src, function (ok) { if (ok) document.body.style.background = "transparent"; cb(ok); }); },
      function (cb) { src.type === "TV" ? Tz.launchId(APP.viewer, cb) : cb(false); }
    ], src.type === "TV" ? "Couldn't switch to TV. Press RED for details."
      : "Direct switching needs IP Remote (see README). Use 'Source list' meanwhile.");
  }
  function lastInputText() {
    var k = store("tvhub.last"); if (!k) return "";
    var m = /^([A-Z]+)(\d+)$/.exec(k); if (!m) return "";
    var s = { type: m[1], number: +m[2] };
    return "last: " + (nameOf(s) || label(s));
  }
  function goSettings() {
    status("Opening settings…");
    attempt([
      function (cb) { Tz.launchId(APP.settings, cb); },
      function (cb) { Remote.send("KEY_MENU", cb); }
    ], "Couldn't open Settings. Press RED for details.");
  }
  /* apps the user can open: the ones Samsung shows in its own app tray, most-used first,
     split into tabs: real apps / Samsung's own / hidden. Your moves and hides are remembered. */
  var APP_TABS = ["Apps", "Samsung", "Hidden"], appTab = 0, onTabs = false;
  function jget(k) { try { return JSON.parse(store(k) || "{}"); } catch (e) { return {}; } }
  // third-party apps that Samsung ships under its own ids
  var NOT_SAMSUNG = /netflix|youtube|prime|disney|apple tv|google|spotify|plex|jellyfin|copilot|perplexity|iqiyi|premier|twitch|max\b|paramount|crunchyroll|deezer|tidal|kodi/i;
  function isSamsungApp(a) {
    var o = jget("tvhub.tab")[a.id]; if (o) return o === "Samsung";
    if (NOT_SAMSUNG.test(a.name)) return false;
    return /^(com\.samsung\.|org\.tizen\.|[0-9]+$|WSRedi|26emanual)/i.test(a.id) || /samsung|bixby|smartthings|gallery|workspace|user guide|internet|browser/i.test(a.name);
  }
  function allApps() {
    var me = ""; try { me = tizen.application.getCurrentApplication().appInfo.id; } catch (e) {}
    return hasTizen ? Tz.apps.filter(function (a) { return a.show && a.id !== me && a.name; })
      : ["Netflix", "YouTube", "Prime Video", "Disney+", "Jellyfin", "Spotify", "Samsung TV Plus", "SmartThings", "Gallery"]
          .map(function (n) { return { id: n.replace(/\W/g, ""), name: n }; });
  }
  function userApps(tab) {
    var used = jget("tvhub.used"), hidden = jget("tvhub.hidden");
    return allApps().filter(function (a) {
      if (tab === "Hidden") return !!hidden[a.id];
      return !hidden[a.id] && isSamsungApp(a) === (tab === "Samsung");
    }).sort(function (a, b) { return (used[b.id] || 0) - (used[a.id] || 0) || a.name.localeCompare(b.name); });
  }
  function appMenu(a) {
    var hidden = jget("tvhub.hidden"), other = isSamsungApp(a) ? "Apps" : "Samsung";
    pick(a.name, [
      { text: "Open", act: function () { openApp(a); } },
      hidden[a.id] ? { text: "Unhide", act: function () { delete hidden[a.id]; store("tvhub.hidden", JSON.stringify(hidden)); } }
                   : { text: "Hide", act: function () { hidden[a.id] = 1; store("tvhub.hidden", JSON.stringify(hidden)); } },
      { text: "Move to " + other, act: function () { var t = jget("tvhub.tab"); t[a.id] = other; store("tvhub.tab", JSON.stringify(t)); } },
      { text: "Cancel", act: function () {} }
    ]);
  }

  /* small choice list over the screen (▲▼, OK, Back) */
  var picking = null;
  function pick(title, opts) {
    picking = { opts: opts, i: 0 };
    document.getElementById("ptitle").textContent = title;
    paintPick(); document.getElementById("pick").style.display = "flex";
  }
  function paintPick() {
    var ul = document.getElementById("plist"); ul.innerHTML = "";
    picking.opts.forEach(function (o, i) { var d = document.createElement("div"); d.className = "opt" + (i === picking.i ? " on" : ""); d.textContent = o.text; ul.appendChild(d); });
  }
  function closePick(run) {
    var o = picking && picking.opts[picking.i]; picking = null;
    document.getElementById("pick").style.display = "none";
    if (run && o) o.act();
    show(cur);
  }
  function openApp(a) {
    var used = {}; try { used = JSON.parse(store("tvhub.used") || "{}"); } catch (e) {}
    used[a.id] = (used[a.id] || 0) + 1; store("tvhub.used", JSON.stringify(used));
    if (!hasTizen) return status("(preview) would open " + a.name);
    status("Opening " + a.name + "…");
    try { tizen.application.launch(a.id, function () { status(""); }, function (e) { status("Couldn't open " + a.name + ": " + e.message); }); }
    catch (e) { status("Couldn't open " + a.name + ": " + e.message); }
  }
  function appIcon(a) {
    var letter = '<div class="letter">' + a.name.charAt(0).toUpperCase() + "</div>";
    if (!a.iconPath) return letter;
    var src = a.iconPath.indexOf("file://") === 0 ? a.iconPath : "file://" + a.iconPath;
    return '<img src="' + src + '" onerror="this.outerHTML=\'' + letter.replace(/"/g, "&quot;") + '\'">';
  }
  function goSourcePicker() {
    attempt([
      function (cb) { Tz.launchId(APP.sources, cb); },
      function (cb) { Remote.send("KEY_SOURCE", cb); }
    ], "Samsung's source list isn't reachable. Press RED for details.");
  }

  /* ---------- input names (yours) + last used ---------- */
  function srcKey(s) { return s.type + s.number; }
  function names() { try { return JSON.parse(store("tvhub.names") || "{}"); } catch (e) { return {}; } }
  function nameOf(s) { return names()[srcKey(s)] || ""; }
  function currentSource() {   // what the TV is showing right now, if it tells us
    try { var c = tizen.tvwindow.getSource("MAIN"); return c ? c.type + c.number : ""; } catch (e) { return ""; }
  }
  function rename(s) {
    var box = document.getElementById("rename"), inp = document.getElementById("rname");
    document.getElementById("rlabel").textContent = "Name for " + label(s) + " (e.g. Android TV, PlayStation). Empty = no name.";
    inp.value = nameOf(s); box.style.display = "flex"; renaming = s;
    setTimeout(function () { inp.focus(); }, 50);
  }
  function closeRename(save) {
    var inp = document.getElementById("rname");
    if (save && renaming) {
      var n = names(), v = inp.value.trim();
      if (v) n[srcKey(renaming)] = v.slice(0, 24); else delete n[srcKey(renaming)];
      store("tvhub.names", JSON.stringify(n));
    }
    inp.blur(); document.getElementById("rename").style.display = "none"; renaming = null;
    show(cur);
  }
  var renaming = null;

  /* ---------- screens ---------- */
  function label(s) { return s.type === "TV" ? "TV" : s.type === "HDMI" ? "HDMI " + s.number : s.type + " " + s.number; }
  var screens = {
    home: function () {
      return { title: "", small: false, items: [
        { icon: ICON.tv, text: "TV", act: function () { goSource({ type: "TV", number: 1 }); } },
        { icon: ICON.input, text: "Inputs", sub: lastInputText(), act: function () { show("inputs"); } },
        { icon: ICON.apps, text: "Apps", act: function () { show("apps"); } },
        { icon: ICON.gear, text: "Settings", act: goSettings }
      ] };
    },
    apps: function () {
      var tab = APP_TABS[appTab];
      var items = userApps(tab).map(function (a) {
        return { icon: appIcon(a), text: a.name, act: function () { openApp(a); }, rename: function () { appMenu(a); } };
      });
      if (!items.length) items.push({ icon: ICON.apps, text: Tz.apps.length || !hasTizen ? "Nothing here" : "Loading…", act: function () { Tz.loadApps(); } });
      var tabs = APP_TABS.map(function (t, i) { return '<span class="tab' + (i === appTab ? " sel" : "") + (i === appTab && onTabs ? " on" : "") + '">' + t + "</span>"; }).join("");
      return { titleHtml: tabs + '<span class="hint">hold OK: hide / move</span>', small: true, grid: true, items: items };
    },
    inputs: function () {
      var list = (Tz.sources && Tz.sources.length) ? Tz.sources.filter(function (s) { return s.type === "HDMI" || s.type === "AV" || s.type === "COMP"; })
        : [1, 2, 3, 4].map(function (n) { return { type: "HDMI", number: n, on: true }; });
      list.sort(function (a, b) { return a.type === b.type ? a.number - b.number : a.type < b.type ? -1 : 1; });
      var now = currentSource(), last = store("tvhub.last") || "";
      var items = list.map(function (s) {
        var n = nameOf(s), k = srcKey(s);
        var sub = (n ? label(s) : "") + (k === now ? (n ? " · " : "") + "now" : k === last ? (n ? " · " : "") + "last used" : "");
        return { icon: ICON.hdmi, text: n || label(s), sub: sub, off: !s.on, act: function () { goSource(s); }, rename: function () { rename(s); } };
      });
      items.push({ icon: ICON.more, text: "Source list", sub: "Samsung's, with device names", act: goSourcePicker });
      return { title: "Inputs   ·   hold OK (or YELLOW) to name an input", small: true, items: items };
    }
  };
  var cur = "home", focus = 0, items = [], cols = 1;
  var lastFocus = { home: 0, inputs: 0, apps: 0 };
  var GRID_COLS = 5;
  function show(name) {
    if (name !== cur) status("");
    if (name !== "apps") onTabs = false;
    lastFocus[cur] = focus; cur = name; focus = lastFocus[name] || 0;
    var s = screens[name]();
    items = s.items; focus = Math.min(focus, items.length - 1);
    if (s.titleHtml) document.getElementById("title").innerHTML = s.titleHtml;
    else document.getElementById("title").textContent = s.title;
    var row = document.getElementById("row"); row.innerHTML = "";
    row.className = "row" + (s.grid ? " grid" : ""); cols = s.grid ? GRID_COLS : items.length;
    items.forEach(function (it, i) {
      var d = document.createElement("div");
      d.className = "tile" + (s.small ? " small" : "") + (it.off ? " off" : "");
      d.innerHTML = it.icon + '<div class="name">' + esc(it.text) + "</div>" + (it.sub ? '<div class="sub">' + esc(it.sub) + "</div>" : "");
      row.appendChild(d); it.el = d;
    });
    paint();
  }
  function paint() {
    items.forEach(function (it, i) { it.el.classList.toggle("on", i === focus && !onTabs); });
    if (cols !== items.length && items[focus]) items[focus].el.scrollIntoView({ block: "nearest" });
  }

  /* ---------- diagnostics (RED) ---------- */
  function diag() {
    var el = document.getElementById("diag");
    if (el.style.display === "block") { el.style.display = "none"; return; }
    var apps = Tz.apps.filter(function (a) { return /samsung|tizen|setting|tv|source/i.test(a.id); })
      .map(function (a) { return "  " + a.id + "  (" + a.name + ")"; }).slice(0, 25);
    el.textContent = "TV Hub diagnostics   (RED to close)\n\n" +
      "tizen API: " + hasTizen + "   tvwindow: " + !!(hasTizen && tizen.tvwindow) + "\n" +
      "remote socket: " + (Remote.ready ? "paired" : Remote.failed ? "unavailable" : "not tried yet") + "\n" +
      "sources: " + (Tz.sources ? Tz.sources.map(function (s) { return label(s) + (s.on ? " (connected)" : ""); }).join(", ") : "-") + "\n\n" +
      "log:\n" + log.slice(-18).join("\n") + "\n\nSamsung apps:\n" + apps.join("\n");
    el.style.display = "block";
  }

  /* ---------- remote control ---------- */
  // OK runs on release, so holding it (0.7 s) can mean "rename" instead
  var okDown = 0, okTimer = null, okLong = false;
  document.addEventListener("keyup", function (e) {
    if (e.keyCode !== KEY.ENTER || !okDown) return;
    clearTimeout(okTimer); okDown = 0;
    if (!okLong && !renaming && items[focus]) items[focus].act();
    e.preventDefault();
  });
  document.addEventListener("keydown", function (e) {
    var k = e.keyCode;
    if (renaming) {   // on-screen keyboard: Done (13 / 65376) saves, Cancel (65385) / Back closes
      if (k === KEY.ENTER && okDown) { e.preventDefault(); return; }   // still the held OK that opened this
      if (k === KEY.ENTER || k === 65376) { closeRename(true); e.preventDefault(); }
      else if (k === 65385 || k === KEY.BACK || k === KEY.ESC) { closeRename(false); e.preventDefault(); }
      return;
    }
    if (picking) {
      e.preventDefault();
      if (k === KEY.ENTER && okDown) return;   // still the held OK that opened the menu
      var n = picking.opts.length;
      if (k === KEY.UP) picking.i = (picking.i + n - 1) % n;
      else if (k === KEY.DOWN) picking.i = (picking.i + 1) % n;
      else if (k === KEY.ENTER) return closePick(true);
      else if (k === KEY.BACK || k === KEY.ESC || k === KEY.BKSP) return closePick(false);
      return paintPick();
    }
    if (Tz.winShown) { if (k === KEY.BACK || k === KEY.ESC) { Tz.hideWindow(); e.preventDefault(); } return; }
    if (cur === "apps" && (onTabs || (k === KEY.UP && focus < cols))) {   // tab bar above the app grid
      e.preventDefault();
      if (!onTabs) onTabs = true;
      else if (k === KEY.LEFT) { appTab = (appTab + APP_TABS.length - 1) % APP_TABS.length; focus = 0; }
      else if (k === KEY.RIGHT) { appTab = (appTab + 1) % APP_TABS.length; focus = 0; }
      else if (k === KEY.DOWN || k === KEY.ENTER) onTabs = false;
      else if (k === KEY.BACK || k === KEY.ESC || k === KEY.BKSP) { onTabs = false; return show("home"); }
      else return;
      lastFocus.apps = focus; return show("apps");
    }
    if (k === KEY.LEFT) focus = (focus + items.length - 1) % items.length;
    else if (k === KEY.RIGHT) focus = (focus + 1) % items.length;
    else if (k === KEY.UP && cols < items.length) focus = Math.max(0, focus - cols);
    else if (k === KEY.DOWN && cols < items.length) focus = Math.min(items.length - 1, focus + cols);
    else if (k === KEY.ENTER) {
      if (!okDown) {
        okDown = Date.now(); okLong = false;
        okTimer = setTimeout(function () { var it = items[focus]; if (it && it.rename) { okLong = true; it.rename(); } }, 700);
      }
    }
    else if (k === KEY.YELLOW || k === 89 /* y, desktop */) { if (items[focus] && items[focus].rename) items[focus].rename(); }
    else if (k === KEY.RED || k === 82 /* r, for desktop testing */) diag();
    else if (k === KEY.BACK || k === KEY.ESC || k === KEY.BKSP) { if (cur !== "home") show("home"); /* top level: stay put */ }
    else return;
    e.preventDefault(); paint();
  });

  function tick() { var d = new Date(); document.getElementById("clock").textContent = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }); }

  /* ---------- start ---------- */
  if (hasTizen) {
    ["ColorF0Red", "ColorF2Yellow"].forEach(function (k) { try { tizen.tvinputdevice.registerKey(k); } catch (e) { note("registerKey " + k + ": " + e.message); } });
  } else note("no tizen API: desktop preview mode (arrows, Enter, Esc, R)");
  tick(); setInterval(tick, 15000);
  Tz.loadApps();
  Tz.loadSources(function () { if (cur === "inputs") show("inputs"); });
  show("home");
  // pair early so the "Allow" prompt appears on first launch, not on the first button press
  if (hasTizen) { Remote.connecting = true; Remote.connect(0); }
})();
