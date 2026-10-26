from __future__ import annotations

import base64
import gzip
import json
from pathlib import Path
import socket
import sqlite3
import struct
import sys
import tempfile
import threading
import unittest
from urllib import request

TESTS_DIR = Path(__file__).resolve().parent
BOT_DIR = TESTS_DIR.parent
if str(BOT_DIR) not in sys.path:
    sys.path.insert(0, str(BOT_DIR))

from build_doc_index import DEFAULT_DB_PATH, build_index  # noqa: E402
from web_relay_and_triage import (  # noqa: E402
    BytebinStore,
    BytesocksRelayHub,
    COMMUNITY_FRAMES_PER_WINDOW,
    PRO_FRAMES_PER_WINDOW,
    PRO_TTL_SECONDS,
    WebRelayAndTriageServer,
    export_web_corpus_json,
    read_ws_frame,
    resolve_service_tier,
    run_web_triage,
    validate_yaml_against_corpus,
)


def _client_masked_ws_frame(opcode: int, payload: bytes) -> bytes:
    mask_key = b"\x12\x34\x56\x78"
    header = bytearray([0x80 | (opcode & 0x0F)])
    length = len(payload)
    if length < 126:
        header.append(0x80 | length)
    elif length <= 0xFFFF:
        header.append(0x80 | 126)
        header.extend(struct.pack("!H", length))
    else:
        header.append(0x80 | 127)
        header.extend(struct.pack("!Q", length))
    header.extend(mask_key)
    masked = bytes(b ^ mask_key[i % 4] for i, b in enumerate(payload))
    return bytes(header) + masked


