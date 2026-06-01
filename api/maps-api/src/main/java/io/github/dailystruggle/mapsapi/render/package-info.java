/**
 * Pure pixel-producing {@link io.github.dailystruggle.mapsapi.render.ChartRenderer}
 * functions. Stage 1 ships {@link io.github.dailystruggle.mapsapi.render.HeatmapRenderer};
 * Stage 4 adds {@code CategoryPieRenderer}, {@code SparklineRenderer},
 * {@code RegionCoverageRenderer}, and the Mermaid pipeline under
 * {@code mapsapi.render.mermaid}.
 *
 * <p>Renderers in this package shall not (REQ-RTP-MAP-002, extends
 * REQ-RTP-F-008 / REQ-RTP-S-005):
 * <ul>
 *   <li>perform chunk I/O ({@code world.getChunkAt}, anvil reads),</li>
 *   <li>block on {@link java.util.concurrent.CompletableFuture} via
 *       {@code .get()} / {@code .join()},</li>
 *   <li>import {@code org.bukkit.*} or {@code net.minecraft.*}.</li>
 * </ul>
 *
 * <p>The {@code ReqRtpMap002NoChunkIoTest} ArchUnit rule enforces these
 * constraints at the bytecode level over every class in this package.
 */
package io.github.dailystruggle.mapsapi.render;
