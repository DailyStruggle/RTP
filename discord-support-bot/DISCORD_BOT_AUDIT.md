# Discord Support Bot Pre-Release Adversarial Audit & Operational Limits

Scope: `discord-support-bot/` (`build_doc_index.py`, `discord_support_bot.py`, `discord_setup.py`, `support-bot.yml`, `embeds.json`), audited 2026-10-25.
Test harness: [`tests/test_discord_bot_adversarial_audit.py`](tests/test_discord_bot_adversarial_audit.py) (25 adversarial, boundary, concurrency, and stress tests added alongside the 41 baseline tests; all 66 tests execute and verify the findings below).

---

## 1. Executive Summary

| Severity | Count | Status | Summary of Findings & Implemented First-Layer Remediations |
|---|---:|---|---|
| **Blocker** | **3** | **Remediated** | (B-01) Priority-trimmed single-message budgeting (`<= 1,950` chars) preserves security notices, top BIT/safety bullets, and bottom diagnostic/reaction footers while isolating per-thread exceptions in `poll_once()`; (B-02) `"⏳ Scanning..."` placeholders are excluded from `bot_indices`, reused in-place on subsequent polls, and roll back `user_last_action` on transient `PATCH` failures; (B-03) Token length caps (`<= 128`), `search_config_keys` exact-probe length guards (`<= 256`), follow-up YAML diffing, and `sqlite3.Error` catches prevent daemon crashes on `>= 50 KB` inputs. |
| **High** | **7** | **Remediated** | (H-01) Case-insensitive `(?i)` URL matching across `ANY_URL_RE`, `MCLOGS_URL_RE`, and Markdown link conversion; (H-02) Unverified `[REFRAIN]` drafts are discarded (`last_clarifying_questions = None`); (H-03) Backtick-tolerant `@range` and `@options` firewall regexes; (H-04) Broadened `SECRET_PATTERNS` (underscore-prefixed keys, SQL/NoSQL URIs, Discord tokens) with Stage-1 redaction propagated to all downstream digests; (H-05) Persisted `thread_bot_reply_counts` and 50-message window eviction guard prevent re-entering `Case 1`; (H-06) Rate-limited threads are deferred in `poll_once()` without posting a stranding reply; (H-07) Multi-modifier `DIAGNOSTIC_REFUSAL_RE`, expanded `conversational_filler`, and facet-grounded convergence guard prevent abstention bypasses while preserving `MULTITURN_CONVERGENCE`. |
| **Medium** | **10** | **Remediated** | (M-01) Removed false-positive 4-caps clause from `HOSTILE_POST_RE` / `support-bot.yml`, kept ping suppression on genuinely hostile posts, and guarded empty `missing_diag` prompts; (M-02) Anchored `SOLVED_TEXT_RE` to short (`<= 80` char) standalone confirmations on the latest author message; (M-03) Allowed unbackticked domain unit/coordinate labels (`Y=128`, `TPS=20`, `step: 1`) in `verify_llm_draft_against_schema`; (M-04) Permitted one-time terminal `#bot-triage-queue` escalation handoff when required diagnostics arrive at the reply cap; (M-05) Guarded `_STEMMER_CONN` with `_STEMMER_LOCK`; (M-06) Quote-aware inline `#` comment stripping and nested sequence mappings in `parse_simple_yaml`; (M-07) `enrich_threads_with_starter_bodies` in `discord_setup.py --apply`; (M-08) Skipped `embeds.json` reference guides and staff-authored threads in `poll_once()`; (M-09) Sibling-inferred `vert.` vs `shape.` section context and per-key default comparison in `extract_non_default_yaml_overrides`; (M-10) Per-provider raw URL resolution via `resolve_log_paste_raw_url`. |
| **Low** | **4** | **Remediated** | (L-01) Stripped comment/code/log noise before `max_chars` slicing in `_mask_protected_tech_spans`; (L-02) Persisted `refusal_grace_threads`, `model_refrained_threads`, `thread_turns`, `thread_last_facets`, `thread_last_diag`, and `thread_bot_reply_counts` in `bot-state.json`; (L-03) Clause-scoped negation guard in `build_dangerous_advice_warning`; (L-04) Rejected link-local (`169.254.169.254`), unspecified (`0.0.0.0`), multicast, and reserved IPs in `is_safe_llm_endpoint`. |
| **Info** | **1** | **Remediated** | (I-01) Registered `atexit.register(_close_stemmer_conn)` for `_STEMMER_CONN` cleanup; all 66 baseline and adversarial tests pass. |

---

## 2. Detailed Findings by Severity

### 2.1 Blocker Findings

