# Language Selection Reference (`language.yml`)

`language.yml` defines the active language for RTP. It is loaded at startup before other configuration files so that messages and configuration defaults are initialized in the selected locale.

Apply changes by editing `language.yml` on disk and running `/rtp reload`, or by using the `/rtp config language <locale>` command.

---

## Configuration Keys

| Key | Type | Default | Description |
|---|---|---|---|
| `language` | String | `"en"` | Active locale code (e.g. `en`, `es`, `de`, `fr`). Sets the language used for generated configuration files, messages, and menus. |
| `version` | String | `"1.0"` | Configuration schema version (internal use; do not edit). |

---

## Changing the Language

### Method 1: In-Game / Console Command
Run the command:
```text
/rtp config language <locale>
```
*Example:* `/rtp config language es`

This command updates `language.yml` and reloads configuration files and messages immediately.

### Method 2: Editing `language.yml`
1. Open `plugins/RTP/language.yml`.
2. Change the `language` value to your desired locale code (for example, `language: de`).
3. Run `/rtp reload` in the console or in-game (or restart the server).

---

## Bundled Locales

The following language codes ship with RTP:

| Code | Language |
|---|---|
| `en` | English (Default) |
| `de` | German |
| `es` | Spanish |
| `fr` | French |
| `it` | Italian |
| `ja` | Japanese |
| `ko` | Korean |
| `nl` | Dutch |
| `pl` | Polish |
| `pt` | Portuguese |
| `ru` | Russian |
| `zh` | Chinese |

---

## Customizing Messages vs. Developing Translations

### Customizing Messages (Server Operators)
To reword or customize messages for your server, you do not need to manage locale folders. Edit the message files directly under `plugins/RTP/advanced/messages/` (`player.yml`, `commands.yml`, `system.yml`, `network.yml`, `placeholders.yml`) and run `/rtp reload`. See [MESSAGES.md](MESSAGES.md) for details.

### Contributing Translations (Developers)
Translated defaults are packaged inside the plugin JAR under `lang/<locale>/`. To contribute a new translation or update an existing language bundle for the plugin repository, see the [Translation Guide](../../dev/TRANSLATION_GUIDE.md).

---

> See [CONFIG_LIFECYCLE.md](CONFIG_LIFECYCLE.md) for how RTP resolves and reloads configuration files across locale changes.
