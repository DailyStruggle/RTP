"""Temporary: apply the baseline messages/ split to every locale.

Reads the English baseline partition (which member file each enum key lives in)
from rtp-plugin/.../resources/messages/*.yml, then for each lang/<locale>/messages.yml
distributes its top-level key blocks into lang/<locale>/messages/<member>.yml using
the same partition. A locale's translated key is mapped back to its enum via the
effective key map (locale messages.lang.yml -> baseline messages.lang.yml -> identity),
mirroring ConfigParser / LocaleParityTest resolution.

The split is VERBATIM and block-based: each top-level key, together with its
preceding comment/blank lines and any indented continuation lines, is moved as-is
into its member file. This preserves translated comments, translated key names,
exact values, and quoting. It does NOT round-trip through a YAML loader/dumper,
which previously dropped every comment and corrupted some non-ASCII keys to "???".

Additive and idempotent: the single lang/<locale>/messages.yml is left in place so
the current runtime keeps working. Corrupted/unparseable working-tree locale files
can be read from git HEAD with --from-head instead.

Usage:
  python scripts/split-locale-messages.py            # write the split tree (working tree)
  python scripts/split-locale-messages.py --from-head # read each locale from git HEAD
  python scripts/split-locale-messages.py --dry-run  # report only
"""
import io
import subprocess
import sys
from pathlib import Path
import yaml

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

DRY_RUN = "--dry-run" in sys.argv
# Read each locale's messages.yml + lang map from git HEAD instead of the working
# tree. Use this when the working-tree locale files carry uncommitted corruption.
FROM_HEAD = "--from-head" in sys.argv

REPO = Path(__file__).resolve().parent.parent
RES = REPO / "rtp-plugin" / "src" / "main" / "resources"
BASELINE_DIR = RES / "messages"
LANG_ROOT = RES / "lang"
BASELINE_LANG_MAP = LANG_ROOT / "messages.lang.yml"
MEMBERS = ["placeholders.yml", "player.yml", "network.yml", "commands.yml", "system.yml"]
NON_LOCALE = {"shape", "vert"}


def load_yaml(path):
    return yaml.safe_load(path.read_text(encoding="utf-8")) or {}


def head_text(path):
    """Content of `path` at git HEAD as UTF-8, or None if absent there."""
    rel = path.relative_to(REPO).as_posix()
    r = subprocess.run(["git", "show", f"HEAD:{rel}"], cwd=REPO, capture_output=True)
    if r.returncode != 0:
        return None
    return r.stdout.decode("utf-8")


def read_text(path):
    """Raw UTF-8 text of `path` from HEAD (when --from-head) else the working tree."""
    if FROM_HEAD:
        return head_text(path)
    if not path.exists():
        return None
    return path.read_text(encoding="utf-8")


def read_yaml_text(path):
    txt = read_text(path)
    if txt is None:
        return None
    return txt


def load_lang_map(path):
    txt = read_text(path)
    if not txt:
        return {}
    raw = yaml.safe_load(txt) or {}
    return {str(k): str(v) for k, v in raw.items()}


def dedup_p0(lines):
    """Drop a redundant standalone top-level `# P0` comment line.

    The locale sources carry both a bare top-level `# P0` comment above the
    placeholders key and an indented `# P0` comment on the list entry. The two
    are duplicates; keep the indented (inner) one and drop the top-level bare one.
    """
    has_inner = any(ln[:1] in (" ", "\t") and ln.strip() == "# P0" for ln in lines)
    out = []
    for ln in lines:
        if has_inner and ln.strip() == "# P0" and ln[:1] not in (" ", "\t"):
            continue
        out.append(ln)
    return out


def is_top_key(line):
    """True for a top-level YAML mapping key line (not indented/comment/blank)."""
    if not line:
        return False
    if line[0] in " \t":
        return False
    if line.lstrip().startswith("#"):
        return False
    return ":" in line


def parse_blocks(text):
    """Split raw YAML text into (key, [verbatim lines]) blocks.

    Each block holds a single top-level key with its preceding comment/blank
    lines and any indented continuation lines. Leading comments before the first
    key attach to that first key's block.
    """
    blocks = []
    pending = []          # comment/blank lines awaiting the next key
    cur_key = None
    cur_lines = []
    for line in text.split("\n"):
        if is_top_key(line):
            if cur_key is not None:
                blocks.append((cur_key, cur_lines))
            key = line.split(":", 1)[0].strip()
            cur_lines = pending + [line]
            pending = []
            cur_key = key
        elif line[:1] in (" ", "\t"):
            if cur_key is not None:
                cur_lines.append(line)
            else:
                pending.append(line)
        else:
            # blank line or top-level comment -> belongs before the next key
            pending.append(line)
    if cur_key is not None:
        blocks.append((cur_key, cur_lines))
    return blocks, pending  # trailing pending (after last key) dropped on output


# enum key (== baseline key name) -> member file
partition = {}
for m in MEMBERS:
    for k in load_yaml(BASELINE_DIR / m):
        partition[k] = m

baseline_map = load_lang_map(BASELINE_LANG_MAP)  # enum -> baseline (English) key name

HEADER = ("# --- RTP messages (this language) ---\n"
          "# Player- and operator-facing message strings for this language, grouped by concern.\n"
          "# Auto-generated mirror of the English messages; do not hand-edit (changes are overwritten).\n")

