"""Unit test suite for scripts/release/build_front_pages.py (storefront single source)."""

import pathlib
import tempfile
import unittest

from scripts.release.build_front_pages import (
    RAW_BASE,
    SOURCE,
    TARGETS,
    FrontPageError,
    build_all,
    check_denylist,
    inline_bbcode,
    main,
    render,
    select_lines,
)

NOTES = "<!--\nauthor notes, never emitted\n-->\n"


def _render(body, target):
    return render(NOTES + body, TARGETS[target])[0]


class MarkerTest(unittest.TestCase):

    def test_author_notes_are_dropped(self):
        out = _render("Hello\n", "hangar")
        self.assertNotIn("author notes", out)
        self.assertIn("Hello", out)

    def test_only_and_not_select_audience_copy(self):
        body = ("<!-- only: modrinth -->\nloaders first\n<!-- /only -->\n"
                "<!-- not: modrinth -->\nfolia first\n<!-- /not -->\n")
        self.assertIn("loaders first", _render(body, "modrinth"))
        self.assertNotIn("folia first", _render(body, "modrinth"))
        self.assertIn("folia first", _render(body, "hangar"))
        self.assertNotIn("loaders first", _render(body, "hangar"))

    def test_aliases_expand(self):
        body = "<!-- only: bbb -->\nbbb copy\n<!-- /only -->\n"
        self.assertIn("bbb copy", _render(body, "bbb-pro"))
        self.assertIn("bbb copy", _render(body, "bbb-lite"))
        self.assertNotIn("bbb copy", _render(body, "hangar"))

    def test_policy_drops_promo_on_markdown_storefronts(self):
        body = "shared\n<!-- kind: promo -->\nsupport pitch\n<!-- /kind -->\n"
        for target in ("modrinth", "hangar"):
            self.assertNotIn("support pitch", _render(body, target))
        for target in ("bbb-pro", "bbb-lite"):
            self.assertIn("support pitch", _render(body, target))

    def test_nested_blocks_need_every_level_to_allow(self):
        body = "<!-- only: bbb-pro, hangar -->\n<!-- kind: promo -->\nx-line\n<!-- /kind -->\n<!-- /only -->\n"
        self.assertIn("x-line", _render(body, "bbb-pro"))
        self.assertNotIn("x-line", _render(body, "bbb-lite"))
        self.assertNotIn("x-line", _render(body, "hangar"))

    def test_variables_expand_per_target(self):
        self.assertIn("LeafRTP-Pro-x.y.z.jar", _render("{{jar}}\n", "bbb-pro"))
        self.assertIn("LeafRTP-x.y.z.jar", _render("{{jar}}\n", "bbb-lite"))

    def test_malformed_markup_fails(self):
        cases = {
            "unclosed": "<!-- only: hangar -->\nx\n",
            "mismatched": "<!-- only: hangar -->\nx\n<!-- /kind -->\n",
            "stray close": "x\n<!-- /only -->\n",
            "unknown target": "<!-- only: curseforge -->\nx\n<!-- /only -->\n",
            "unknown kind": "<!-- kind: advert -->\nx\n<!-- /kind -->\n",
            "empty list": "<!-- only: -->\nx\n<!-- /only -->\n",
            "stray comment": "x <!-- note --> y\n",
            "unknown variable": "{{price}}\n",
            "latex": "Y=$250+$\n",
            "relative link": "[guide](../admin/GUIDE.md)\n",
        }
        for name, body in cases.items():
            with self.subTest(name), self.assertRaises(FrontPageError):
                _render(body, "bbb-lite")

    def test_audience_share_counts_only_and_not_lines(self):
        body = "a\nb\n<!-- only: hangar -->\nc\n<!-- /only -->\n<!-- kind: promo -->\nd\n<!-- /kind -->\n"
        _, specific, shared = select_lines(NOTES + body, "hangar")
        self.assertEqual((1, 3), (specific, shared))


class DenylistTest(unittest.TestCase):

    def test_untagged_paid_copy_fails_the_markdown_storefronts(self):
        for line in ("Get LeafRTP Pro today", "Buy the support tier", "Now on BuiltByBit",
                     "Only $5", "Become a sponsor"):
            for target in ("hangar", "modrinth"):
                with self.subTest(line=line, target=target), self.assertRaises(FrontPageError):
                    _render(line + "\n", target)

    def test_bbb_targets_are_not_denylisted(self):
        self.assertIn("LeafRTP-Pro", _render("LeafRTP-Pro listing\n", "bbb-lite"))

    def test_lookalike_words_pass(self):
        self.assertEqual([], check_denylist(TARGETS["hangar"],
                                            "ProtocolLib, Protection, per-region pricing, pays for every candidate"))


