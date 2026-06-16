# Language Selection Reference (`language.yml`)

`language.yml` is loaded **before** every other RTP configuration file so that `config.yml`, `messages.yml`, `economy.yml`, `performance.yml`, `safety.yml`, and `logging.yml` are read directly from `lang/<language>/` when a non-English locale is active.

---

## Keys

| Key | Type | Default | Description |
|---|---|---|---|
| `language` | String | `"en"` | Locale code. Selects the `lang/<code>/` directory whose translated YAML files are loaded. |

## Bundled locales

`en`, `cat`, `de`, `es`, `fr`, `it`, `ja`, `ko`, `nl`, `pl`, `pt`, `ru`, `zh`.

## Adding your own locale

1. Create a folder under `<pluginDir>/lang/<code>/` containing the translated YAML files.
2. Set `language` to that code.
3. Run `/rtp reload` (or restart the server).

---

> See [CONFIG_LIFECYCLE.md](CONFIG_LIFECYCLE.md) for how RTP resolves and reloads locale files.
