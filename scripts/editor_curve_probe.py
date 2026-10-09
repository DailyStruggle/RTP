#!/usr/bin/env python3
"""Headless-Chrome probe of the web editor's sandboxed curve runner (ADR-106 sections 4.3-4.6).

Serves a probe copy of docs/editor/index.html over http://127.0.0.1 (the GitHub Pages layout)
and writes the same copy to a temp dir for file://, each with an embedded scenario payload, then
reads the page's verdict, which a probe script POSTs back to the local server. Scenarios:

  real          captured plugin snapshot (rtp-core/build/editor-probe/payload.json, written by
                EditorProbePayloadFixtureTest): helpers verified against the Java CurveHash, typed
                settings form, radius edit redraws "estimated", hazard runs decoded, second region
  line          synthetic helper with a hash computed here (spec parity of the page's CurveHash)
  escape        helper probing parent.document / localStorage / indexedDB / fetch / Image
  loop          helper that spins forever once radius is edited (only with a Worker sandbox)
  throw, malformed, load-error, sha256, hash-mismatch   one fallback reason each

Stdlib only. Every Chrome run is killed after --timeout seconds; no virtual time is used, so
the runner's real Worker timeouts are what is measured.

  python scripts/editor_curve_probe.py [--chrome PATH] [--mode http|file|both] [--only NAME,...]
"""
import argparse
import hashlib
import http.server
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PAGE = ROOT / "docs" / "editor" / "index.html"
CAPTURED = ROOT / "rtp-core" / "build" / "editor-probe" / "payload.json"

CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
]

# ---------------------------------------------------------------------------------------------
# Synthetic helper: two rows of 2r chunks around (centerX, centerZ); range = 4r
# ---------------------------------------------------------------------------------------------

LINE_CORE = """
  range: function (p) { var r = Math.floor(p.radius); return r > 0 ? 4 * r : 0; },
  locationToXZ: function (loc, p) {
    var r = Math.floor(p.radius), w = 2 * r;
    if (!(r > 0) || loc < 0 || loc >= 4 * r) return null;
    return [p.centerX + (loc % w) - r, p.centerZ + Math.floor(loc / w) - 1];
  },
  xzToLocation: function (x, z, p) {
    var r = Math.floor(p.radius), w = 2 * r, dx = x - p.centerX + r, dz = z - p.centerZ + 1;
    return (r > 0 && dx >= 0 && dx < w && dz >= 0 && dz < 2) ? dz * w + dx : -1;
  }
"""
LINE_JS = "({" + LINE_CORE + "})"

ESCAPE_JS = """(function () {
  var g = (typeof globalThis !== 'undefined') ? globalThis : this;
  try { parent.document.body.setAttribute('data-pwned', 'dom'); } catch (e) {}
  try { top.document.title = 'PWNED'; } catch (e) {}
  try { g.localStorage.setItem('rtp-probe-pwned', '1'); } catch (e) {}
  try { g.indexedDB.open('rtp-probe-pwned'); } catch (e) {}
  try { g.fetch('http://127.0.0.1:%PORT%/leak?fetch').catch(function () {}); } catch (e) {}
  try { var i = new Image(); i.src = 'http://127.0.0.1:%PORT%/leak?img'; } catch (e) {}
  try { var x = new XMLHttpRequest(); x.open('GET', 'http://127.0.0.1:%PORT%/leak?xhr'); x.send(); } catch (e) {}
  try { new WebSocket('ws://127.0.0.1:%PORT%/leak?ws'); } catch (e) {}
  try { g.importScripts('http://127.0.0.1:%PORT%/leak?import'); } catch (e) {}
  return ({""" + LINE_CORE + """});
})()"""

# Correct until the radius is edited to 999, then never returns
LOOP_JS = LINE_JS.replace("locationToXZ: function (loc, p) {",
                          "locationToXZ: function (loc, p) {\n    if (p.radius === 999) { for (;;) {} }")
