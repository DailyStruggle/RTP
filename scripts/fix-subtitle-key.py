"""One-off: repair the `subtitle` enum's localized key name in ja/ko/ru/zh.

The translated key name was corrupted to literal `?` (0x3F) chars in the value
file (messages.yml), the lang-map RHS (messages.lang.yml), and the split member
(messages/player.yml). Values are intact; only the key name is replaced, kept in
sync across all three files so parity and runtime resolution hold.
"""
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LANG = REPO / "rtp-plugin/src/main/resources/lang"

# localized key name for the `subtitle` enum
NAMES = {
    "ja": "\u30b5\u30d6\u30bf\u30a4\u30c8\u30eb",  # サブタイトル
    "ko": "\ubd80\uc81c\ubaa9",                      # 부제목
    "ru": "\u043f\u043e\u0434\u0437\u0430\u0433\u043e\u043b\u043e\u0432\u043e\u043a",  # подзаголовок
    "zh": "\u526f\u6807\u9898",                      # 副标题
}


def fix_value_file(path, name):
    lines = path.read_text(encoding="utf-8").split("\n")
    out = []
    for ln in lines:
        if ln.startswith("?") and ': "<gradient' in ln:
            out.append(name + ln[ln.index(":"):])
        else:
            out.append(ln)
    path.write_text("\n".join(out), encoding="utf-8")


def fix_lang_map(path, name):
    lines = path.read_text(encoding="utf-8").split("\n")
    out = [(f'subtitle: "{name}"' if ln.startswith("subtitle:") else ln) for ln in lines]
    path.write_text("\n".join(out), encoding="utf-8")


for loc, name in NAMES.items():
    d = LANG / loc
    fix_value_file(d / "messages.yml", name)
    fix_value_file(d / "messages" / "player.yml", name)
    fix_lang_map(d / "messages.lang.yml", name)
    print(f"{loc}: subtitle key fixed")
