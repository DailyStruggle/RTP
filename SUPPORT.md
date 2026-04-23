# Getting Support for RTP

RTP is maintained by a single developer in their spare time. Please read this
page **before** opening an issue or pinging in chat — it tells you where your
question belongs and how to ask it so you actually get a useful answer.

---

## Before you ask

Check these first. Most questions are already answered:

1. **Wiki / resource page** — <https://www.spigotmc.org/resources/rtp.94812/>
2. **Server-admin docs** — [`docs/FOR_SERVER_ADMINS.md`](docs/FOR_SERVER_ADMINS.md)
   and the rest of [`docs/admin/`](docs/admin/)
3. **FAQ** — [`docs/admin/FAQ.md`](docs/admin/FAQ.md)
4. **Commands reference** — [`docs/admin/COMMANDS.md`](docs/admin/COMMANDS.md)
5. **Existing issues** — search **open *and* closed** issues; your problem has
   probably been reported.
6. **Update first** — confirm the problem reproduces on the latest release
   (`/rtp version`). Old builds do not get support.

---

## Where to ask — pick the right channel

| Intent | Channel |
|---|---|
| "How do I configure / use X?" (question, not a bug) | [GitHub Discussions](https://github.com/DailyStruggle/RTP/discussions) |
| Real-time chat with the community | SpigotMC resource discussion thread |
| Suspected bug (crash, stack trace, broken behavior) | [New bug report](https://github.com/DailyStruggle/RTP/issues/new?template=bug_report.md) |
| Feature idea | Open a **Discussion** first; issues only after triage |
| **Security vulnerability** | **Do not open a public issue.** See [`SECURITY.md`](SECURITY.md) and use [private advisories](https://github.com/DailyStruggle/RTP/security/advisories/new) |
| Commercial / paid support | Not offered |
| Addon development help | [`docs/FOR_ADDON_DEVELOPERS.md`](docs/FOR_ADDON_DEVELOPERS.md) first, then Discussions |

**Do not** use the issue tracker for questions, "how do I" requests, or
plugin-conflict debugging without first reproducing with RTP alone. Those
will be closed and redirected to Discussions.

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

## Response expectations

- This is a volunteer project. There is **no SLA**.
- Typical first response: a few days to a couple of weeks.
- Issues with incomplete information are closed immediately and not
  re-opened until the missing context is provided.
- Pinging the maintainer in DMs, other repositories, or unrelated threads
  will not speed anything up and is covered by the
  [Code of Conduct](CODE_OF_CONDUCT.md).

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