THROW_JS = LINE_JS.replace("locationToXZ: function (loc, p) {",
                           "locationToXZ: function (loc, p) {\n    throw new Error('probe');")
MALFORMED_JS = LINE_JS.replace("locationToXZ: function (loc, p) {",
                               "locationToXZ: function (loc, p) {\n    return 'not-a-pair';")
LOAD_ERROR_JS = "({ range: function () { return 1; } })"

LINE_PARAMS = {"radius": 50, "centerX": -3, "centerZ": 4}


def line_xz(loc, p):
    r = int(p["radius"])
    w = 2 * r
    if r <= 0 or loc < 0 or loc >= 4 * r:
        return None
    return p["centerX"] + (loc % w) - r, p["centerZ"] + loc // w - 1


def curve_hash(rng, xz_of):
    """CurveHash.of: FNV-1a 32 over R (int64 LE) then each sample's x, z (int32 LE)."""
    h = 0x811C9DC5

    def mix(octet):
        nonlocal h
        h = ((h ^ (octet & 0xFF)) * 0x01000193) & 0xFFFFFFFF

    for b in range(8):
        mix((rng >> (8 * b)) & 0xFF)
    samples = range(rng) if rng <= 256 else [(i * rng) >> 8 for i in range(256)]
    for s in samples:
        xz = xz_of(s)
        for v in ((0x80000000, 0x80000000) if xz is None else xz):
            v &= 0xFFFFFFFF
            for b in range(4):
                mix(v >> (8 * b))
    return "%08x" % h


def synthetic_payload(js, port, sha_ok=True, hash_ok=True):
    js = js.replace("%PORT%", str(port))
    rng = 4 * LINE_PARAMS["radius"]
    good_hash = curve_hash(rng, lambda s: line_xz(s, LINE_PARAMS))
    sha = hashlib.sha256(js.encode("utf-8")).hexdigest()
    schema_shape = {
        "radius": {"type": "integer", "kind": "distance", "default": 256, "description": "probe radius",
                   "suggestions": ["32", "64", "128"]},
        "centerX": {"type": "integer", "kind": "distance", "default": 0, "description": "probe centre x"},
        "centerZ": {"type": "integer", "kind": "distance", "default": 0, "description": "probe centre z"},
        "expand": {"type": "boolean", "kind": "boolean", "default": False},
        "mode": {"type": "enum", "kind": "enum", "default": "ACCUMULATE", "suggestions": ["ACCUMULATE", "REROLL"]},
    }
    yml = ('world: "[0]"\nshape:\n  name: "PROBE_LINE"\n  radius: %d\n  centerX: %d\n  centerZ: %d\n'
           % (LINE_PARAMS["radius"], LINE_PARAMS["centerX"], LINE_PARAMS["centerZ"]))
    return {
        "version": 1,
        "files": {"regions/default.yml": yml},
        "schema": {"shape": {"PROBE_LINE": schema_shape}, "vert": {},
                   "curveParams": {"PROBE_LINE": ["radius", "centerX", "centerZ"]}},
        "curveCode": {"PROBE_LINE": {"sha256": sha if sha_ok else "0" * 64, "js": js}},
        "regionModels": {"default": {
            "region": "default", "world": "[0]",
            "curve": {"shape": "PROBE_LINE", "params": dict(LINE_PARAMS), "state": {},
                      "hash": good_hash if hash_ok else "%08x" % (int(good_hash, 16) ^ 1)},
            "hazardRuns": {"v": 1, "runs": ""},
        }},
    }


