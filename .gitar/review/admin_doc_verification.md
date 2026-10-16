# Review Guide: Admin Documentation & Schema Accuracy Verification

Server administrators rely on documentation under `docs/admin/` to safely deploy, tune, and manage LeafRTP. Inaccurate, outdated, or misleading admin documentation leads directly to misconfigured servers, player exploits, JVM memory exhaustion, and degraded community trust.

Gitar must enforce absolute parity and technical accuracy between shipped code and administrative documentation. A code change is incomplete and must be blocked if its corresponding documentation is missing, inaccurate, or syntactically invalid.

---

## 1. Configuration & Message Schema Parity

Whenever configuration records (`rtp-core/.../configuration/schema/*`), baseline configuration files (`rtp-plugin/src/main/resources/config.yml`), or message templates (`messages.yml`) are modified:

- **Matching Documentation Required:**
  - Core settings -> `docs/admin/configuration/CORE_CONFIG.md`
  - Region & shape settings -> `docs/admin/configuration/REGIONS.md`
  - Performance & caching settings -> `docs/admin/configuration/PERFORMANCE.md`
  - Messages & placeholders -> `docs/admin/configuration/MESSAGES.md`
  - Action scripts & hooks -> `docs/admin/ACTIONS.md`
- **Localization Parity (REQ-RTP-F-013):**
  - Any addition, deletion, or renaming of keys in `messages.yml` must simultaneously update:
    - Baseline key map: `lang/messages.lang.yml`
    - All localized templates: `lang/<locale>/messages.yml`
- **Review Action:** Hard-block any pull request that modifies configuration keys or default messages without updating both the relevant `docs/admin/configuration/` guides and the `lang/` files.

---

## 2. Technical Accuracy of Admin Examples

Documentation must not merely exist; it must be completely accurate:

- **Exact YAML Key Names:**
  - Verify that documented YAML keys in markdown code blocks match the exact case-sensitive string identifiers defined in code schema records.
- **Data Type & Unit Accuracy:**
  - Verify that documented data types match the code deserializer:
    - Time intervals: clearly document whether values represent integer ticks, milliseconds, or fractional seconds.
    - Numeric bounds: document minimum, maximum, and default values matching code constants.
    - Enums & booleans: ensure documented values match allowed tokens.
- **Default Value Accuracy:**
  - Default values listed in markdown tables or YAML example blocks must match the compiled constants in Java schemas and the shipped `config.yml`/`messages.yml` baselines.
- **Valid YAML Syntax:**
  - Ensure all YAML snippets in documentation are well-formed and valid YAML without indentation or quoting errors.
- **Review Action:** Flag any discrepancy between code constants and documented defaults as an immediate blocking error.

---

## 3. Command & Permission Parity

Whenever commands, arguments, or permissions are modified (`commands-api`, `rtp-core/.../commands/`, platform adapters):

- **Command Syntax Table:**
  - Update `docs/admin/COMMANDS.md` with the exact subcommand tree, argument names, and usage syntax.
- **Permission Nodes:**
  - Every subcommand and administrative feature must document its required permission node and default access level (e.g., `op`, `true`, `false`).
- **Audit Logging (REQ-RTP-S-004):**
  - Verify that platform command handlers log invalid commands or bad parameters to `RTP.log(Level.WARNING, ...)` for administrative auditing.
- **Review Action:** Block pull requests introducing new subcommands or flags without updated command tables in `docs/admin/COMMANDS.md`.

---

## 4. Traceability & Scope Synchronization (Rules D-001, D-007)

- **Traceability Parity (Rule D-001):**
  - If a pull request modifies, adds, or renames classes bound to requirements in `docs/dev/TRACEABILITY.md`, update `docs/dev/TRACEABILITY.md` in the same changeset.
- **Subsystem Scope Alignment (Rule D-007):**
  - If a pull request adds a new module, command family, platform adapter, or network interface, update `docs/dev/REQUIREMENTS.md` Section 0 (In Scope / Out of Scope) in the same changeset.
