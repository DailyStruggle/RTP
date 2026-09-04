package io.github.dailystruggle.rtp.common.benchmark;

/**
 * A plugin-side memory model, replayed request-by-request against one shared candidate stream
 * (ADR-080).
 *
 * <p><b>Scope boundary, and it is the whole reason this is measurable.</b> An implementation
 * allocates only the bookkeeping its design implies: cache entries, spatial-memory entries, result
 * containers, boxed keys. It must never allocate a stand-in for a chunk payload. Chunk load and
 * worldgen allocation is platform-owned ({@code LevelChunk}, palettes, sections, NBT buffers) and a
 * {@code new byte[]} sized "like a chunk" would be arithmetic wearing a measurement's clothes. So
 * these figures are <em>plugin-side allocation</em> only, and the report says so.
 */
interface MemoryModel {

  /** Report subject name. */
  String name();

  /**
   * Serves one teleport request.
   *
   * @param stream shared candidate source; identical draws for every model (common random numbers)
   * @param nowMillis virtual clock, so wall-clock TTLs can be exercised over simulated hours
   * @return candidates consumed, i.e. how many picks this model had to evaluate
   */
  int serve(CandidateStream stream, long nowMillis);

  /** Entries currently retained across all of this model's structures. */
  long retainedEntries();

  /**
   * Candidates this model had to materialise a chunk for, i.e. could not reject off-tick. Counted,
   * never priced here: the cost of a chunk load is platform-owned and belongs to the real harness.
   *
   * @return running count since construction
   */
  long chunkMaterializations();

  /**
   * Requests this model could not hand a location to. Reported because a starved cache looks cheap:
   * without it, a model that serves nothing would post the best allocation figure in the table.
   *
   * @return running count since construction
   */
  long unservedRequests();
}