class WebRelayAndTriageTest(unittest.TestCase):
    db_path: Path

    @classmethod
    def setUpClass(cls) -> None:
        cls.db_path = DEFAULT_DB_PATH
        if not cls.db_path.is_file():
            build_index(db_path=cls.db_path)

    def test_export_web_corpus_json_includes_all_planes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out_file = Path(tmp) / "support-corpus.json"
            bundle = export_web_corpus_json(db_path=self.db_path, out_path=out_file)
            self.assertTrue(out_file.is_file())
            self.assertGreaterEqual(bundle["counts"]["bit_codes"], 225)
            self.assertGreaterEqual(bundle["counts"]["config_keys"], 880)
            self.assertGreaterEqual(bundle["counts"]["doc_sections"], 500)
            self.assertGreaterEqual(bundle["counts"]["code_sections"], 100)
            self.assertGreater(bundle["counts"]["locale_aliases"], 100)
            self.assertIn("dinero", bundle["synonyms"])

            bit_keys = {b["key"] for b in bundle["bit_codes"]}
            self.assertIn("ACTADDON_NOT_REGISTERED", bit_keys)
            self.assertIn("CLAIM_PLUGIN_NOT_INTEGRATED", bit_keys)

            cfg_keys = {(c["file_path"], c["key_path"]) for c in bundle["config_keys"]}
            self.assertIn(("definitions/regions/default.yml", "vert.maxSlope"), cfg_keys)
            self.assertIn(("definitions/regions/default.yml", "vert.slopeRadius"), cfg_keys)

    def test_validate_yaml_and_run_web_triage_redacts_and_validates(self) -> None:
        conn = sqlite3.connect(str(self.db_path))
        try:
            bad_yaml = "slopeRadius: 99\npassword: superSecretPassword123\n"
            issues = validate_yaml_against_corpus(conn, bad_yaml)
            self.assertTrue(any(i["key_path"] == "vert.slopeRadius" for i in issues), str(issues))

            with tempfile.TemporaryDirectory() as tmp:
                train_log = Path(tmp) / "web-training.jsonl"
                res = run_web_triage(
                    conn=conn,
                    query_text="Seeing CLAIM-0001 and LinearAdjustor slopeRadius issue",
                    config_yaml=bad_yaml,
                    log_text="jdbc:mysql://rtp:myDbSecret99@localhost:3306/rtp",
                    use_llm=False,
                    training_log_path=train_log,
                )
                self.assertTrue(res["ok"])
                self.assertGreaterEqual(res["secret_redactions"], 2)
                self.assertNotIn("superSecretPassword123", res["redacted_input"])
                self.assertNotIn("myDbSecret99", res["redacted_input"])
                self.assertTrue(any(b["key"] == "CLAIM_PLUGIN_NOT_INTEGRATED" for b in res["bit_findings"]))
                self.assertTrue(any(c["class_name"] == "LinearAdjustor" for c in res["code_hits"]))
                self.assertTrue(train_log.is_file())
                logged = json.loads(train_log.read_text(encoding="utf-8").strip())
                self.assertIn("CLAIM_PLUGIN_NOT_INTEGRATED", logged["matched_bit_codes"])
        finally:
            conn.close()

    def test_service_tier_resolution_and_bytebin_store_limits(self) -> None:
        self.assertEqual(
            ("community", COMMUNITY_FRAMES_PER_WINDOW, 1800),
            resolve_service_tier({"X-RTP-Edition": "GITHUB"}),
        )
        self.assertEqual(
            ("pro", PRO_FRAMES_PER_WINDOW, PRO_TTL_SECONDS),
            resolve_service_tier({"X-RTP-Edition": "BUILTBYBIT"}),
        )
        self.assertEqual(
            ("pro", PRO_FRAMES_PER_WINDOW, PRO_TTL_SECONDS),
            resolve_service_tier({"Authorization": "Bearer secret-pro-tok"}, pro_tokens={"secret-pro-tok"}),
        )

        now = [1000.0]
        store = BytebinStore(max_blobs=4, clock=lambda: now[0])
        gz_payload = gzip.compress(b'{"version":1,"files":{"config.yml":"a: 1"}}')
        key = store.put(gz_payload, content_encoding="gzip", tier="pro", ttl_seconds=PRO_TTL_SECONDS)
        fetched = store.get(key)
        self.assertIsNotNone(fetched)
        assert fetched is not None
        self.assertEqual(b'{"version":1,"files":{"config.yml":"a: 1"}}', fetched[1])

        now[0] += PRO_TTL_SECONDS + 1
        self.assertIsNone(store.get(key), "expired blob must be evicted")

    def test_http_and_websocket_relay_end_to_end(self) -> None:
        srv = WebRelayAndTriageServer(("127.0.0.1", 0), db_path=self.db_path, pro_tokens={"pro-secret-1"})
        port = srv.server_address[1]
        thread = threading.Thread(target=srv.serve_forever, daemon=True)
        thread.start()
        try:
            base = f"http://127.0.0.1:{port}"

            # 1. Bytebin POST + GET roundtrip
            payload = gzip.compress(b'{"version":1,"files":{"config.yml":"radius: 2500"}}')
            req = request.Request(
                f"{base}/bytebin/post",
                data=payload,
                headers={
                    "Content-Type": "application/json",
                    "Content-Encoding": "gzip",
                    "X-RTP-Edition": "BUILTBYBIT",
                },
                method="POST",
            )
            with request.urlopen(req, timeout=5) as resp:
                self.assertEqual(200, resp.status)
                self.assertEqual("pro", resp.headers.get("X-RTP-Tier"))
                post_data = json.loads(resp.read().decode("utf-8"))
                key = post_data["key"]

            with request.urlopen(f"{base}/bytebin/{key}", timeout=5) as resp:
                self.assertEqual(200, resp.status)
                fetched_json = json.loads(resp.read().decode("utf-8"))
                self.assertEqual("radius: 2500", fetched_json["files"]["config.yml"])

            # 2. Bytesocks create room (Pro tier negotiation headers)
            create_req = request.Request(
                f"{base}/bytesocks/create",
                headers={"Authorization": "Bearer pro-secret-1"},
                method="GET",
            )
            with request.urlopen(create_req, timeout=5) as resp:
                self.assertEqual(201, resp.status)
                self.assertEqual(str(PRO_FRAMES_PER_WINDOW), resp.headers.get("X-RTP-Frames-Per-Window"))
                self.assertEqual(str(PRO_TTL_SECONDS), resp.headers.get("X-RTP-Session-TTL-Seconds"))
                room_id = json.loads(resp.read().decode("utf-8"))["key"]

            # 3. Connect two WebSocket peers to the room and broadcast a signed frame
            def open_ws() -> socket.socket:
                s = socket.create_connection(("127.0.0.1", port), timeout=5)
                ws_key = base64.b64encode(b"0123456789abcdef").decode("ascii")
                handshake = (
                    f"GET /bytesocks/{room_id} HTTP/1.1\r\n"
                    f"Host: 127.0.0.1:{port}\r\n"
                    "Upgrade: websocket\r\n"
                    "Connection: Upgrade\r\n"
                    f"Sec-WebSocket-Key: {ws_key}\r\n"
                    "Sec-WebSocket-Version: 13\r\n\r\n"
                )
                s.sendall(handshake.encode("ascii"))
                resp_bytes = b""
                while b"\r\n\r\n" not in resp_bytes:
                    resp_bytes += s.recv(1024)
                self.assertIn(b"101 Switching Protocols", resp_bytes)
                return s

            peer_a = open_ws()
            peer_b = open_ws()
            try:
                signed_env = json.dumps({"msg": '{"type":"hello","channel":"' + room_id + '"}', "signature": "AAAA"})
                peer_a.sendall(_client_masked_ws_frame(0x1, signed_env.encode("utf-8")))
                frame = read_ws_frame(peer_b)
                self.assertIsNotNone(frame)
                assert frame is not None
                self.assertEqual(0x1, frame[0])
                self.assertEqual(signed_env, frame[1].decode("utf-8"))
            finally:
                peer_a.close()
                peer_b.close()

            # 4. POST /api/v1/triage
            triage_body = json.dumps({"query": "ACTADDON-0001 maxSlope", "config_yaml": "password: secret12345"}).encode("utf-8")
            triage_req = request.Request(
                f"{base}/api/v1/triage",
                data=triage_body,
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with request.urlopen(triage_req, timeout=5) as resp:
                self.assertEqual(200, resp.status)
                triage_res = json.loads(resp.read().decode("utf-8"))
                self.assertTrue(triage_res["ok"])
                self.assertEqual(1, triage_res["secret_redactions"])
                self.assertTrue(any(b["key"] == "ACTADDON_NOT_REGISTERED" for b in triage_res["bit_findings"]))
        finally:
            srv.shutdown()
            srv.server_close()

    def test_ast_bake_small_llm_scanner_and_bot_stack_trace_bridging(self) -> None:
        from corpus_atlas import explain_leafrtp_query, resolve_config_leaf_via_lca, scan_section_with_small_llm
        from discord_support_bot import ForumPostContext, SupportBotEngine

        conn = sqlite3.connect(str(self.db_path))
        conn.row_factory = sqlite3.Row
        try:
            for tbl in ("ast_symbols", "ast_candidate_refs", "ast_edges", "ast_claims", "ast_conflicts", "ast_config_tree"):
                cnt = conn.execute(f"SELECT count(*) FROM {tbl}").fetchone()[0]
                self.assertGreater(cnt, 0, f"AST table {tbl} should be populated")

            # Verify LCA flat-YAML leaf resolution via ast_config_tree / config_keys
            resolved_lca = resolve_config_leaf_via_lca(conn, "name", ["minY", "maxY", "requireSkyLight"], project="leafrtp")
            self.assertIsNotNone(resolved_lca)
            self.assertIn("vert.name", str(resolved_lca))

            # Verify explain_leafrtp_query traverses symptoms, edges, and conflicts
            exp = explain_leafrtp_query(conn, "Every time players type /rtp it opens a chest inventory GUI instead of teleporting them")
            self.assertTrue(any("openOnBareRtp" in e["dst"] for e in exp["ast_subgraph_edges"]), str(exp))

            exp_lag = explain_leafrtp_query(conn, "My server has lag spikes during RTP, should I set syncLoading: true or give players rtp.unqueued?")
            self.assertGreaterEqual(len(exp_lag["flagged_conflicts"]), 1, str(exp_lag))

            # Verify small-LLM JSON AST scanner hook rejects hallucinated symbol IDs and keeps valid ones
            valid_ids = {"cfg:addons/guimenu.yml:openOnBareRtp", "cmd:/rtp reload"}
            fake_llm = lambda _ep, _payload, _to: {
                "response": json.dumps({
                    "claims": [
                        {"src": "cfg:addons/guimenu.yml:openOnBareRtp", "predicate": "MITIGATED_BY_COMMAND", "dst": "cmd:/rtp reload", "polarity": "AFFIRMED", "quote": "Reload after editing guimenu.yml"},
                        {"src": "cfg:hallucinated:fakeKey", "predicate": "GOVERNED_BY_KEY", "dst": "cmd:/rtp reload", "polarity": "AFFIRMED", "quote": "Hallucinated"},
                    ]
                })
            }
            verified_claims = scan_section_with_small_llm("openOnBareRtp in guimenu.yml", valid_ids, http_post_fn=fake_llm)
            self.assertEqual(1, len(verified_claims))
            self.assertEqual("cfg:addons/guimenu.yml:openOnBareRtp", verified_claims[0]["src"])

            # Verify SupportBotEngine bridges Java AST classes to config_keys and bit_codes
            eng = SupportBotEngine(conn=conn, cooldown_seconds=0.0, log_fetcher=lambda _id: "")
            for idx, (title, body, expected_token) in enumerate([
                ("JumpAdjustor crash", "IllegalArgumentException at io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor.adjust(JumpAdjustor.java:88) on LeafRTP 3.3.1 Paper 1.21.4 https://mclo.gs/abc1234", "maxY"),
                ("ClaimSetupBit warning", "Warning in io.github.dailystruggle.rtp.claimaddon.ClaimSetupBit.report(ClaimSetupBit.java:56) for GriefPrevention on LeafRTP 3.3.1 Paper 1.21.4 https://mclo.gs/abc1234", "CLAIM_PLUGIN_NOT_INTEGRATED"),
                ("ActionSetupBit trigger", "Error in io.github.dailystruggle.rtp.actionaddon.ActionSetupBit.report(ActionSetupBit.java:93) on LeafRTP 3.3.1 Paper 1.21.4 https://mclo.gs/abc1234", "ACTADDON_TRIGGER_INACTIVE"),
                ("TickBudget warning", "Issue in io.github.dailystruggle.rtp.common.tasks.TickBudget.tick(TickBudget.java:77) on LeafRTP 3.3.1 Paper 1.21.4 https://mclo.gs/abc1234", "syncAllottedTime"),
            ]):
                dec = eng.handle_new_thread(
                    ForumPostContext(
                        thread_id=f"ast_t_{idx}",
                        author_id=f"u_{idx}",
                        author_name="tester",
                        title=title,
                        body=body,
                        applied_tags=("🌿 LeafRTP",),
                        timestamp=2000.0 + idx,
                    )
                )
                self.assertIn(expected_token.lower(), dec.reply_text.lower(), f"Failed for {title}: {dec.reply_text}")
        finally:
            conn.close()

    def test_headless_browser_official_docs_and_editor_html_e2e(self) -> None:
        import shutil
        import subprocess
        import time

        browser = None
        for cand in ("/usr/bin/firefox", "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser"):
            if Path(cand).is_file():
                browser = cand
                break
        if not browser:
            self.skipTest("No headless browser (/usr/bin/firefox or chromium) installed")

        srv = WebRelayAndTriageServer(("127.0.0.1", 0), db_path=self.db_path)
        port = srv.server_address[1]
        thread = threading.Thread(target=srv.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                # Build a test page on the same origin that mounts rtp-support-doctor.js against the official corpus
                # and drives its HTML DOM (#rtp-doctor-fab -> #rtp-doctor-input -> #rtp-doctor-run -> #rtp-doctor-output)
                probe_html = (REPO_ROOT := BOT_DIR.parent) / "docs" / "assets" / "e2e-doctor-probe.html"
                probe_html.write_text(
                    """<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Official Docs Doctor E2E</title></head>
<body>
  <article class="md-content__inner"><h1>LeafRTP Official Docs</h1></article>
  <script src="/RTP/assets/javascripts/rtp-support-doctor.js"></script>
  <script>
  (async () => {
    const wait = (pred, ms) => new Promise((ok) => {
      const t0 = Date.now();
      const iv = setInterval(() => { if (pred()) { clearInterval(iv); ok(true); } else if (Date.now() - t0 > ms) { clearInterval(iv); ok(false); } }, 40);
    });
    await wait(() => !!document.getElementById('rtp-doctor-fab'), 5000);
    const fab = document.getElementById('rtp-doctor-fab');
    fab.click();
    const modal = document.getElementById('rtp-doctor-modal');
    const inp = document.getElementById('rtp-doctor-input');
    const runBtn = document.getElementById('rtp-doctor-run');
    const copyBtn = document.getElementById('rtp-doctor-copy');
    const out = document.getElementById('rtp-doctor-output');
    inp.value = [
      'ACTADDON-0001 CLAIM-0004 at io.github.dailystruggle.rtp.common.tasks.TickBudget.tick(TickBudget.java:77)',
      'slopeRadius: 999',
      'dinero',
      'password: "topSecretPassword99"'
    ].join('\\n');
    runBtn.click();
    await wait(() => out.textContent.includes('ACTADDON_NOT_REGISTERED') && out.textContent.includes('TickBudget'), 10000);
    copyBtn.click();
    await wait(() => typeof window.__lastCopiedDoctorSummary === 'string', 5000);
    await fetch('/bytebin/post', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        modalOpen: modal.style.display === 'flex',
        outputText: out.textContent,
        copied: window.__lastCopiedDoctorSummary
      })
    });
  })();
  </script>
</body>
</html>""",
                    encoding="utf-8",
                )
                try:
                    prof = tmp_path / "profile"
                    prof.mkdir(parents=True, exist_ok=True)
                    url = f"http://127.0.0.1:{port}/RTP/assets/e2e-doctor-probe.html"
                    if "firefox" in browser:
                        cmd = [browser, "--headless", "--no-remote", "--profile", str(prof), url]
                    else:
                        cmd = [browser, "--headless=new", "--disable-gpu", "--no-first-run", f"--user-data-dir={prof}", url]
                    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    try:
                        t0 = time.time()
                        report = None
                        while time.time() - t0 < 25.0:
                            if srv.bytebin._blobs:
                                key = next(iter(srv.bytebin._blobs))
                                fetched = srv.bytebin.get(key)
                                if fetched is not None:
                                    report = json.loads(fetched[1].decode("utf-8"))
                                    break
                            time.sleep(0.1)
                        self.assertIsNotNone(report, "Headless browser did not post DOM probe report in time")
                        assert report is not None
                        self.assertTrue(report["modalOpen"])
                        self.assertIn("Automatically redacted 1 secret", report["outputText"])
                        self.assertIn("YAML Schema & Range Issues", report["outputText"])
                        self.assertIn("vert.slopeRadius", report["outputText"])
                        self.assertIn("ACTADDON_NOT_REGISTERED", report["outputText"])
                        self.assertIn("CLAIM_PLUGIN_ABSENT", report["outputText"])
                        self.assertIn("TickBudget", report["outputText"])
                        self.assertIn("syncAllottedTime", report["outputText"])
                        self.assertIn("***REDACTED***", report["copied"])
                        self.assertNotIn("topSecretPassword99", report["copied"])
                    finally:
                        proc.terminate()
                        try:
                            proc.wait(timeout=5)
                        except Exception:
                            proc.kill()
                finally:
                    probe_html.unlink(missing_ok=True)
        finally:
            srv.shutdown()
            srv.server_close()


if __name__ == "__main__":
    unittest.main()
