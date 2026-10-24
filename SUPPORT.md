# Getting Support for RTP

RTP is maintained by a single developer in their spare time. Please read this
page **before** opening an issue or pinging in chat — it tells you where your
question belongs and how to ask it so you actually get a useful answer.

---

## Before you ask

Check these first. Most questions are already answered:

1. **Documentation site** (searchable) — <https://dailystruggle.github.io/RTP/>.
   The same pages ship inside the jar (unpacked to `plugins/RTP/docs/`, or `config/rtp/docs/` on Fabric / NeoForge) and live in this
   repo under [`docs/`](docs/). They replace the old GitHub Wiki.
2. **Server-admin docs** — <https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/>
   ([`docs/FOR_SERVER_ADMINS.md`](docs/FOR_SERVER_ADMINS.md)) and the rest of
   [`docs/admin/`](docs/admin/).
3. **FAQ** — <https://dailystruggle.github.io/RTP/admin/FAQ/>
   ([`docs/admin/FAQ.md`](docs/admin/FAQ.md))
4. **Commands reference** — <https://dailystruggle.github.io/RTP/admin/COMMANDS/>
   ([`docs/admin/COMMANDS.md`](docs/admin/COMMANDS.md))
5. **Resource page** — <https://www.spigotmc.org/resources/rtp.94812/>
6. **Existing issues** — search **open *and* closed** issues; your problem has
   probably been reported.
7. **Update first** — confirm the problem reproduces on the latest release
   (`/rtp version`). Old builds do not get support.

---

## Where to ask - pick the right channel

| Intent | Channel |
|---|---|
| Interactive setup help, `[RTP bit]` log triage, or quick config questions | **LeafCraft Plugin Support** Discord `#support` Forum ([server layout & guide](docs/publishing/DISCORD_LAYOUT.md)) |
| "How do I configure / use X?" (long-form question, not a bug) | [GitHub Discussions](https://github.com/DailyStruggle/RTP/discussions) or Discord `#support` Forum |
| Real-time chat with the community | Discord `#general` or SpigotMC resource discussion thread |
| Suspected bug (crash, stack trace, broken behavior) | [New bug report](https://github.com/DailyStruggle/RTP/issues/new?template=bug_report.md) or Discord `#support` Forum (with `/rtp version`, `/version`, `/rtp test`, and an `mclo.gs` log) |
| LeafRTP Pro priority support (`@Pro Supporter`) | Discord `#support` Forum (automatically tagged `💎 Pro Priority` via `#bbb-logs`) or BuiltByBit ticket |
| Feature idea | Open a **Discussion** or Discord `#support` thread first; issues only after triage |
| **Security vulnerability** | **Do not open a public issue or Discord thread.** See [`SECURITY.md`](SECURITY.md) and use [private advisories](https://github.com/DailyStruggle/RTP/security/advisories/new) |
| Addon development help | [Addon developer guide](https://dailystruggle.github.io/RTP/FOR_ADDON_DEVELOPERS/) first, then Discord `#development` or Discussions |

**Do not** use the GitHub issue tracker for questions, "how do I" requests, or
plugin-conflict debugging without first reproducing with RTP alone. Those
will be closed and redirected to Discord `#support` or GitHub Discussions.

---

## How to ask well

A report without the following will be closed without a reply:

- **Platform** — Spigot, Paper, or Folia (choose one; write "I'm not sure"
  only if you genuinely don't know and then attach `/version` output).
- **Server version** — full output of `/version`.
- **RTP version** — output of `/rtp version`.
- **Java version** — output of `java -version` on the server host.
- **Full `latest.log`** — uploaded to <https://mclo.gs/> or a pastebin.
  Screenshots of console text are not acceptable; the text must be searchable.
- **Reproduction steps** — numbered, starting from a clean server if
  possible.
- **Expected vs. actual behavior** — one sentence each.
- **Config diff** — if you changed `config.yml` / `regions/*.yml` /
  `messages.yml`, show the diff from default.
- **Other plugins** — list them, and ideally reproduce with RTP alone.

The [`bug_report.md`](.github/ISSUE_TEMPLATE/bug_report.md) template walks
you through all of the above. Use it.

---

## Supported Versions and Maintenance Windows

Bug fixes and patches are released according to the following support tiers:

| Line / Version | Support Level | Maintenance Window | Notes |
|---|---|---|---|
| **Current Stable (3.x)** | **Active** | Through next major release + 3 months | Receives bug fixes, security patches, platform compatibility updates, and performance improvements. |
| **Previous Stable (2.x)** | **Maintenance / Critical Only** | 6 months post-3.0 release | Critical security advisories and fatal data-loss bugs only. No feature backports. |
| **Legacy (1.x and older)** | **End of Life (EOL)** | None | Unsupported. Upgrade required before filing issues. |

**Minecraft versions:** bug reports are accepted for Minecraft **1.21.11 through
the latest release**, on Spigot, Paper, Folia, Fabric and NeoForge. Older
versions RTP still loads on (down to 1.18 on Paper and Spigot) are best-effort:
reports from them may be closed without a fix. A later release will deprecate
1.21.11, which moves this window up; it does not remove 1.21.11 support.
Folia is the exception: it is experimental and only its current builds are
maintained upstream, so Folia below 1.21.11 is not supported at all, and
reports from it are closed.

For platform, Minecraft, and Java runtime compatibility, refer to the canonical [Platform Support Matrix](docs/dev/SUPPORT_MATRIX.md).

---

## Response Expectations & Timelines

RTP is actively maintained by an open-source team. While we do not offer commercial SLA guarantees, incoming reports are triaged under the following expected response windows:

- **Initial Triage & Acknowledgement:** Typically within **3 to 7 business days** for well-formed reports containing complete logs and reproduction steps.
- **Bug Fix Delivery:** Confirmed defects on tested platforms are typically addressed in the next patch release (typically **2 to 4 weeks**), or an intermediate snapshot build if critical.
- **Critical Security Hazards:** Handled on an expedited track via [`SECURITY.md`](SECURITY.md) (acknowledgement within **72 hours**, triage within **7 days**).
- **Pro Support Priority:** Operators running LeafRTP Pro on BuiltByBit receive the `@Pro Supporter` role (synced via `#bbb-logs`), which automatically applies the `💎 Pro Priority` tag in the Discord `#support` Forum and routes confirmed issues to the front of my queue (24-72 h weekday target).
- **Incomplete Reports:** Reports missing required reproduction steps, configuration diffs, or `latest.log` links may be flagged or closed until the required context is provided.
- Pinging the maintainer in DMs, other repositories, or unrelated threads will not speed anything up and is covered by the [Code of Conduct](CODE_OF_CONDUCT.md).

---

## What will not get a reply

- "It doesn't work." / "Please fix." / "Urgent!!" with no logs or context.
- Reports against **cracked / pirated / unofficial** server builds.
- Requests for compatibility with abandoned forks or unsupported Minecraft
  versions — see the README for the current support matrix.
- Demands, threats, or entitled messaging — see the
  [Code of Conduct](CODE_OF_CONDUCT.md).
- Duplicate reports of an already-open issue (comment on the existing one
  instead).

---

## If you want to help

Contributions are welcome. Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before
opening a pull request, and by submitting you agree to the
[Code of Conduct](CODE_OF_CONDUCT.md).