# expect: checks the probe script evaluates in the page (see PROBE_JS)
SCENARIOS = {
    "real": {"expect": "verified", "edit": "radius=320c", "after": "estimated", "region2": "probe_square"},
    "line": {"js": LINE_JS, "expect": "verified", "edit": "radius=80", "after": "estimated"},
    "escape": {"js": ESCAPE_JS, "expect": "verified"},
    "loop": {"js": LOOP_JS, "expect": "verified", "edit": "radius=999", "after": "fallback:timeout", "needsWorker": True},
    "throw": {"js": THROW_JS, "expect": "fallback:throw"},
    "malformed": {"js": MALFORMED_JS, "expect": "fallback:malformed"},
    "load-error": {"js": LOAD_ERROR_JS, "expect": "fallback:load-error"},
    "sha256": {"js": LINE_JS, "sha_ok": False, "expect": "fallback:sha256"},
    "hash-mismatch": {"js": LINE_JS, "hash_ok": False, "expect": "fallback:hash-mismatch"},
}

HEAD_JS = """<script>
window.__probe = %CFG%;
window.__probeErrors = [];
window.addEventListener('error', function (e) { window.__probeErrors.push(String(e.message || e)); });
window.addEventListener('unhandledrejection', function (e) { window.__probeErrors.push('rejection: ' + String(e.reason && e.reason.message || e.reason)); });
</script>"""

PROBE_JS = r"""<script>
(async function () {
  const cfg = window.__probe;
  const out = { scenario: cfg.name, steps: [] };
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const badge = () => { const el = document.getElementById('hud-curve'); return el ? (el.dataset.status + (el.dataset.reason ? ':' + el.dataset.reason : '')) : 'missing'; };
  const settle = async (ms) => {
    const t = Date.now();
    while (Date.now() - t < ms) {
      const b = badge();
      if (b !== 'pending' && b !== '' && b !== 'missing') return b;
      requestMapRedraw();
      await sleep(50);
    }
    return badge();
  };
  try {
    out.badge = await settle(8000);
    out.text = document.getElementById('hud-curve').textContent;
    out.worker = CurveSandbox.usesWorker();
    out.form = [...document.querySelectorAll('#shape-settings-form .shape-setting')].map(r => r.dataset.key + ':' + r.dataset.kind);
    const runs = decodedCurveRuns(regionState);
    out.hazardRuns = runs ? runs.n : null;
    const m = curveModels.get(regionState.name);
    out.range = m ? m.range : null;
    if (cfg.diag && m && m.inputs) {
      const r = (p) => p.then(v => String(v && v.length !== undefined ? 'len ' + v.length + ' ' + Array.from(v.slice(0, 4)) : v),
        e => 'ERR ' + (e && e.reason) + ' ' + (e && e.message));
      // Through the sandbox only: helper code must never be evaluated on the page, even here
      out.diag = { inputs: m.inputs, reason: m.reason };
      out.diag.range = await r(CurveSandbox.range(m.inputs.shape, m.inputs.params, m.inputs.state));
      out.diag.toXZ = await r(CurveSandbox.toXZ(m.inputs.shape, m.inputs.params, m.inputs.state, Float64Array.from([0, 1, 2])));
    }
    if (cfg.edit && !(cfg.needsWorker && !out.worker)) {
      const [key, value] = cfg.edit.split('=');
      const input = document.querySelector('#shape-settings-form .shape-setting[data-key="' + key + '"] input');
      if (!input) throw new Error('no input for ' + key);
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await sleep(150);
      out.afterBadge = await settle(8000);
      const m2 = curveModels.get(regionState.name);
      out.afterRange = m2 ? m2.range : null;
      out.afterOverview = !!(m2 && m2.overview && m2.overview.xz.length > 0);
      out.stagedYamlHasEdit = String(configs[regionFileFor(regionState.name)] || '').includes(key + ': ' + value);
    } else if (cfg.edit) {
      out.skipped = 'no Worker in the sandbox: an in-frame infinite loop would block the page';
    }
    if (cfg.region2) {
      changeRegionProfile(cfg.region2);
      out.region2Badge = await settle(8000);
    }
    out.pwnedDom = document.body.getAttribute('data-pwned');
    out.title = document.title;
    try { out.pwnedStorage = localStorage.getItem('rtp-probe-pwned'); } catch (e) { out.pwnedStorage = 'n/a'; }
  } catch (e) {
    out.error = String(e && e.stack || e);
  }
  out.errors = window.__probeErrors;
  try {
    await fetch('http://127.0.0.1:' + cfg.port + '/result', { method: 'POST', mode: 'no-cors', body: JSON.stringify(out) });
  } catch (e) { /* the server times out */ }
})();
</script>"""