#### [BLOCKER-01] Reply Length Overflow (`> 2,000` chars) Causes Uncaught `HTTP 400` and Aborts `DiscordForumPoller.poll_once()`
- **Location:** `discord-support-bot/discord_support_bot.py` lines `1951`, `3688`, `4150`, `4321`, `4362`, `4761-4771`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_blocker_01_reply_text_exceeds_discord_2000_char_limit_and_aborts_poller`
- **Mechanism:**
  - `verify_llm_draft_against_schema` caps only the raw LLM draft at `1,600` characters (`line 1951`).
  - `SupportBotEngine.handle_new_thread` and `handle_followup_message` concatenate up to five independent blocks into `sections`: `secret_notice` (~220 chars) + `danger_warning` (up to 6 bullets, ~1,150 chars) + `bit_matches` (up to 4 `[RTP bit]` remediations, ~1,100 chars) + `llm_reply` or deterministic FTS5 `cfg_hits`/`doc_hits` (~1,400-1,600 chars) + `_build_investigative_prompt` (~600 chars).
  - In `test_blocker_01`, `len(decision.reply_text)` reaches **`4,469` characters** (and **`2,159` characters** on the shipped Quick Fix #2 reference guide text in `test_medium_08`).
  - Discord REST API v10 strictly rejects `POST /channels/{id}/messages` and `PATCH /channels/{id}/messages/{msg_id}` when `len(content) > 2000` with `HTTP 400 Bad Request (code 50035: Must be 2000 or fewer in length)`.
  - In `DiscordForumPoller._apply_decision` (`line 4771`), `self.client.request("POST", ...)` is not wrapped in `try...except`. When one thread generates a `> 2,000`-character reply, `urllib.error.HTTPError` aborts `poll_once()` mid-loop, starving every thread sorted after it on every poll cycle.
  - Conversely, when `progress_message_id` is set (`line 4761`), `PATCH` catches `OSError` and silently discards the `> 2,000`-character response, leaving the `"⏳ Scanning..."` placeholder in the thread with `✅` and `❓` reactions attached to it.
- **Remediation:** Enforce a hard `<= 1,950`-character budget in `sanitize_bot_output` (or split/truncate at bullet boundaries before returning `BotDecision.reply_text`), and wrap per-thread `_apply_decision` calls in `poll_once()` in `try...except OSError` so one failing thread cannot starve the queue.

#### [BLOCKER-02] Orphaned `"⏳ Scanning..."` Placeholder Poisons `bot_indices` and Permanently Strands Threads
- **Location:** `discord-support-bot/discord_support_bot.py` lines `4705-4725`, `4759-4770`, `4916-4920`, `5087`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_blocker_02_orphaned_scanning_placeholder_permanently_strands_thread`
- **Mechanism:**
  - During `DiscordForumPoller.poll_once()`, the initial pass posts `"⏳ Scanning documentation, configuration schema, and source code for your report..."` (`_post_progress_message`) authored by `self.bot_user_id` before running slow LLM synthesis (`up to 300s`).
  - If the process restarts during that 300-second window, or if the subsequent `PATCH /channels/{thread_id}/messages/{progress_message_id}` fails (`HTTP 502` / `HTTP 400` / network timeout, caught and ignored at `line 4766`), the `"⏳ Scanning..."` message remains the newest message in the thread authored by `self.bot_user_id`.
  - On all subsequent poll cycles, `bot_indices` (`lines 4916-4920`) treats the `"⏳ Scanning..."` message as a completed bot reply (`Case 2`). Because `after_author_msgs` after `last_bot_idx` is empty (`line 5087`), `poll_once()` skips the thread forever.
- **Remediation:** Exclude bot messages whose content starts with `"⏳ Scanning documentation"` from `bot_indices` (and either reuse their `id` as `progress_message_id` or delete stale placeholders on the next poll pass).

