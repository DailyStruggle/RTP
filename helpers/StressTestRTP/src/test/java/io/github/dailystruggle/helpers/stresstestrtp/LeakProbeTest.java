package io.github.dailystruggle.helpers.stresstestrtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LeakProbeTest {

    private static final String HISTO = String.join("\n",
            " num     #instances         #bytes  class name (module)",
            "-------------------------------------------------------",
            "   1:      12345678      987654321  [B (java.base@21.0.4)",
            "   2:            52          41600  org.bukkit.craftbukkit.entity.CraftPlayer",
            "   3:            50         160000  net.minecraft.server.level.ServerPlayer",
            "   4:            50          12000  net.minecraft.server.network.ServerGamePacketListenerImpl",
            "   5:             3            144  org.bukkit.craftbukkit.v1_20_R1.entity.CraftPlayer",
            "Total      12345833      987868065");

    @Test
    @DisplayName("histogram: parses rows, skips header and total")
    void parses() {
        List<LeakProbe.ClassCount> rows = LeakProbe.parseHistogram(HISTO);
        assertEquals(5, rows.size());
        assertEquals("[B", rows.get(0).name());
        assertEquals(12345678L, rows.get(0).instances());
        assertEquals(41600L, rows.get(1).bytes());
    }

    @Test
    @DisplayName("histogram: CraftPlayer matched by suffix across versioned packages")
    void craftPlayerSuffix() {
        List<LeakProbe.ClassCount> rows = LeakProbe.parseHistogram(HISTO);
        assertEquals(55L, LeakProbe.sumInstances(rows, LeakProbe.CRAFT_PLAYER, true));
        assertEquals(50L, LeakProbe.sumInstances(rows, LeakProbe.SERVER_PLAYER, false));
        assertEquals(50L, LeakProbe.sumInstances(rows, LeakProbe.CONNECTION, false));
    }

    @Test
    @DisplayName("histogram: absent class is not-measured (-1), never zero retention")
    void absentIsSentinel() {
        List<LeakProbe.ClassCount> rows = LeakProbe.parseHistogram("   1:  10  100  [B (java.base)");
        assertEquals(-1L, LeakProbe.sumInstances(rows, LeakProbe.SERVER_PLAYER, false));
    }

    @Test
    @DisplayName("checkpoint: retained player objects = max(live player classes) - online, floored at 0")
    void retained() {
        LeakProbe.Checkpoint c = new LeakProbe.Checkpoint(0L, "c1", 48, 96, 48, 96,
                55L, 50L, 48L, 1000.0, 800.0, 10L, "f");
        assertEquals(7L, c.retainedPlayerObjects());
        LeakProbe.Checkpoint under = new LeakProbe.Checkpoint(0L, "c1", 48, 0, 0, 48,
                40L, 40L, 40L, 1.0, 1.0, 1L, "f");
        assertEquals(0L, under.retainedPlayerObjects());
        LeakProbe.Checkpoint unknown = new LeakProbe.Checkpoint(0L, "c1", 48, 0, 0, 48,
                -1L, -1L, -1L, -1, -1, 1L, "f");
        assertEquals(-1L, unknown.retainedPlayerObjects());
    }

    @Test
    @DisplayName("checkpoint: CSV row matches header column count and escapes commas")
    void csvShape() {
        LeakProbe.Checkpoint c = new LeakProbe.Checkpoint(1L, "a,b", 1, 2, 3, 4,
                5L, 6L, 7L, 8.25, -1, 9L, "x.txt");
        String[] cols = c.csvRow().split(",", -1);
        assertEquals(LeakProbe.CSV_HEADER.split(",").length, cols.length);
        assertEquals("a;b", cols[1]);
        assertEquals("-1", cols[11]);
    }

    @Test
    @DisplayName("nmt: total and per-category committed MB parsed; disabled output yields nothing")
    void nmt() {
        String out = String.join("\n",
                "Native Memory Tracking:",
                "",
                "Total: reserved=20971520KB, committed=17825792KB",
                "       malloc: 1048576KB #1234",
                "-                 Java Heap (reserved=16777216KB, committed=16777216KB)",
                "                            (mmap: reserved=16777216KB, committed=16777216KB)",
                "-                    Thread (reserved=204800KB, committed=20480KB)");
        Map<String, Double> m = LeakProbe.parseNmt(out);
        assertEquals(17408.0, m.get("nmt.total.committed_mb"));
        assertEquals(16384.0, m.get("nmt.java_heap.committed_mb"));
        assertEquals(20.0, m.get("nmt.thread.committed_mb"));
        assertEquals(3, m.size());
        assertTrue(LeakProbe.parseNmt("Native memory tracking is not enabled").isEmpty());
    }

    @Test
    @DisplayName("proc status: RSS, peak RSS, swap and OS threads in MB / count")
    void procStatus() {
        String s = String.join("\n", "Name:\tjava", "VmHWM:\t 2097152 kB", "VmRSS:\t 1048576 kB",
                "RssAnon:\t 1000448 kB", "VmSwap:\t       0 kB", "Threads:\t312");
        Map<String, Double> m = LeakProbe.parseProcStatus(s);
        assertEquals(1024.0, m.get("rss_mb"));
        assertEquals(2048.0, m.get("rss_peak_mb"));
        assertEquals(0.0, m.get("swap_mb"));
        assertEquals(312.0, m.get("os_threads"));
        assertNull(m.get("name"));
    }

    @Test
    @DisplayName("threads: pool members fold into one group by digit runs")
    void threadGroups() {
        assertEquals(LeakProbe.threadGroup("BetterRTP-async-3"), LeakProbe.threadGroup("BetterRTP-async-17"));
        assertEquals("worker-#_pool-#", LeakProbe.threadGroup("Worker-1 (pool-22)"));
        assertEquals("unnamed", LeakProbe.threadGroup(" "));
    }

    @Test
    @DisplayName("plugin census: package sum covers arrays, excludes look-alike prefixes")
    void packageSum() {
        List<LeakProbe.ClassCount> rows = List.of(
                new LeakProbe.ClassCount("me.SuperRonanCraft.BetterRTP.player.PlayerInfo", 3, 300),
                new LeakProbe.ClassCount("[Lme.SuperRonanCraft.BetterRTP.references.QueueData;", 2, 64),
                new LeakProbe.ClassCount("me.SuperRonanCraft.BetterRTPAddons.X", 9, 900),
                new LeakProbe.ClassCount("[B", 100, 1000));
        long[] s = LeakProbe.sumPackage(rows, "me.SuperRonanCraft.BetterRTP");
        assertEquals(5L, s[0]);
        assertEquals(364L, s[1]);
        assertEquals(0L, LeakProbe.sumPackage(rows, "")[0]);
        assertEquals(2264L, LeakProbe.sumBytes(rows));
    }

    @Test
    @DisplayName("plugin census: ownership by jar classes spans bundled APIs, excludes sibling jars and shared classes")
    void jarOwnership() {
        Map<String, java.util.Set<String>> jars = new LinkedHashMap<>();
        jars.put("LeafRTP", java.util.Set.of(
                "io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin",
                "io.github.dailystruggle.rtp.common.tasks.TeleportPipelineTask",
                "io.github.dailystruggle.effectsapi.Effect",
                "org.bstats.MetricsBase"));
        jars.put("StressTestRTP", java.util.Set.of(
                "io.github.dailystruggle.helpers.stresstestrtp.LeakProbe",
                "org.bstats.MetricsBase"));
        Map<String, String> owners = LeakProbe.ownerIndex(jars);
        assertNull(owners.get("org.bstats.MetricsBase"));
        List<LeakProbe.ClassCount> rows = List.of(
                new LeakProbe.ClassCount("io.github.dailystruggle.rtp.common.tasks.TeleportPipelineTask", 4, 400),
                new LeakProbe.ClassCount("[Lio.github.dailystruggle.effectsapi.Effect;", 1, 32),
                new LeakProbe.ClassCount(
                        "io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin$$Lambda/0x0000000801234567", 2, 32),
                new LeakProbe.ClassCount("io.github.dailystruggle.helpers.stresstestrtp.LeakProbe", 1, 64),
                new LeakProbe.ClassCount("org.bstats.MetricsBase", 2, 48),
                new LeakProbe.ClassCount("[B", 100, 1000));
        Map<String, long[]> s = LeakProbe.sumByOwner(rows, owners);
        assertEquals(7L, s.get("LeafRTP")[0]);
        assertEquals(464L, s.get("LeafRTP")[1]);
        assertEquals(1L, s.get("StressTestRTP")[0]);
    }

    @Test
    @DisplayName("plugin census: jar entries map to binary names; resources and multi-release prefixes handled")
    void jarEntries() {
        assertEquals("a.b.C$D", LeakProbe.jarEntryClass("a/b/C$D.class"));
        assertEquals("a.b.C", LeakProbe.jarEntryClass("META-INF/versions/17/a/b/C.class"));
        assertNull(LeakProbe.jarEntryClass("plugin.yml"));
        assertNull(LeakProbe.jarEntryClass("module-info.class"));
        assertNull(LeakProbe.jarEntryClass("[I"));
        assertNull(LeakProbe.histogramClass("[I"));
        assertEquals("a.B", LeakProbe.histogramClass("[[La.B;"));
    }

    @Test
    @DisplayName("metrics sidecar: one row per metric, integers unpadded, label escaped")
    void metricRows() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("rss_mb", 1024.5);
        m.put("threads_live", 312.0);
        List<String> rows = LeakProbe.metricRows(7L, "a,b", m);
        assertEquals(List.of("7,a;b,rss_mb,1024.500", "7,a;b,threads_live,312"), rows);
        assertEquals(4, LeakProbe.METRICS_HEADER.split(",").length);
    }

    @Test
    @DisplayName("quick checkpoint: keeps exact counts and JVM pools, skips NMT, thread census/dump and heap dump")
    void quickSections() {
        java.util.Set<LeakProbe.Section> q = LeakProbe.Options.QUICK.sections();
        assertTrue(q.containsAll(java.util.EnumSet.of(LeakProbe.Section.JVM_POOLS, LeakProbe.Section.WORKLOAD,
                LeakProbe.Section.PROC, LeakProbe.Section.THREAD_COUNTS, LeakProbe.Section.PLUGINS)));
        for (LeakProbe.Section s : List.of(LeakProbe.Section.NMT, LeakProbe.Section.THREAD_CENSUS,
                LeakProbe.Section.THREAD_DUMP, LeakProbe.Section.HEAP_DUMP)) {
            assertFalse(q.contains(s), s.name());
        }
        assertTrue(new LeakProbe.Options(true, true).sections().contains(LeakProbe.Section.HEAP_DUMP));
    }

    @Test
    @DisplayName("full checkpoint: every section except the heap dump, which only dump adds")
    void fullSections() {
        java.util.Set<LeakProbe.Section> f = LeakProbe.Options.FULL.sections();
        assertEquals(LeakProbe.Section.values().length - 1, f.size());
        assertFalse(f.contains(LeakProbe.Section.HEAP_DUMP));
        java.util.Set<LeakProbe.Section> d = new LeakProbe.Options(true, false).sections();
        assertEquals(java.util.EnumSet.allOf(LeakProbe.Section.class), d);
    }

    @Test
    @DisplayName("jfr: each thread's first sample per recording is dropped, later ones kept")
    void jfrThreadBaseline() {
        JfrAllocationProfiler.ThreadBaseline b = new JfrAllocationProfiler.ThreadBaseline();
        assertFalse(b.accept(1L));
        assertTrue(b.accept(1L));
        assertFalse(b.accept(2L));
        assertTrue(b.accept(2L));
        assertTrue(b.accept(-1L));
        assertTrue(b.accept(-1L));
    }

    @Test
    @DisplayName("direct teleport: destinations stay inside the configured square")
    void directPick() {
        for (int i = 0; i < 1000; i++) {
            int v = HarnessTeleporter.pick(16);
            assertTrue(v >= -16 && v <= 16);
        }
    }

    @Test
    @DisplayName("targets: per-entry sender overrides the global dispatch-as-player")
    void targetSender() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("target-commands", List.of(
                java.util.Map.of("label", "betterrtp", "command", "betterrtp:betterrtp player {player}",
                        "sender", "console"),
                java.util.Map.of("label", "rtp", "command", "rtp:rtp"),
                java.util.Map.of("label", "p", "command", "x", "sender", "PLAYER")));
        List<Targets.Entry> t = Targets.load(cfg);
        assertFalse(t.get(0).dispatchAsPlayer(true));
        assertTrue(t.get(1).dispatchAsPlayer(true));
        assertFalse(t.get(1).dispatchAsPlayer(false));
        assertTrue(t.get(2).dispatchAsPlayer(false));
        assertEquals("betterrtp:betterrtp player bench_001", t.get(0).render("bench_001"));
        assertNull(Targets.parseSender("bogus"));
    }
}
