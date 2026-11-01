# RTP addons

This is the `addons/` folder inside your RTP plugin directory
(`plugins/RTP/addons/`). RTP unpacked the jars in here on first run so the
bundled reference and companion addons work out of the box - no separate
download. This README was unpacked alongside them for reference.

## What's in here

- `LeafRTPGuiAddon.jar` - the in-game GUI destination picker / menu.
- `LeafRTPClaimAddon.jar` - claim-plugin integrations (keeps teleports out of
  protected land for the claim plugins it supports).
- `LeafRTPCountdownAddon.jar` - the reference event-hook / countdown addon.
- `README.md` - this file.

Drop your own addon jars in here too: RTP scans this folder on startup and loads
any jar exposing an `RTPAddon`. Jars live here (not in `plugins/`) because the
server's plugin loader would reject them for lacking a `plugin.yml`.

## Removing / opting out of an addon

Delete the jar you don't want and restart. As long as this folder still exists,
RTP will **not** put it back - removing a single jar is a permanent opt-out for
that addon.

Do **not** delete the whole `addons/` folder to opt out: if the folder is
missing on the next start, RTP treats it as a fresh install and re-extracts
every bundled jar (and this README) again. Keep the folder, remove only the
jar(s) you want gone.

## Updating an addon

RTP never overwrites a jar that's already here. If you replace a bundled jar
with your own newer build (same filename), your copy is kept untouched on every
startup.