#### [BLOCKER-03] Uncaught `sqlite3.OperationalError: LIKE or GLOB pattern too complex` on `>= 50 KB` Inputs Kills `--listen` Daemon
- **Location:** `discord-support-bot/build_doc_index.py` lines `2396`, `2412`, `3489`, `3496`, `3513`; `discord-support-bot/discord_support_bot.py` line `5361`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_blocker_03_sqlite_like_pattern_overflow_crashes_daemon_on_50kb_input`
- **Mechanism:**
  - SQLite enforces `SQLITE_MAX_LIKE_PATTERN_LENGTH = 50000` bytes by default.
  - `search_config_keys(conn, query)` (`lines 2396, 2412`) binds `f"%.{q_strip}"` directly to `WHERE lower(key_path) LIKE lower(?)` without checking `len(q_strip)`. When `handle_followup_message` (`line 4288`) passes `effective_message_body` (which includes `.yml` attachments up to `MAX_TEXT_ATTACHMENT_BYTES = 256 KB`), any `>= 50 KB` input raises an uncaught `sqlite3.OperationalError: LIKE or GLOB pattern too complex`.
  - Similarly, `_tokenize_query` (`line 2089`) has no maximum token length, and `is_schema_or_heading_facet` (`lines 3489, 3496, 3513`) binds `f"%.{clean}"` and `f"%{clean}%"` to `LIKE ?` without `try...except sqlite3.OperationalError`. Any single `>= 50 KB` unbroken token in a post or `200 KB` `mclo.gs` log (via `search_code_sections`) raises `sqlite3.OperationalError`.
  - In `discord_support_bot.py:5361`, the `--listen` daemon loop only catches `(KeyboardInterrupt, OSError)`. Because `sqlite3.OperationalError` inherits from `Exception` (not `OSError`), this unhandled exception terminates the `--listen` process on every poll cycle as long as the thread remains active.
- **Remediation:** Cap token length in `_tokenize_query` / `is_schema_or_heading_facet` (e.g. `len(clean) <= 128`), skip exact/LIKE key probes in `search_config_keys` when `len(q_strip) > 256`, and catch `sqlite3.Error` in `poll_once()`.

---

### 2.2 High Findings

#### [HIGH-01] Case-Sensitive `ANY_URL_RE` Allows Uppercase `HTTPS://` / `Http://` URLs Through Both Schema Firewall and Sanitizer
- **Location:** `discord-support-bot/discord_support_bot.py` lines `120`, `359`, `1976`, `2539`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_high_01_uppercase_url_scheme_bypasses_schema_firewall_and_sanitizer`
- **Mechanism:** `ANY_URL_RE = re.compile(r"https?://[^\s<>)\]]+")` and the Markdown link converter at `line 2539` lack `re.IGNORECASE` (`(?i)`). Any URL using an uppercase or mixed-case scheme (`HTTPS://evil.example/malware.jar`, `[click here](Http://phishing.example)`) is ignored by `verify_llm_draft_against_schema` and `sanitize_bot_output`, passing straight to Discord where clients still auto-link it.
- **Remediation:** Add `(?i)` to `ANY_URL_RE` (`re.compile(r"(?i)https?://[^\s<>)\]]+")`) and the Markdown link regex at `line 2539`.

#### [HIGH-02] `[REFRAIN]` Clarifying-Question Branch Bypasses All `verify_llm_draft_against_schema` Checks
- **Location:** `discord-support-bot/discord_support_bot.py` lines `2557-2570`, `3489-3492`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_high_02_refrain_tag_bypasses_all_schema_firewall_checks`
- **Mechanism:** In `LocalLlmSynthesizer.synthesize` (`lines 2565-2569`):
  ```python
  if ok_q:
      self.last_clarifying_questions = sanitize_bot_output(content)
  else:
      # Salvage valid clarifying lines if angle-bracket URLs or formatting triggered a check
      self.last_clarifying_questions = sanitize_bot_output(content)
  ```
  Both branches execute the exact same assignment. When an LLM response includes `[REFRAIN: ...]` alongside bullets containing forbidden `/rtp config set`, hallucinated config keys (`shape.nonExistentKey=42`), out-of-range numbers (`maxMSPT=999.0`), or fake enums (`shape.name=HEXAGON`), `verify_llm_draft_against_schema` returns `ok_q = False`, yet `self.last_clarifying_questions` still receives the unverified draft and `handle_new_thread` (`line 3492`) posts it to Discord.
- **Remediation:** In the `else:` branch, filter `content` per bullet via `verify_llm_draft_against_schema` (just like partial bullet salvage at `lines 2581-2593`) and set `self.last_clarifying_questions = None` if no individual bullet passes verification.

#### [HIGH-03] Backtick-Wrapped Key/Value Spans (`` `maxMSPT`: `60.0` ``, `` `shape.name`: `HEXAGON` ``) Bypass `@range` and `@options` Firewalls
- **Location:** `discord-support-bot/discord_support_bot.py` lines `2116`, `2156`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_high_03_backticked_key_value_assignments_bypass_range_and_options_firewall`
- **Mechanism:** The numeric `@range` regex (`r"\b([A-Za-z_][A-Za-z0-9_.]*)\s*[=:]\s*(-?\d+(?:\.\d+)?)(?![A-Za-z])"`) and enum `@options` regex (`r"\b([A-Za-z_][A-Za-z0-9_.]*)\s*[=:]\s*['\"]?([A-Za-z_][A-Za-z0-9_-]{1,32})['\"]?"`) do not allow backticks (`\x60`) between the key name, the `=`/`:` separator, and the value. When an LLM formats keys and values in separate Markdown code spans (e.g. `` Set `maxMSPT`: `60.0` ``, `` `minTPS` = `10.0` ``, `` `shape.name`: `HEXAGON` ``), neither regex matches, bypassing `@range`, `minTPS >= 19.0`, and `@options` validation.
- **Remediation:** Allow optional backticks around keys and values in both regexes (``r"`?\b([A-Za-z_][A-Za-z0-9_.]*)`?\s*[=:]\s*`?..."``).

