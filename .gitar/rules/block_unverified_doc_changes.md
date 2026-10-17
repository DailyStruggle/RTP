# Gitar Rule: Block Unverified or Desynchronized Documentation Changes

When a pull request is opened, updated, or marked ready for review:

## 1. Configuration & Message Schema Synchronization
If the pull request modifies any of the following code or resource paths:
- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/configuration/`
- `rtp-plugin/src/main/resources/config.yml`
- `rtp-plugin/src/main/resources/messages.yml`

Evaluate the pull request diff for corresponding documentation and localization updates:
- If configuration options are added, modified, or removed:
  - Verify that matching files under `docs/admin/configuration/` are modified in the same changeset (`CORE_CONFIG.md`, `REGIONS.md`, `PERFORMANCE.md`, `MESSAGES.md`, or `ACTIONS.md`).
- If default messages in `messages.yml` are modified:
  - Verify that `lang/messages.lang.yml` and localized templates (`lang/<locale>/messages.yml`) are updated in the same changeset (`REQ-RTP-F-013`).
- **Action if missing:**
  - Block the pull request.
  - Post a review comment:
    > "Configuration schemas or default messages were modified without corresponding updates to `docs/admin/configuration/` or `lang/`. Shipped code and administrative documentation must remain in strict parity. See `.gitar/review/admin_doc_verification.md`."

---

## 2. Technical Accuracy of Admin Examples
If the pull request modifies any files under `docs/admin/`:
- **Syntax Check:** Validate that all YAML code snippets in the modified markdown files parse as valid YAML without syntax errors.
- **Key Parity Check:** Verify that documented YAML key names match the exact case-sensitive string keys declared in `rtp-core` configuration schema records.
- **Default Value Check:** Verify that documented default values in tables and YAML blocks match compiled constants in code and the baseline `config.yml`/`messages.yml`.
- **Action if inaccurate:**
  - Block the pull request.
  - Post a targeted review comment identifying the exact line number, invalid syntax, or discrepancy between code constants and documentation.

---

## 3. Requirement Traceability & Scope Synchronization (Rules D-001, D-007)
- If classes mapped to requirements in `docs/dev/TRACEABILITY.md` are added, modified, or renamed:
  - Verify that `docs/dev/TRACEABILITY.md` is updated in the same changeset.
  - If missing: Block the pull request and request traceability updates per Rule D-001.
- If a new module, command family, or platform adapter is introduced:
  - Verify that `docs/dev/REQUIREMENTS.md` Section 0 (In Scope / Out of Scope) is updated per Rule D-007.
  - If missing: Block the pull request and request scope boundary documentation.