class ProbeState:
    def __init__(self):
        self.lock = threading.Lock()
        self.pages = {}
        self.results = {}
        self.leaks = []


def make_handler(state):
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _send(self, code, body=b"", ctype="text/plain"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path.startswith("/leak"):
                with state.lock:
                    state.leaks.append(self.path)
                return self._send(204)
            name = self.path.strip("/").split("/")[0]
            page = state.pages.get(name)
            if page is None:
                return self._send(404)
            self._send(200, page.encode("utf-8"), "text/html; charset=utf-8")

        def do_POST(self):
            body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode("utf-8", "replace")
            if self.path.startswith("/leak"):
                with state.lock:
                    state.leaks.append(self.path)
            elif self.path.startswith("/result"):
                try:
                    res = json.loads(body)
                    with state.lock:
                        state.results[res.get("scenario")] = res
                except ValueError:
                    pass
            self._send(204)

    return Handler


def build_page(template, name, payload, port, scen, diag=False):
    cfg = {"name": name, "port": port, "edit": scen.get("edit"), "needsWorker": scen.get("needsWorker", False),
           "region2": scen.get("region2"), "diag": diag}
    # Same splice as EditorSessionManager.embedPayload: every '<' escaped, block before the first </head>
    blob = json.dumps(payload).replace("<", "\\u003c")
    block = '<script type="application/json" id="rtp-embedded-payload">' + blob + "</script>\n"
    page = template.replace("<head>", "<head>" + HEAD_JS.replace("%CFG%", json.dumps(cfg)), 1)
    i = page.index("</head>")
    page = page[:i] + block + page[i:]
    j = page.rindex("</body>")
    return page[:j] + PROBE_JS + page[j:]


def run_chrome(chrome, url, profile, timeout, done, log=None):
    args = [chrome, "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
            "--disable-extensions", "--window-size=1280,900", "--user-data-dir=" + str(profile)]
    if log:
        args += ["--enable-logging=stderr", "--v=0"]
    args.append(url)
    err = open(log, "wb") if log else subprocess.DEVNULL
    proc = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=err)
    t0 = time.time()
    try:
        while time.time() - t0 < timeout and not done():
            time.sleep(0.2)
    finally:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)
        else:
            proc.kill()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            pass
        if log:
            err.close()
    return time.time() - t0


