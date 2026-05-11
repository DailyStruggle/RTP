# FAQ (RTP-lite)

> Stripped from `docs/admin/FAQ.md` to lite scope (ADR-024). Questions about
> SQL persistence, Folia, claim plugins, economy, multilingual support, or
> the login reserve cache apply only to RTP-Pro.

### Q: How is "RTP" different from "RTP-Pro"?

Same source tree, two shadow jars (ADR-024). Lite drops SQL/Redis persistence,
the Folia adapter, claim integrations, economy/Vault, the localized `lang/**`
tree, the login reserve cache, visitor mode, and several `performance.yml`
toggles. Both keep the same `name: RTP` plugin identifier so the data folder
is interchangeable: you can swap jars without losing region/world configs.

### Q: I'm on Folia. Can I run the lite jar?

No. Folia operators must use RTP-Pro. Spigot, Paper, and Fabric are supported
in lite (Fabric is unstable — see `MULTI_PLATFORM_PLAN.md` in the Pro docs).

### Q: I want to charge for `/rtp`. Can lite do that?

No. Economy / Vault is dropped from lite. Use RTP-Pro.

### Q: My claim plugin (GriefDefender, Lands, …) — does lite respect claims?

No. Claim-plugin softdepend integrations (ADR-019) are dropped from lite. The
lite jar does not block teleports into protected land. If your server uses
claims, run RTP-Pro.

### Q: Can I switch from lite to Pro (or back) without losing data?

Yes. Both jars share `plugins/RTP/` and the `name: RTP` plugin identifier.
Stop the server, swap the jar, start the server. Pro's first start may add
files lite never created (`language.yml`, `economy.yml`, `integrations.yml`,
`lang/**`); lite ignores them on the way back.

### Q: First few `/rtp`s are slow. Is this normal?

Yes — the pregen queue starts empty. After ~30–60 s the per-region queue is
warm. To speed up cold starts, pregenerate the world (Chunky / WorldBorder)
and run `/rtp scan start region:<name>` to populate spatial memory.

### Q: `/rtp scan` is crawling. Why?

Almost certainly the world is not pregenerated. Scan throughput is bound by
chunk-load latency. Finish your Chunky / WorldBorder pass first, then resume.

### Q: How do I invalidate the safety memory after editing `safety.yml`?

```
/rtp scan reset  region:<name>
/rtp scan start  region:<name>
```

### Q: Can I add custom messages or translate in-game text?

`messages.yml` is editable in lite (REQ-RTP-F-013). Full multilingual support
(`lang/**`, `/rtp language ...`) is Pro only.

### Q: Where do these docs live on disk?

`plugins/RTP/lite-docs/`. They are extracted from the jar on first start. To
refresh them after upgrading, delete the folder and restart — the next start
re-extracts the bundled copy.