#### [HIGH-04] `SECRET_PATTERNS` Misses Underscore-Prefixed Keys & SQL URIs, and `handle_followup_message` Leaks Unredacted Secrets to `#bot-triage-queue`
- **Location:** `discord-support-bot/discord_support_bot.py` lines `123-128`, `3249`, `3755`, `4352`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_high_04_secret_redaction_misses_underscore_keys_sql_uris_and_leaks_to_triage_digest`
- **Mechanism:**
  1. `SECRET_PATTERNS` uses `\b(?:password|passwd|redis_password|db_password)` and `\b(?:secret|net_secret|jwt_secret|token)`. Because `_` is a word character (`\w`), `\b` fails whenever a key name has any other underscore prefix (`DISCORD_BOT_TOKEN=...`, `bot_token: ...`, `api_token: ...`, `mysql_password: ...`, `client_secret: ...`). Additionally, only `rediss?://` URIs are matched, missing `mysql://`, `postgresql://`, `mariadb://`, and `jdbc:...` connection strings.
  2. In `handle_new_thread` (`line 3249`) and `handle_followup_message` (`line 3755`), the redacted text returned by `detect_and_redact_secrets` is discarded (`has_secret, _ = detect_and_redact_secrets(...)`). When `handle_followup_message` escalates to `#bot-triage-queue` (`line 4352`), it passes the unredacted `effective_message_body` into `_format_triage_digest(..., investigative_note=effective_message_body)`, echoing plaintext passwords into `#bot-triage-queue`.
- **Remediation:** Broaden `SECRET_PATTERNS` to `(?:^|[\s'\"_.-])(?:password|passwd|secret|token|api_key)\s*[:=]` + `(?:rediss?|mysql|postgresql|mariadb|mongodb)://` + Discord token patterns, and pass redacted text into `_format_triage_digest`.

#### [HIGH-05] 50-Message Polling Window (`GET /channels/{id}/messages?limit=50`) Evicts Thread Starter & Bot Replies
- **Location:** `discord-support-bot/discord_support_bot.py` lines `4886-4920`, `4997-5016`, `5105`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_high_05_50_message_window_evicts_bot_replies_and_starter_post`
- **Mechanism:** Discord's `GET /channels/{thread_id}/messages?limit=50` returns the 50 newest messages. Once 50 messages are posted after the bot's earlier replies:
  1. All earlier bot replies fall out of the 50-message window (`bot_indices == []`). `poll_once()` enters `Case 1 (if not bot_indices:)`, treating the old thread as a brand-new thread and bypassing `MAX_BOT_REPLIES_PER_THREAD = 3`.
  2. The starter message (`id == thread_id`) also falls out of `messages`, so `prior_author_body` (`lines 4997-5006`) loses the original question body, and if `th.get("owner_id")` is absent, `owner_id` (`line 4900`) is misattributed to the author of the 50th newest message.
- **Remediation:** Track replied thread IDs and reply counts in `bot-state.json`, and fetch the starter message (`GET /channels/{thread_id}/messages/{thread_id}`) if `thread_id` is not present in `messages`.

#### [HIGH-06] Per-User Cooldown Replies Permanently Strand New Threads in `DiscordForumPoller`
- **Location:** `discord-support-bot/discord_support_bot.py` lines `2846-2851`, `3283-3292`, `4916-4920`, `5087`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_high_06_cooldown_reply_permanently_strands_second_thread_in_poller`
- **Mechanism:** When a user opens two threads within `cooldown_seconds` (`10.0s`) - or when two settled threads from the same author are evaluated in the same `poll_once()` pass (which shares a single `now = self.now_fn()` timestamp across all threads) - the second thread receives `"I received your post - please wait a few seconds before sending another request."` Because that rate-limit notice is posted to Discord as a message authored by `self.bot_user_id`, subsequent polls see `bot_indices = [1]` and `after_author_msgs = []`, so the second thread is never answered after the 10-second cooldown expires.
- **Remediation:** In `DiscordForumPoller.poll_once()`, defer cooldown-limited threads to the next poll cycle (`continue` without posting a dummy bot message) rather than posting a rate-limit message that marks the thread as answered.