def verdict(name, scen, res, leaks):
    problems = []
    if res is None:
        return ["no result (page did not report within the timeout)"]
    if res.get("error"):
        problems.append("probe error: " + res["error"])
    if res.get("errors"):
        problems.append("page script errors: %s" % res["errors"])
    if res.get("badge") != scen["expect"]:
        problems.append("badge %r, expected %r (%s)" % (res.get("badge"), scen["expect"], res.get("text")))
    if scen.get("after") and not res.get("skipped"):
        if res.get("afterBadge") != scen["after"]:
            problems.append("after edit: badge %r, expected %r" % (res.get("afterBadge"), scen["after"]))
        if scen["after"] == "estimated":
            if not res.get("afterOverview"):
                problems.append("after edit: no path drawn from the helper")
            if res.get("afterRange") == res.get("range"):
                problems.append("after edit: range unchanged (%s)" % res.get("range"))
            if not res.get("stagedYamlHasEdit"):
                problems.append("after edit: region YAML not staged with the new value")
    if scen.get("region2") and res.get("region2Badge") != "verified":
        problems.append("second region badge %r" % res.get("region2Badge"))
    if name in ("real", "line"):
        if not any(f.startswith("radius:distance") for f in res.get("form") or []):
            problems.append("settings form lacks a distance radius input: %s" % res.get("form"))
    if name == "real" and not res.get("hazardRuns"):
        problems.append("hazard runs not decoded: %s" % res.get("hazardRuns"))
    if res.get("pwnedDom") or res.get("title") == "PWNED" or res.get("pwnedStorage") not in (None, "n/a"):
        problems.append("sandbox escape: dom=%s title=%s storage=%s"
                        % (res.get("pwnedDom"), res.get("title"), res.get("pwnedStorage")))
    if leaks:
        problems.append("network from helper code: %s" % leaks)
    return problems


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--chrome")
    ap.add_argument("--mode", default="both", choices=["http", "file", "both"])
    ap.add_argument("--only", default="")
    ap.add_argument("--timeout", type=float, default=40.0)
    ap.add_argument("--debug", action="store_true", help="print the page console lines of failed runs")
    args = ap.parse_args()

    chrome = args.chrome or next((c for c in CHROME_CANDIDATES if Path(c).exists()), None)
    if not chrome:
        print("No Chrome / Edge found; pass --chrome PATH")
        return 2
    template = PAGE.read_text(encoding="utf-8")
    names = [n for n in SCENARIOS if not args.only or n in args.only.split(",")]

    state = ProbeState()
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), make_handler(state))
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    tmp = Path(tempfile.mkdtemp(prefix="rtp-editor-probe-"))
    modes = ["http", "file"] if args.mode == "both" else [args.mode]
    failures = 0
    try:
        for mode in modes:
            for name in names:
                scen = SCENARIOS[name]
                if name == "real":
                    if not CAPTURED.exists():
                        print("[%s] %-14s SKIP  run :rtp-core:test --tests \"*EditorProbePayloadFixtureTest\" first" % (mode, name))
                        continue
                    payload = json.loads(CAPTURED.read_text(encoding="utf-8"))
                else:
                    payload = synthetic_payload(scen["js"], port, scen.get("sha_ok", True), scen.get("hash_ok", True))
                key = mode + "-" + name
                page = build_page(template, key, payload, port, scen, args.debug)
                with state.lock:
                    state.results.pop(key, None)
                    state.leaks.clear()
                if mode == "http":
                    state.pages[key] = page
                    url = "http://127.0.0.1:%d/%s/index.html" % (port, key)
                else:
                    f = tmp / key / "index.html"
                    f.parent.mkdir(parents=True, exist_ok=True)
                    f.write_text(page, encoding="utf-8")
                    url = f.as_uri()
                log = tmp / (key + ".log") if args.debug else None
                took = run_chrome(chrome, url, tmp / ("profile-" + key), args.timeout,
                                  lambda: key in state.results, log)
                time.sleep(0.3)  # late leak requests
                with state.lock:
                    res = state.results.get(key)
                    leaks = list(state.leaks)
                problems = verdict(name, scen, res, leaks)
                failures += bool(problems)
                summary = "" if res is None else "badge=%s%s worker=%s" % (
                    res.get("badge"), (" -> " + str(res.get("afterBadge"))) if res.get("afterBadge") else "", res.get("worker"))
                if res and res.get("skipped"):
                    summary += " (edit skipped: %s)" % res["skipped"]
                print("[%s] %-14s %s  %.1fs  %s" % (mode, name, "FAIL" if problems else "ok  ", took, summary))
                for p in problems:
                    print("      - " + p)
                if problems and res and res.get("diag"):
                    print("      diag: " + json.dumps(res["diag"])[:600])
                if problems and log and log.exists():
                    lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
                    for line in [x for x in lines if "CONSOLE" in x or "Uncaught" in x][:40]:
                        print("      | " + line[:300])
    finally:
        server.shutdown()
        shutil.rmtree(tmp, ignore_errors=True)
    print("%d failure(s)" % failures)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
