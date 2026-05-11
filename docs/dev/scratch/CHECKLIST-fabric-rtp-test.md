Effective Issue: add `rtp test` subcommand to fabric variants. Mode: [CODE]. Approved scope: minimal (no class lift; rtp-plugin already hosts both Bukkit and Fabric entrypoints).

- [ ] 1. Split `TestCmd` constructor: register 17 platform-neutral children only; subclass `BukkitTestCmd` registers 4 Bukkit-bound (`TestStressCmd`, `TestChunkProbePerfCmd`, `TestFullCmd`, `AsyncReplyTestJob`)
- [ ] 2. Move `commandLookup.put("ALL", fullCmd)` aliasing into `BukkitTestCmd` (only relevant where `TestFullCmd` exists)
- [ ] 3. Update `RTPCmdBukkit` to use `BukkitTestCmd` instead of `TestCmd`
- [ ] 4. `RTPFabricMod` — after `new RTPCmdFabricRoot()`, call `root.addSubCommand(new TestCmd(root))`
- [ ] 5. Verify `TestCmd`'s dispatch logic (`acquireAndDispatch`, `dispatchWithPermit`) doesn't reference any Bukkit-only types
- [ ] 6. Add a JVM unit test asserting Fabric-side registration: `RTPFabricMod`-style root has `TEST` child after registration
- [ ] 7. Run existing tests under `rtp-plugin/src/test/.../bukkit/commands/test/` — must stay green
- [ ] 9. Delete this scratch file