#### [HIGH-07] Follow-Up Convergence Branch Bypasses Stage 7 Abstention Gate on Multi-Modifier Log Refusals
- **Location:** `discord-support-bot/discord_support_bot.py` lines `155-166`, `2654-2664`, `4102-4159`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_high_07_followup_convergence_bypasses_abstention_gate_on_multi_modifier_refusal`
- **Mechanism:**
  1. `DIAGNOSTIC_REFUSAL_RE` (`line 158`) uses `(?:my\s+|the\s+|any\s+|private\s+)?` (at most one modifier before `logs`), so `"I refuse to share my private logs"` or `"I won't upload my server logs"` sets `is_explicit_refusal = False`.
  2. `_extract_corpus_facets` (`line 2656`) does not exclude verbs like `"share"` or `"upload"`, which exist in `corpus_vocab`, setting `has_new_facets = True`.
  3. In `handle_followup_message` (`line 4102`), the Indirect-Channel Convergence branch checks `has_new_facets and has_high_cum` without checking `should_abstain_for_missing_info`. Because Turn 1 (`"My server lags"`) already has `has_high_cum = True` in `combinatorial_retrieve`, the bot mistakes `"share"` in `"I refuse to share my private logs"` for narrowing technical context, removes `⏳ Needs Info / Logs`, and dumps speculative config recommendations with zero diagnostics.
- **Remediation:** Change `(?:my\s+|the\s+|any\s+|private\s+)?` in `DIAGNOSTIC_REFUSAL_RE` to `(?:(?:my|the|any|private|server|full|latest|console)\s+)*`, add `"share"`, `"upload"`, `"post"`, `"send"`, `"refuse"` to `conversational_filler`, and guard the convergence branch with `not should_abstain_for_missing_info(...)`.

---

### 2.3 Medium Findings

#### [MEDIUM-01] `HOSTILE_POST_RE` All-Caps Clause False-Positives on Valid Technical Tokens and Blocks `@Pro Supporter` Escalation
- **Location:** `discord-support-bot/discord_support_bot.py` lines `1363`, `3573`, `3666`, `4169`, `4299-4303`; `discord-support-bot/support-bot.yml` line `145`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_medium_01_hostile_regex_false_positive_on_uppercase_tokens_blocks_escalation`
- **Mechanism:** `HOSTILE_POST_RE` flags any 4 consecutive uppercase words (`[A-Z]{3,}(?:\s+[A-Z]{2,}){3,}`) unless the first word is one of 7 shape/action enums. Polite posts containing Minecraft material/biome lists (`LAVA WATER FIRE CACTUS`, `DEEP OCEAN WARM OCEAN LUKEWARM OCEAN`), SQL log snippets (`SELECT COUNT FROM TABLE`), or `THANK YOU SO MUCH` are classified as `hostile = True`. This suppresses `#bot-triage-queue` escalation even for `@Pro Supporter` users who provide all 4 diagnostics, and on follow-up (`lines 4299-4303`, where `missing_diag == []`) emits a truncated `"To finish narrowing this down before I ping the maintainer, please also share:\n"` with an empty bullet list.
- **Remediation:** Require all-caps shouting detection to exclude `[A-Z0-9_]+` technical/enum tokens or require `>= 6` consecutive non-corpus dictionary words with `!`/`?`, and guard `lines 4299-4303` when `missing_diag` is empty.

#### [MEDIUM-02] `SOLVED_TEXT_RE` Multiline Prefix (`^\s*fixed\b`) Falsely Locks and Archives Active Unresolved Threads
- **Location:** `discord-support-bot/discord_support_bot.py` lines `4502-4504`, `5092-5095`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_medium_02_solved_regex_multiline_prefix_falsely_locks_active_thread`
- **Mechanism:** `SOLVED_TEXT_RE` uses `(?im)^\s*(?:!?\s*solved|!?\s*fixed|...)\b` without an end-of-line anchor or negation check. When a user writes a multi-line update where any line begins with `fixed` or `solved` (e.g. `"I checked my config:\nfixed the region name in worlds.yml, but it still teleports players above Y=128!"`), `poll_once()` immediately marks the thread `✅ Solved` and locks + archives it.
- **Remediation:** Require the solved phrase to match the entire trimmed message (`^\s*(?:!?\s*solved|!?\s*fixed|that\s+fixed\s+it|issue\s+solved|problem\s+solved)[.!?\s]*$`) when `len(coalesced_followup) < 80`.

#### [MEDIUM-03] `verify_llm_draft_against_schema` Falsely Rejects Valid LLM Prose Containing `Y=128`, `TPS=20`, or `step: 1`
- **Location:** `discord-support-bot/discord_support_bot.py` lines `2116-2133`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_medium_03_schema_firewall_falsely_rejects_prose_numbers_like_y_128_or_tps_20`
- **Mechanism:** Line `2116` scans all prose (even outside backticks) for `\b([A-Za-z_][A-Za-z0-9_.]*)\s*[=:]\s*(-?\d+(?:\.\d+)?)` and rejects the draft if `k_name` is not in `config_keys` or `("candidates", "queue", "activechunks", "hot_queue")`. Valid domain prose encouraged by the system prompt itself (`"stay below Y=128"`, `"at TPS=20"`, `"step: 1"`) fails verification with `hallucinated config key: Y` / `TPS` / `step`.
- **Remediation:** Restrict unknown-key rejection on unbackticked `word=number` / `word: number` to backticked spans, or allow standard domain unit/coordinate labels (`y`, `x`, `z`, `tps`, `mspt`, `step`, `tier`, `java`, `mc`).