class BBCodeTest(unittest.TestCase):

    def test_inline_conversion(self):
        self.assertEqual("[B]bold[/B] [I]code *[x=1][/I] [URL='https://a.b/']text[/URL]",
                         inline_bbcode("**bold** `code *[x=1]` [text](https://a.b/)"))
        self.assertEqual("[URL='https://l/'][IMG]https://i/x.svg[/IMG][/URL]",
                         inline_bbcode("[![badge](https://i/x.svg)](https://l/)"))
        self.assertEqual("see below", inline_bbcode("[see below](#section)"))
        self.assertEqual("[I]italic[/I] and 3 * 4", inline_bbcode("*italic* and 3 * 4"))

    def test_block_conversion(self):
        body = ('<div align="center">\n\n# Title\n\n</div>\n\n## Section\n\n### Sub\n\n'
                "- one\n  - nested\n- two\n\n"
                "| A | B |\n|---|---|\n| `x\\|y` | **2** |\n\n"
                "<details>\n<summary><b>More \"info\"</b></summary>\n\nbody\n\n</details>\n")
        out = _render(body, "bbb-lite")
        self.assertIn("[CENTER]\n[SIZE=7][B][COLOR=#27AE60]Title[/COLOR][/B][/SIZE]\n[/CENTER]", out)
        self.assertIn("[SIZE=6][B][COLOR=#27AE60]Section[/COLOR][/B][/SIZE]", out)
        self.assertIn("[SIZE=4][B]Sub[/B][/SIZE]", out)
        self.assertIn("[LIST]\n[*]one\n[LIST]\n[*]nested\n[/LIST]\n[*]two\n[/LIST]", out)
        self.assertIn("[TABLE]\n[TR][TH]A[/TH][TH]B[/TH][/TR]\n[TR][TD][I]x|y[/I][/TD][TD][B]2[/B][/TD][/TR]\n[/TABLE]",
                      out)
        self.assertIn("[SPOILER=\"More 'info'\"]\nbody\n[/SPOILER]", out)

    def test_unsupported_constructs_fail(self):
        for body in ("```\ncode\n```\n", "> quote\n", "<table>\n", "<details>\nno summary\n</details>\n",
                     "| no separator |\n| row |\n"):
            with self.subTest(body=body), self.assertRaises(FrontPageError):
                _render(body, "bbb-pro")


class CommittedSourceTest(unittest.TestCase):

    def test_committed_source_builds_every_target(self):
        outputs, _ = build_all(SOURCE.read_text(encoding="utf-8"))
        self.assertEqual(set(TARGETS), set(outputs))

    def test_generation_is_deterministic(self):
        text = SOURCE.read_text(encoding="utf-8")
        self.assertEqual(build_all(text), build_all(text))

    def test_build_writes_every_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = pathlib.Path(tmp) / "generated"
            self.assertEqual(0, main(["--out-dir", str(out)]))
            self.assertEqual(sorted(t.filename for t in TARGETS.values()),
                             sorted(p.name for p in out.iterdir()))

    def test_check_mode_validates_without_writing(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = pathlib.Path(tmp) / "generated"
            self.assertEqual(0, main(["--check", "--out-dir", str(out)]))
            self.assertFalse(out.exists())
            bad = pathlib.Path(tmp) / "FRONT_PAGE.md"
            bad.write_text(NOTES + "Buy the support tier\n", encoding="utf-8")
            self.assertEqual(1, main(["--check", "--source", str(bad), "--out-dir", str(out)]))
            self.assertFalse(out.exists())


class LocalImageTest(unittest.TestCase):

    def test_local_image_resolves_to_absolute_url(self):
        body = "![Web editor](../assets/img/web_editor.png)\n"
        out_md = _render(body, "hangar")
        self.assertIn("![Web editor](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/web_editor.png)", out_md)
        out_bb = _render(body, "bbb-pro")
        self.assertIn("[IMG]https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/web_editor.png[/IMG]", out_bb)

    def test_repo_root_relative_image_resolves(self):
        body = "![Web editor](docs/assets/img/web_editor.png)\n"
        out = _render(body, "modrinth")
        self.assertIn("https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/web_editor.png", out)

    def test_multiple_images_on_same_line_resolve(self):
        body = "![one](../assets/img/menu_1.png) ![two](../assets/img/menu_2.png)\n"
        out = _render(body, "bbb-lite")
        self.assertIn("[IMG]https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/menu_1.png[/IMG] [IMG]https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/menu_2.png[/IMG]", out)

    def test_nonexistent_local_image_fails(self):
        body = "![ghost](../assets/img/nonexistent_image_123.png)\n"
        with self.assertRaises(FrontPageError) as ctx:
            _render(body, "modrinth")
        self.assertIn("does not exist on disk", str(ctx.exception))

    def test_absolute_image_url_preserved(self):
        body = "![ext](https://example.com/ext.png)\n"
        out = _render(body, "hangar")
        self.assertIn("https://example.com/ext.png", out)

    def test_custom_raw_base(self):
        body = "![pic](../assets/img/menu_1.png)\n"
        custom_base = "https://cdn.example.com/rtp"
        out = render(NOTES + body, TARGETS["modrinth"], raw_base=custom_base)[0]
        self.assertIn("https://cdn.example.com/rtp/docs/assets/img/menu_1.png", out)


if __name__ == "__main__":
    unittest.main()