# Per-locale translated file header (3 lines). Falls back to the English HEADER
# above for any locale not listed. The header tells operators what the file is
# for, in the locale's own language - not which tool produced it.
HEADERS = {
    "de": ("# --- RTP-Nachrichten (diese Sprache) ---\n"
           "# Nachrichtentexte fuer Spieler und Operatoren in dieser Sprache, nach Themen gruppiert.\n"
           "# Automatisch aus den englischen Nachrichten erzeugt; nicht von Hand bearbeiten (wird ueberschrieben).\n"),
    "es": ("# --- Mensajes de RTP (este idioma) ---\n"
           "# Textos de mensajes para jugadores y operadores en este idioma, agrupados por tema.\n"
           "# Copia generada automaticamente de los mensajes en ingles; no editar a mano (se sobrescribe).\n"),
    "fr": ("# --- Messages RTP (cette langue) ---\n"
           "# Textes des messages destines aux joueurs et aux operateurs dans cette langue, groupes par theme.\n"
           "# Copie generee automatiquement des messages anglais; ne pas editer a la main (sera ecrase).\n"),
    "it": ("# --- Messaggi RTP (questa lingua) ---\n"
           "# Testi dei messaggi per giocatori e operatori in questa lingua, raggruppati per argomento.\n"
           "# Copia generata automaticamente dai messaggi inglesi; non modificare a mano (viene sovrascritto).\n"),
    "nl": ("# --- RTP-berichten (deze taal) ---\n"
           "# Berichtteksten voor spelers en operators in deze taal, gegroepeerd per onderwerp.\n"
           "# Automatisch gegenereerde kopie van de Engelse berichten; niet met de hand bewerken (wordt overschreven).\n"),
    "pl": ("# --- Komunikaty RTP (ten jezyk) ---\n"
           "# Teksty komunikatow dla graczy i operatorow w tym jezyku, pogrupowane tematycznie.\n"
           "# Automatycznie wygenerowana kopia angielskich komunikatow; nie edytowac recznie (zostanie nadpisane).\n"),
    "pt": ("# --- Mensagens do RTP (este idioma) ---\n"
           "# Textos de mensagens para jogadores e operadores neste idioma, agrupados por tema.\n"
           "# Copia gerada automaticamente das mensagens em ingles; nao editar a mao (sera sobrescrito).\n"),
    "ru": ("# --- Сообщения RTP (этот язык) ---\n"
           "# Тексты сообщений для игроков и операторов на этом языке, сгруппированные по темам.\n"
           "# Автоматически созданная копия английских сообщений; не редактировать вручную (будет перезаписано).\n"),
    "ja": ("# --- RTP のメッセージ (この言語) ---\n"
           "# この言語でのプレイヤーおよび運営者向けメッセージ文。テーマごとに分類されています。\n"
           "# 英語版メッセージから自動生成された複製です。手動で編集しないでください (上書きされます)。\n"),
    "ko": ("# --- RTP 메시지 (이 언어) ---\n"
           "# 이 언어의 플레이어 및 운영자용 메시지 문구로, 주제별로 분류되어 있습니다.\n"
           "# 영어 메시지에서 자동 생성된 사본입니다. 직접 편집하지 마세요 (덮어쓰여집니다).\n"),
    "zh": ("# --- RTP 消息（本语言） ---\n"
           "# 本语言面向玩家和管理员的消息文本，按主题分组。\n"
           "# 由英文消息自动生成的副本；请勿手动编辑（会被覆盖）。\n"),
    "cat": ("# --- RTP mews (this kitty's tongue) ---\n"
            "# Player- and staff-facing mews for this language, grouped by what they're for.\n"
            "# Auto-copied from the baseline mews; do not paw-edit (it gets overwritten).\n"),
}

summary = []
for locale_dir in sorted(p for p in LANG_ROOT.iterdir() if p.is_dir()):
    locale = locale_dir.name
    if locale in NON_LOCALE:
        continue
    msg = locale_dir / "messages.yml"
    text = read_yaml_text(msg)
    if text is None:
        summary.append(f"  {locale}: SKIP (no messages.yml)")
        continue
    try:
        blocks, _ = parse_blocks(text)
    except Exception as e:  # pragma: no cover - defensive
        summary.append(f"  {locale}: SKIP (block parse failed: {e})".replace("\n", " "))
        continue

    locale_map = load_lang_map(locale_dir / "messages.lang.yml")
    # effective enum -> translated key name; invert to translated name -> enum
    inv = {}
    for enum in partition:
        tname = locale_map.get(enum) or baseline_map.get(enum) or enum
        inv[tname] = enum

    per_member = {m: [] for m in MEMBERS}
    unrouted = []
    for key, lines in blocks:
        enum = inv.get(key, key)
        member = partition.get(enum)
        if member is None:
            unrouted.append(key)
            member = "player.yml"  # safe fallback
        per_member[member].append(lines)

    note = f" (unrouted->player.yml: {unrouted})" if unrouted else ""
    if DRY_RUN:
        counts = ", ".join(f"{m.split('.')[0]}={len(per_member[m])}" for m in MEMBERS)
        summary.append(f"  {locale}: OK [{counts}]{note}")
        continue

    header = HEADERS.get(locale, HEADER)
    out_dir = locale_dir / "messages"
    out_dir.mkdir(exist_ok=True)
    for m in MEMBERS:
        parts = []
        for block_lines in per_member[m]:
            lines = block_lines
            if m == "placeholders.yml":
                lines = dedup_p0(lines)
            parts.append("\n".join(lines))
        body = "\n".join(parts).strip("\n")
        out = header + "\n" + body + "\n"
        (out_dir / m).write_text(out, encoding="utf-8")
    summary.append(f"  {locale}: WROTE {out_dir.relative_to(REPO)}{note}")

print(("DRY RUN - " if DRY_RUN else "") + "locale message split:")
print("\n".join(summary))