#### [MEDIUM-04] `MAX_BOT_REPLIES_PER_THREAD = 3` and `TAG_NEEDS_INFO` Gate Block Final Diagnostic Escalation on Turn 4
- **Location:** `discord-support-bot/discord_support_bot.py` lines `5097`, `5105`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_medium_04_max_bot_replies_cap_blocks_final_diagnostic_escalation_and_need_help`
- **Mechanism:** In `DiscordForumPoller.poll_once()`, `NEED_HELP_TEXT_RE` (`line 5097`) is ignored when `TAG_NEEDS_INFO in tag_names`, and `if len(bot_indices) >= MAX_BOT_REPLIES_PER_THREAD: continue` (`line 5105`) drops all further messages after 3 bot replies. If a user asks a meta-clarification (`"how do I run /rtp test?"`) and shares partial diagnostics across 3 bot replies, their Turn 4 message supplying the final `mclo.gs` log is silently ignored and never escalated to `#bot-triage-queue`.
- **Remediation:** Allow one terminal diagnostic-escalation transition even when `len(bot_indices) == MAX_BOT_REPLIES_PER_THREAD` if `thread_id not in self.engine.escalated_threads` and `TAG_NEEDS_INFO in tag_names`.

#### [MEDIUM-05] Concurrent `porter_stem_word` Calls Race on Unlocked Global `_STEMMER_CONN` SQLite Connection
- **Location:** `discord-support-bot/build_doc_index.py` lines `195-223`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_medium_05_concurrent_porter_stem_word_races_on_shared_sqlite_connection`
- **Mechanism:** `_get_stemmer_conn()` returns a single global `sqlite3.Connection(":memory:", check_same_thread=False)`, and `porter_stem_word` executes `DELETE FROM stem_input`, `INSERT INTO stem_input VALUES (?)`, and `SELECT term FROM stem_vocab LIMIT 1` without a `threading.Lock()`. Under concurrent calls, threads interleave `DELETE`/`INSERT`/`SELECT` on `stem_input`, raising `sqlite3.OperationalError` or caching another thread's stem permanently in `_PORTER_STEM_CACHE`.
- **Remediation:** Guard `_STEMMER_CONN` operations in `porter_stem_word` with a module-level `threading.Lock()`.

#### [MEDIUM-06] `parse_simple_yaml` Corrupts Quoted Values With Inline `#` Comments and Drops List Items After Nested Mappings
- **Location:** `discord-support-bot/build_doc_index.py` lines `47-141`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_medium_06_parse_simple_yaml_corrupts_inline_comments_and_nested_list_dicts`
- **Mechanism:** `parse_simple_yaml` strips only full-line `#` comments (`line 91`). Adding an inline comment to `support-bot.yml` (e.g. `default_project: "leafrtp" # comment`) causes `_parse_yaml_scalar` to see a string that does not end with `"` and return `'"leafrtp" # comment'` literally. Additionally, `_parse_block` (`lines 102-112`) breaks out of sequence parsing when a list item has nested indented keys (`ind > first_indent`), silently dropping all subsequent items in the sequence.
- **Remediation:** Strip unquoted inline `#` comments in `_parse_yaml_scalar` and advance `idx` past nested blocks in `_parse_block`.

#### [MEDIUM-07] `discord_setup.py --apply` Ignores Edited `pinned_forum_threads` Bodies on Live Guilds
- **Location:** `discord-support-bot/discord_setup.py` lines `638-643`, `883-906`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_medium_07_discord_setup_live_apply_ignores_edited_thread_bodies`
- **Mechanism:** `compute_provisioning_plan` (`line 638`) gates starter-message updates on `has_body = ("body" in existing) or ("message" in existing)`. Discord's `GET /guilds/{id}/threads/active` and `/threads/archived/public` endpoints return thread channel objects without `body` or `message` fields, so `has_body` is always `False` during live `--apply` unless `GET /channels/{thread_id}/messages/{thread_id}` is fetched.
- **Remediation:** In `discord_setup.py:main()` (`--apply` branch), fetch `GET /channels/{th_id}/messages/{th_id}` for existing reference threads whose title matches `pinned_forum_threads` so `existing["body"]` is populated before calling `compute_provisioning_plan`.

#### [MEDIUM-08] Unpinned Quick Fix Reference Threads (`idx 1..4`) Are Polled as Support Questions When `owner_id != maintainer_id`
- **Location:** `discord-support-bot/discord_setup.py` lines `613-621`; `discord-support-bot/discord_support_bot.py` lines `4863-4869`, `4904`, `5285`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_medium_08_unpinned_quick_fix_reference_threads_polled_when_maintainer_id_differs`
- **Mechanism:** Because Discord `GUILD_FORUM` channels allow only 1 pinned thread (`CHANNEL_FLAG_PINNED`), `discord_setup.py` pins only guide #1 (`idx == 0`) and creates guides #2-#5 with `flags = 0`. In `DiscordForumPoller.poll_once()`, `support_threads` filters out `CHANNEL_FLAG_PINNED` (`idx == 0` only) and checks `owner_id in (self.engine.maintainer_id, self.bot_user_id)`. If guides #2-#5 were created by another staff account or if `DISCORD_MAINTAINER_ID` is not set in the environment (defaulting to `"999999999999999999"` at `line 5285`), the bot polls and replies to its own Quick Fix reference guides.
- **Remediation:** Skip threads whose title matches `pinned_forum_threads` in `embeds.json` (or whose author holds a staff role via `self._is_staff_replier(owner_id)`).

