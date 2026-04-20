$p = "C:\Users\lxgol\Documents\GitHub\RTP\.junie\AGENTS.md"
$c = [System.IO.File]::ReadAllText($p)
$old = @'
Platform adapters (`rtp-paper`, `rtp-folia`) override `getChunkAtAsync` with their native async APIs.
'@
$old = $old.TrimEnd("`r","`n")
$new = @'
Platform adapters (`rtp-paper`, `rtp-folia`) override `getChunkAtAsync` with their native async APIs. **On pure Spigot this is only partially achieved:** the Bukkit API ships only the `Consumer`-based async chunk overloads; the `World#getChunkAtAsync(int,int) -> CompletableFuture<Chunk>` overload is a Paper addition. `BukkitRTPWorld` probes for it reflectively (`CHUNK_AT_ASYNC_FUTURE`) and, when absent (vanilla Spigot), `loadChunkFuture` falls back to `Bukkit.getScheduler().runTask(plugin, () -> world.getChunkAt(cx, cz))` -- a synchronous chunk load scheduled onto the primary thread. The caller's `CompletableFuture` is unblocked, but the chunk I/O itself is not off-tick. **No blanket "fully async chunk loading on all platforms" guarantee exists on pure Spigot** -- that guarantee holds only on Paper and Folia. The **Anvil read-only pre-filter** (ADR-016 / ADR-017) is the mechanism by which off-tick safety evaluation is achieved on pure Spigot for the common case; every candidate that falls to `UNKNOWN`, is already loaded, or sits in a world with a custom `ChunkGenerator` will still drive a main-thread `getChunkAt` via the fallback. Prefilter coverage therefore defines effective off-tick coverage on pure Spigot, not a blanket async contract.
'@
$new = $new.TrimEnd("`r","`n")
if ($c.Contains($old)) { [System.IO.File]::WriteAllText($p, $c.Replace($old, $new)); "OK" } else { "NOT FOUND" }
