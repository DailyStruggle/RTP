package io.github.dailystruggle.rtp.bukkitplatform.metrics;

/** Engine telemetry dataset for the spark metadata provider; shared by binding and accessor. */
final class SparkTelemetry {

    private SparkTelemetry() {
    }

    /**
     * Collects engine telemetry dataset for spark metadata serialization.
     * Guaranteed never to throw and safely degrades if core or subcomponents
     * are uninitialized.
     *
     * @return structured map of telemetry key-value pairs
     */
    static java.util.Map<String, Object> collect() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        try {
            // MemoryTracker State
            map.put("rtp.memory.tracked_entries_total", io.github.dailystruggle.rtp.common.tools.MemoryTracker.trackedCount());
            map.put("rtp.memory.active_tasks", io.github.dailystruggle.rtp.common.tools.MemoryTracker.activeTasks());
            map.put("rtp.memory.active_chunk_tickets", io.github.dailystruggle.rtp.common.tools.MemoryTracker.activeTickets());
            map.put("rtp.memory.ceiling_bytes", io.github.dailystruggle.rtp.common.tools.MemoryTracker.getMemoryCeiling());

            // Queue & Cache State
            long l1Ready = 0;
            long l2Cold = 0;
            long l3BacklogBins = 0;

            try {
                if (io.github.dailystruggle.rtp.common.RTP.selectionAPI != null) {
                    java.util.Map<String, io.github.dailystruggle.rtp.common.selection.region.Region> perm =
                            io.github.dailystruggle.rtp.common.RTP.selectionAPI.permRegionLookup;
                    if (perm != null) {
                        for (io.github.dailystruggle.rtp.common.selection.region.Region r : perm.values()) {
                            if (r != null && r.queueManager != null) {
                                if (r.queueManager.keptLocations != null) {
                                    l1Ready += r.queueManager.keptLocations.size();
                                }
                                if (r.queueManager.unkeptLocations != null) {
                                    l2Cold += r.queueManager.unkeptLocations.size();
                                }
                                if (r.queueManager.backlogLocations != null) {
                                    l3BacklogBins += Math.max(0, r.queueManager.backlogLocations.size()
                                            - r.queueManager.backlogLocations.validatedSize()
                                            - r.queueManager.backlogLocations.invalidatedSize());
                                }
                            }
                        }
                    }

                    java.util.Map<java.util.UUID, io.github.dailystruggle.rtp.common.selection.region.Region> temp =
                            io.github.dailystruggle.rtp.common.RTP.selectionAPI.tempRegions;
                    if (temp != null) {
                        for (io.github.dailystruggle.rtp.common.selection.region.Region r : temp.values()) {
                            if (r != null && r.queueManager != null) {
                                if (r.queueManager.keptLocations != null) {
                                    l1Ready += r.queueManager.keptLocations.size();
                                }
                                if (r.queueManager.unkeptLocations != null) {
                                    l2Cold += r.queueManager.unkeptLocations.size();
                                }
                                if (r.queueManager.backlogLocations != null) {
                                    l3BacklogBins += Math.max(0, r.queueManager.backlogLocations.size()
                                            - r.queueManager.backlogLocations.validatedSize()
                                            - r.queueManager.backlogLocations.invalidatedSize());
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Ignored: SelectionAPI or region queues uninitialized / unavailable
            }

            map.put("rtp.queue.l1_ready_count", l1Ready);
            map.put("rtp.queue.l2_cold_count", l2Cold);
            map.put("rtp.queue.l3_backlog_bins", l3BacklogBins);

            // Teleport Pipeline
            long pendingTeleports = 0;
            double avgLatency = 0.0;
            long slowCount = 0;

            try {
                pendingTeleports = io.github.dailystruggle.rtp.common.tools.MemoryTracker.trackedCountByLabel("TeleportPipelineTask");
            } catch (Throwable ignored) {
                // Ignored: MemoryTracker label query fallback
            }

            try {
                io.github.dailystruggle.rtp.common.metrics.CoreMetrics cm = io.github.dailystruggle.rtp.common.RTP.metrics;
                if (cm != null) {
                    avgLatency = cm.pipelineHistogram().mean();
                    slowCount = cm.slowPipelineCount();
                }
            } catch (Throwable ignored) {
                // Ignored: CoreMetrics query fallback
            }

            map.put("rtp.pipeline.pending_teleports", pendingTeleports);
            map.put("rtp.pipeline.avg_latency_ms", avgLatency);
            map.put("rtp.pipeline.slow_count", slowCount);
        } catch (Throwable ignored) {
            // Ignored: graceful degradation for overall telemetry collection
        }
        return map;
    }
}