#### [MEDIUM-09] `extract_non_default_yaml_overrides` Misattributes Flat Leaf Keys (`name: jump` -> `shape.name`) and Drops Invalid Cross-Section Values
- **Location:** `discord-support-bot/build_doc_index.py` lines `3629-3670`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_medium_09_yaml_override_extractor_misattributes_flat_leaf_keys_and_section_context`
- **Mechanism:** When a user pastes a 4-line `vert` snippet without the outer `vert:` header (`name: jump`, `minY: 32`, `maxY: 120`, `requireSkyLight: false`), `rows` (`WHERE lower(key_path) LIKE '%.name' LIMIT 4`) returns `defaults.shape.name` (`rows[0]`) ahead of `vert.name`, rewriting `name: jump` into `shape.name: jump`. Conversely, if a user mistakenly writes `name: CIRCLE` in a flat `vert` paste, `any(_yaml_values_equivalent("CIRCLE", r["default_value"]) for r in rows)` matches `shape.name`'s default (`CIRCLE`) and strips the broken line as an "untouched default".
- **Remediation:** Infer the enclosing section prefix (`vert.` vs `shape.`) from sibling keys in the same indentation block (`minY`, `maxY`, `requireSkyLight` -> `vert.`) when resolving bare leaf keys like `name` or `mode`.

#### [MEDIUM-10] `fetch_mclogs_raw` Hard-Breaks on First Provider Template When Multiple `log_paste_providers` Are Configured
- **Location:** `discord-support-bot/discord_support_bot.py` lines `647-708`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_medium_10_multi_provider_log_paste_fetcher_always_uses_first_provider_template`
- **Mechanism:** `DISCORD_SUPPORT_BOT_DESIGN.md` section 5.2 documents adding multiple `security.log_paste_providers` (`mclo.gs`, `pastebin.com`, `gist.github.com`, `0x0.st`) in `support-bot.yml`. While `extract_mclogs_ids` extracts paste IDs across all configured providers, `fetch_mclogs_raw` loops over `providers.values()` and unconditionally `break`s on the first provider with a `raw_url_template`, sending every extracted Pastebin/Gist ID to `https://api.mclo.gs/1/raw/{id}`.
- **Remediation:** Return `(provider_name, paste_id)` (or the resolved `raw_url`) from `extract_mclogs_ids` so `fetch_mclogs_raw` uses the matching provider's `raw_url_template`.

---

### 2.4 Low Findings

#### [LOW-01] `_mask_protected_tech_spans` Truncates Foreign Queries at `900` / `500` Characters Before Translation
- **Location:** `discord-support-bot/discord_support_bot.py` lines `715-727`, `755`, `800`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_low_01_translator_masking_truncates_queries_beyond_500_and_900_chars`
- **Mechanism:** `translate_via_google_nmt` (`max_chars=900`) and `translate_via_mymemory_tm` (`max_chars=500`) slice `cleaned[:max_chars]` before masking technical spans. When a non-English user pastes a 600-900 character YAML snippet or console log first and writes their foreign-language question at the bottom of the post, the question is truncated before reaching either translator.
- **Remediation:** Mask/strip multi-line code blocks and YAML pastes first (or translate the non-code prose segments around them) before applying the `max_chars` cap.

#### [LOW-02] `DiscordForumPoller._save_state` Drops `refusal_grace_threads`, `model_refrained_threads`, and `thread_turns` Across Restarts
- **Location:** `discord-support-bot/discord_support_bot.py` lines `4567-4595`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_low_02_poller_state_persistence_drops_refusal_grace_and_turn_history`
- **Mechanism:** `bot-state.json` persists `escalated_threads`, `investigative_threads`, `followup_answered_threads`, and `handled_reactions`, but omits `refusal_grace_threads` and `model_refrained_threads`. If the bot restarts after sending its one-time self-serve grace reply to an obstinate user (`refusal_grace_threads`), a subsequent zero-delta refusal triggers a second grace reply instead of staying silent.
- **Remediation:** Persist `refusal_grace_threads` and `model_refrained_threads` in `bot-state.json`.

#### [LOW-03] `DANGEROUS_ADVICE_RE` Triggers on Negated Warnings (`"Do NOT set syncLoading: true"`)
- **Location:** `discord-support-bot/discord_support_bot.py` lines `167-176`, `205-251`, `5069`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_low_03_dangerous_advice_regex_triggers_on_negated_statements_and_warnings`
- **Mechanism:** `DANGEROUS_ADVICE_RE` matches `syncLoading: true`, `viewDistanceSelect: 2`, and `/rtp config set` without checking for preceding negation (`not`, `never`, `don't`, `avoid`). When a user or community bystander writes `"Make sure you do NOT set syncLoading: true"` or `"Don't use /rtp config set"`, the bot still emits a redundant **Configuration safety note** (and in `poll_once` line `5069`, `has_bystander_danger` triggers a bot reply even when the thread author has not posted).
- **Remediation:** Ignore matches preceded on the same line by `\b(?:not|never|don'?t|do\s+not|avoid|instead\s+of)\b`.

#### [LOW-04] `is_safe_llm_endpoint` Accepts `169.254.169.254` (Cloud Metadata Link-Local) and `0.0.0.0`
- **Location:** `discord-support-bot/discord_support_bot.py` lines `1824-1848`
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_low_04_is_safe_llm_endpoint_accepts_cloud_metadata_link_local_and_unspecified_ips`
- **Mechanism:** In Python's `ipaddress` module, `ip.is_private` is `True` for `169.254.0.0/16` (`ip.is_link_local`, including cloud metadata `169.254.169.254`) and `0.0.0.0` (`ip.is_unspecified`).
- **Remediation:** Reject `ip.is_link_local`, `ip.is_unspecified`, and `ip.is_multicast` in `is_safe_llm_endpoint` (`return bool((ip.is_loopback or ip.is_private) and not ip.is_link_local and not ip.is_unspecified)`).

---

### 2.5 Info & Operational Limits

#### [INFO-01] Measured Operational Limits, Stress Benchmarks & Resource Cleanup
- **Reproducer:** `DiscordBotAdversarialAuditTest.test_info_01_fts5_special_syntax_null_bytes_and_stress_limits`
- **Measured Corpus & Index Limits (`doc-index.sqlite`):**
  - Cold index build time (`build_index`): **~8.2 s - 9.5 s** (indexes **580** `doc_sections`, **1,383** Java `code_sections`, **887** `config_keys`, **221** `bit_codes`, and **10,917** `locale_aliases` across 12 locales).
  - Single-post deterministic triage latency (`combinatorial_retrieve` + `extract_grounded_query_signals` + `handle_new_thread`, warm cache): **8 ms - 25 ms** per post.
  - `1 MB` (20,000-line) server log `[RTP bit]` scan (`extract_bit_codes` + `unwrap_console_paste`): **< 450 ms**.
  - Maximum safe single-token / query length before SQLite `LIKE` failure: **`49,998` bytes** (`SQLITE_MAX_LIKE_PATTERN_LENGTH = 50,000` minus 2 bytes for `"%."`).
  - Maximum safe reply length before Discord REST API `HTTP 400`: **`2,000` characters** (currently bounded to `1,600` chars only on the raw LLM draft, not on the assembled multi-section `reply_text`).
  - Maximum thread history window per poll: **`50` messages** (`GET /channels/{id}/messages?limit=50`).
  - Maximum automated bot replies per thread: **`3` replies** (`MAX_BOT_REPLIES_PER_THREAD = 3`), reset if `> 50` subsequent messages evict the bot's earlier replies from the 50-message window.
  - Maximum fetched `mclo.gs` pastes per post: **`3` pastes**, **`200,000` bytes** each (`4.0s` timeout).
  - Maximum attachments per post: **`3` image attachments** (`<= 5 MB` each, `4.0s` timeout) and **`3` text/YAML attachments** (`<= 256 KB` each, `4.0s` timeout).
- **What Held Up Well Under Adversarial Testing:**
  - **SQLite FTS5 & SQL Injection Immunity:** FTS5 operators (`NEAR/5`, `AND`, `OR`, `NOT`, `*`, `^`, `:`), unbalanced quotes, null bytes (`\x00`), Unicode replacement characters (`\ufffd`), and SQL injection payloads (`'; DROP TABLE doc_sections; --`) are cleanly tokenized into `[A-Za-z0-9_.-]+` and double-quote escaped in `_build_fts_or_query` with parameterized SQL queries throughout.
  - **Stage 3 Orderless Corpus-Facet Bottleneck ($P(\text{permute}(x)) = P(x)$):** Bidirectional RTL override characters (`\u202e`), zero-width joiners (`\u200b`..`\u200d`), `@everyone` / `<@&role>` mentions, and imperative jailbreak instructions (`"ignore previous instructions..."`) are stripped before LLM prompt construction; clause token sets are strictly closed over `corpus_vocab` and canonically sorted.
  - **Valid Lowercase URL & Mention Sanitization:** Standard lowercase `http://` / `https://` links to non-allowlisted domains, guessed `dailystruggle.github.io/RTP/...` paths not in `doc_sections.url`, and `@everyone` / `@here` / `<@&role>` mentions are deterministically blocked when `verify_llm_draft_against_schema` runs on normal (non-`[REFRAIN]`) drafts.
- **Minor Resource Cleanup Note:** `_STEMMER_CONN` in `build_doc_index.py:195` (`sqlite3.connect(":memory:", check_same_thread=False)`) is never closed at process exit, emitting `ResourceWarning: unclosed database in <sqlite3.Connection object>` under Python 3.12+ unittest runners; registering `atexit.register(_STEMMER_CONN.close)` silences this warning.
