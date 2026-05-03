package io.github.dailystruggle.helpers.stresstestrtp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Offline summariser for spark {@code .sparkprofile} files, ported from
 * {@code helpers/StressTestRTP/scripts/spark_summary.py}.
 *
 * <p>Reads a profile produced by {@code /spark profiler --save-to-file ...}
 * (or equivalent), extracts per-window MSPT/TPS/CPU plus aggregated rolling
 * stats and the top per-thread CPU shares, and emits a compact JSON document
 * suitable for AI analysis or pasting into a markdown table.
 *
 * <p>Self-contained: parses the protobuf wire format directly, no
 * {@code com.google.protobuf} dependency required. Schema mirrored from
 * {@code https://github.com/lucko/spark/tree/master/spark-common/src/main/proto/spark}
 * as of 2026-05.
 */
public final class SparkProfileSummariser {

    private SparkProfileSummariser() {}

    // ------------------------------------------------------------------
    // Wire-format reader
    // ------------------------------------------------------------------

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LDELIM = 2;
    private static final int WIRE_FIXED32 = 5;

    /** Mutable cursor over a byte array. */
    static final class Cursor {
        final byte[] buf;
        int pos;
        final int end;
        Cursor(byte[] buf, int pos, int end) { this.buf = buf; this.pos = pos; this.end = end; }
    }

    static long readVarint(Cursor c) {
        long result = 0;
        int shift = 0;
        while (true) {
            int b = c.buf[c.pos++] & 0xFF;
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift > 63) throw new IllegalStateException("varint too long");
        }
    }

    static void skip(Cursor c, int wire) {
        switch (wire) {
            case WIRE_VARINT: readVarint(c); break;
            case WIRE_FIXED64: c.pos += 8; break;
            case WIRE_LDELIM: { int n = (int) readVarint(c); c.pos += n; break; }
            case WIRE_FIXED32: c.pos += 4; break;
            default: throw new IllegalStateException("unsupported wire type " + wire);
        }
    }

    static double readDouble(Cursor c) {
        double v = ByteBuffer.wrap(c.buf, c.pos, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble();
        c.pos += 8;
        return v;
    }

    static String readString(Cursor c) {
        int n = (int) readVarint(c);
        String s = new String(c.buf, c.pos, n, java.nio.charset.StandardCharsets.UTF_8);
        c.pos += n;
        return s;
    }

    /** Enter a length-delimited submessage; returns end index. Caller must restore c.end. */
    static int enterLDelim(Cursor c) {
        int n = (int) readVarint(c);
        return c.pos + n;
    }

    // ------------------------------------------------------------------
    // Decoded model (only what we summarise)
    // ------------------------------------------------------------------

    static final class RollingAvg {
        Double mean, max, min, median, p95;
    }

    static final class Tps {
        Double last1m, last5m, last15m;
        Integer gameTargetTps;
    }

    static final class Mspt {
        RollingAvg last1m, last5m;
        Integer gameMaxIdealMspt;
    }

    static final class PlatformStatistics {
        Long uptime;
        Tps tps;
        Mspt mspt;
        Long playerCount;
    }

    static final class PlatformMetadata {
        Integer type;
        String name, version, minecraftVersion, brand;
    }

    static final class WindowStatistics {
        Integer ticks;
        Double cpuProcess, cpuSystem;
        Double tps, msptMedian, msptMax;
        Integer players, entities, tileEntities, chunks;
        Long startTime, endTime;
        Integer duration;
    }

    static final class SamplerMetadata {
        Long startTime, endTime;
        Integer interval, numberOfTicks;
        PlatformMetadata platform;
        PlatformStatistics platformStats;
    }

    static final class ThreadNode {
        String name;
        double totalTime; // sum of times[]
    }

    static final class SamplerData {
        SamplerMetadata metadata;
        final List<ThreadNode> threads = new ArrayList<>();
        final Map<Integer, WindowStatistics> windows = new HashMap<>();
    }

    // ------------------------------------------------------------------
    // Parsers (one per submessage)
    // ------------------------------------------------------------------

    private static RollingAvg parseRolling(Cursor c, int end) {
        RollingAvg r = new RollingAvg();
        while (c.pos < end) {
            long tag = readVarint(c);
            int field = (int) (tag >>> 3); int wire = (int) (tag & 7);
            if (wire == WIRE_FIXED64) {
                double v = readDouble(c);
                switch (field) {
                    case 1: r.mean = v; break;
                    case 2: r.max = v; break;
                    case 3: r.min = v; break;
                    case 4: r.median = v; break;
                    case 5: r.p95 = v; break;
                    default: break;
                }
            } else { skip(c, wire); }
        }
        return r;
    }

    private static Tps parseTps(Cursor c, int end) {
        Tps t = new Tps();
        while (c.pos < end) {
            long tag = readVarint(c);
            int field = (int) (tag >>> 3); int wire = (int) (tag & 7);
            if (wire == WIRE_FIXED64) {
                double v = readDouble(c);
                switch (field) { case 1: t.last1m = v; break; case 2: t.last5m = v; break; case 3: t.last15m = v; break; default: break; }
            } else if (wire == WIRE_VARINT) {
                long v = readVarint(c);
                if (field == 4) t.gameTargetTps = (int) v;
            } else { skip(c, wire); }
        }
        return t;
    }

    private static Mspt parseMspt(Cursor c, int end) {
        Mspt m = new Mspt();
        while (c.pos < end) {
            long tag = readVarint(c);
            int field = (int) (tag >>> 3); int wire = (int) (tag & 7);
            if (wire == WIRE_LDELIM) {
                int e2 = enterLDelim(c);
                RollingAvg r = parseRolling(c, e2);
                if (field == 1) m.last1m = r;
                else if (field == 2) m.last5m = r;
                c.pos = e2;
            } else if (wire == WIRE_VARINT) {
                long v = readVarint(c);
                if (field == 3) m.gameMaxIdealMspt = (int) v;
            } else { skip(c, wire); }
        }
        return m;
    }

    private static PlatformStatistics parsePlatformStats(Cursor c, int end) {
        PlatformStatistics p = new PlatformStatistics();
        while (c.pos < end) {
            long tag = readVarint(c);
            int field = (int) (tag >>> 3); int wire = (int) (tag & 7);
            if (wire == WIRE_LDELIM) {
                int e2 = enterLDelim(c);
                if (field == 4) p.tps = parseTps(c, e2);
                else if (field == 5) p.mspt = parseMspt(c, e2);
                c.pos = e2;
            } else if (wire == WIRE_VARINT) {
                long v = readVarint(c);
                if (field == 3) p.uptime = v;
                else if (field == 7) p.playerCount = v;
            } else { skip(c, wire); }
        }
        return p;
    }

    private static PlatformMetadata parsePlatformMeta(Cursor c, int end) {
        PlatformMetadata p = new PlatformMetadata();
        while (c.pos < end) {
            long tag = readVarint(c);
            int field = (int) (tag >>> 3); int wire = (int) (tag & 7);
            if (wire == WIRE_LDELIM) {
                String s = readString(c);
                switch (field) {
                    case 2: p.name = s; break;
                    case 3: p.version = s; break;
                    case 4: p.minecraftVersion = s; break;
                    case 8: p.brand = s; break;
                    default: break;
                }
            } else if (wire == WIRE_VARINT) {
                long v = readVarint(c);
                if (field == 1) p.type = (int) v;
            } else { skip(c, wire); }
        }
        return p;
    }

    private static WindowStatistics parseWindow(Cursor c, int end) {
        WindowStatistics w = new WindowStatistics();
        while (c.pos < end) {
            long tag = readVarint(c);
            int field = (int) (tag >>> 3); int wire = (int) (tag & 7);
            if (wire == WIRE_FIXED64) {
                double v = readDouble(c);
                switch (field) {
                    case 2: w.cpuProcess = v; break;
                    case 3: w.cpuSystem = v; break;
                    case 4: w.tps = v; break;
                    case 5: w.msptMedian = v; break;
                    case 6: w.msptMax = v; break;
                    default: break;
                }
            } else if (wire == WIRE_VARINT) {
                long v = readVarint(c);
                switch (field) {
                    case 1: w.ticks = (int) v; break;
                    case 7: w.players = (int) v; break;
                    case 8: w.entities = (int) v; break;
                    case 9: w.tileEntities = (int) v; break;
                    case 10: w.chunks = (int) v; break;
                    case 11: w.startTime = v; break;
                    case 12: w.endTime = v; break;
                    case 13: w.duration = (int) v; break;
                    default: break;
                }
            } else { skip(c, wire); }
        }
        return w;
    }

    private static SamplerMetadata parseSamplerMeta(Cursor c, int end) {
        SamplerMetadata m = new SamplerMetadata();
        while (c.pos < end) {
            long tag = readVarint(c);
            int field = (int) (tag >>> 3); int wire = (int) (tag & 7);
            if (wire == WIRE_LDELIM) {
                int e2 = enterLDelim(c);
                if (field == 7) m.platform = parsePlatformMeta(c, e2);
                else if (field == 8) m.platformStats = parsePlatformStats(c, e2);
                c.pos = e2;
            } else if (wire == WIRE_VARINT) {
                long v = readVarint(c);
                switch (field) {
                    case 2: m.startTime = v; break;
                    case 3: m.interval = (int) v; break;
                    case 11: m.endTime = v; break;
                    case 12: m.numberOfTicks = (int) v; break;
                    default: break;
                }
            } else { skip(c, wire); }
        }
        return m;
    }

    /** Parse a ThreadNode, summing all {@code times[]} doubles (field 4) and
     *  recursively summing nested StackTraceNode {@code times[]} (field 8 in
     *  field-3 children). */
    private static ThreadNode parseThreadNode(Cursor c, int end) {
        ThreadNode t = new ThreadNode();
        while (c.pos < end) {
            long tag = readVarint(c);
            int field = (int) (tag >>> 3); int wire = (int) (tag & 7);
            if (field == 1 && wire == WIRE_LDELIM) {
                t.name = readString(c);
            } else if (field == 4 && wire == WIRE_LDELIM) {
                // packed double[]
                int n = (int) readVarint(c);
                int e2 = c.pos + n;
                while (c.pos < e2) t.totalTime += readDouble(c);
            } else if (field == 4 && wire == WIRE_FIXED64) {
                t.totalTime += readDouble(c);
            } else {
                skip(c, wire);
            }
        }
        return t;
    }

    private static void parseSamplerData(Cursor c, SamplerData out) {
        while (c.pos < c.end) {
            long tag = readVarint(c);
            int field = (int) (tag >>> 3); int wire = (int) (tag & 7);
            if (wire == WIRE_LDELIM) {
                int e2 = enterLDelim(c);
                if (field == 1) {
                    out.metadata = parseSamplerMeta(c, e2);
                } else if (field == 2) {
                    out.threads.add(parseThreadNode(c, e2));
                } else if (field == 7) {
                    // map<int32, WindowStatistics> entry submsg: key=1 varint, value=2 ldelim
                    Integer key = null; WindowStatistics val = null;
                    while (c.pos < e2) {
                        long t2 = readVarint(c);
                        int f2 = (int) (t2 >>> 3); int w2 = (int) (t2 & 7);
                        if (f2 == 1 && w2 == WIRE_VARINT) {
                            long v = readVarint(c);
                            // sign-extend int32
                            int iv = (int) v;
                            key = iv;
                        } else if (f2 == 2 && w2 == WIRE_LDELIM) {
                            int e3 = enterLDelim(c);
                            val = parseWindow(c, e3);
                            c.pos = e3;
                        } else { skip(c, w2); }
                    }
                    if (key != null && val != null) out.windows.put(key, val);
                }
                c.pos = e2;
            } else {
                skip(c, wire);
            }
        }
    }

    // ------------------------------------------------------------------
    // File loader (handles optional gzip)
    // ------------------------------------------------------------------

    public static SamplerData load(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
            try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] tmp = new byte[8192]; int r;
                while ((r = in.read(tmp)) > 0) bo.write(tmp, 0, r);
                raw = bo.toByteArray();
            }
        }
        SamplerData out = new SamplerData();
        Cursor c = new Cursor(raw, 0, raw.length);
        parseSamplerData(c, out);
        return out;
    }

    // ------------------------------------------------------------------
    // JSON emission — minimal, hand-rolled (no Gson dependency)
    // ------------------------------------------------------------------

    private static Double percentile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) return null;
        int i = Math.max(0, Math.min(sorted.size() - 1, (int) Math.round(q * (sorted.size() - 1))));
        return sorted.get(i);
    }

    /** Build a JSON document summarising the given profile. */
    public static String toJson(SamplerData d, String label, int topThreads) {
        SamplerMetadata md = d.metadata != null ? d.metadata : new SamplerMetadata();
        PlatformMetadata plat = md.platform != null ? md.platform : new PlatformMetadata();
        PlatformStatistics ps = md.platformStats != null ? md.platformStats : new PlatformStatistics();
        Mspt mspt = ps.mspt != null ? ps.mspt : new Mspt();
        Tps tps = ps.tps != null ? ps.tps : new Tps();

        // Per-thread totals.
        Map<String, Double> byThread = new HashMap<>();
        for (ThreadNode t : d.threads) {
            String n = t.name != null ? t.name : "?";
            byThread.merge(n, t.totalTime, Double::sum);
        }
        double totalCpu = 0; for (double v : byThread.values()) totalCpu += v;
        if (totalCpu == 0) totalCpu = 1.0;
        List<Map.Entry<String, Double>> top = new ArrayList<>(byThread.entrySet());
        top.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        if (top.size() > topThreads) top = top.subList(0, topThreads);

        // Per-window aggregates.
        List<Integer> wids = new ArrayList<>(d.windows.keySet());
        Collections.sort(wids);
        List<Double> mediansList = new ArrayList<>();
        List<Double> maxesList = new ArrayList<>();
        List<Double> tpsList = new ArrayList<>();
        List<Double> cpuProcList = new ArrayList<>();
        List<WindowStatistics> windowsOrdered = new ArrayList<>();
        for (Integer wid : wids) {
            WindowStatistics w = d.windows.get(wid);
            windowsOrdered.add(w);
            if (w.msptMedian != null) mediansList.add(w.msptMedian);
            if (w.msptMax != null) maxesList.add(w.msptMax);
            if (w.tps != null) tpsList.add(w.tps);
            if (w.cpuProcess != null) cpuProcList.add(w.cpuProcess);
        }
        Collections.sort(mediansList); Collections.sort(maxesList);
        Collections.sort(tpsList); Collections.sort(cpuProcList);

        JsonBuilder j = new JsonBuilder();
        j.beginObj();
        j.kv("label", label);
        j.key("platform"); j.beginObj();
            j.kv("name", plat.name);
            j.kv("version", plat.version);
            j.kv("minecraft_version", plat.minecraftVersion);
            j.kv("brand", plat.brand);
        j.endObj();
        j.key("capture"); j.beginObj();
            j.kv("start_epoch_ms", md.startTime);
            j.kv("end_epoch_ms", md.endTime);
            Double durS = (md.startTime != null && md.endTime != null)
                ? (md.endTime - md.startTime) / 1000.0 : null;
            j.kv("duration_s", durS);
            j.kv("ticks", md.numberOfTicks);
            j.kv("interval_us", md.interval);
        j.endObj();
        j.key("mspt_rolling"); j.beginObj();
            j.kv("last1m_mean", mspt.last1m != null ? mspt.last1m.mean : null);
            j.kv("last1m_max",  mspt.last1m != null ? mspt.last1m.max : null);
            j.kv("last1m_median", mspt.last1m != null ? mspt.last1m.median : null);
            j.kv("last1m_p95", mspt.last1m != null ? mspt.last1m.p95 : null);
            j.kv("last5m_mean", mspt.last5m != null ? mspt.last5m.mean : null);
            j.kv("last5m_max",  mspt.last5m != null ? mspt.last5m.max : null);
            j.kv("game_max_ideal_mspt", mspt.gameMaxIdealMspt);
        j.endObj();
        j.key("tps_rolling"); j.beginObj();
            j.kv("last1m", tps.last1m);
            j.kv("last5m", tps.last5m);
            j.kv("last15m", tps.last15m);
            j.kv("game_target_tps", tps.gameTargetTps);
        j.endObj();
        j.key("mspt_window_aggregate"); j.beginObj();
            j.kv("windows", mediansList.size());
            j.kv("median_of_medians", percentile(mediansList, 0.5));
            j.kv("p95_of_medians", percentile(mediansList, 0.95));
            j.kv("max_of_medians", mediansList.isEmpty() ? null : mediansList.get(mediansList.size()-1));
            j.kv("max_of_maxes", maxesList.isEmpty() ? null : maxesList.get(maxesList.size()-1));
        j.endObj();
        j.key("tps_window_aggregate"); j.beginObj();
            j.kv("windows", tpsList.size());
            j.kv("median", percentile(tpsList, 0.5));
            j.kv("p05", percentile(tpsList, 0.05));
            j.kv("min", tpsList.isEmpty() ? null : tpsList.get(0));
        j.endObj();
        j.key("cpu_process_window_aggregate"); j.beginObj();
            j.kv("windows", cpuProcList.size());
            j.kv("median", percentile(cpuProcList, 0.5));
            j.kv("p95", percentile(cpuProcList, 0.95));
            j.kv("max", cpuProcList.isEmpty() ? null : cpuProcList.get(cpuProcList.size()-1));
        j.endObj();
        j.key("thread_cpu_top"); j.beginArr();
        for (Map.Entry<String, Double> e : top) {
            j.beginObj();
            j.kv("thread", e.getKey());
            j.kv("weight", e.getValue());
            j.kv("share_pct", 100.0 * e.getValue() / totalCpu);
            j.endObj();
        }
        j.endArr();
        j.key("windows"); j.beginArr();
        for (int i = 0; i < windowsOrdered.size(); i++) {
            WindowStatistics w = windowsOrdered.get(i);
            j.beginObj();
            j.kv("window", wids.get(i));
            j.kv("duration_s", w.duration);
            j.kv("tps", w.tps);
            j.kv("mspt_median", w.msptMedian);
            j.kv("mspt_max", w.msptMax);
            j.kv("cpu_process", w.cpuProcess);
            j.kv("cpu_system", w.cpuSystem);
            j.kv("players", w.players);
            j.kv("entities", w.entities);
            j.kv("chunks", w.chunks);
            j.endObj();
        }
        j.endArr();
        j.endObj();
        return j.toString();
    }

    /** Convenience: read profile, write {@code <profile>.summary.json}, return the path. */
    public static Path summariseToSidecar(Path profile, String label) throws IOException {
        SamplerData d = load(profile);
        String json = toJson(d, label, 8);
        String name = profile.getFileName().toString();
        if (name.endsWith(".sparkprofile")) name = name.substring(0, name.length() - ".sparkprofile".length());
        Path out = profile.resolveSibling(name + ".summary.json");
        Files.write(out, json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return out;
    }

    // ------------------------------------------------------------------
    // Tiny JSON builder (pretty-printed, two-space indent).
    // ------------------------------------------------------------------

    private static final class JsonBuilder {
        private final StringBuilder sb = new StringBuilder();
        private int depth = 0;
        private boolean firstInScope = true;

        private void indent() { for (int i = 0; i < depth; i++) sb.append("  "); }
        private void comma() { if (!firstInScope) sb.append(",\n"); else sb.append("\n"); firstInScope = false; }

        void beginObj() { sb.append("{"); depth++; firstInScope = true; }
        void endObj() { depth--; sb.append("\n"); indent(); sb.append("}"); firstInScope = false; }
        void beginArr() { sb.append("["); depth++; firstInScope = true; }
        void endArr() { depth--; sb.append("\n"); indent(); sb.append("]"); firstInScope = false; }

        void key(String k) { comma(); indent(); sb.append('"').append(escape(k)).append("\": "); firstInScope = true; }

        void kv(String k, Object v) {
            comma(); indent(); sb.append('"').append(escape(k)).append("\": ");
            writeValue(v);
        }

        private void writeValue(Object v) {
            if (v == null) sb.append("null");
            else if (v instanceof String) sb.append('"').append(escape((String) v)).append('"');
            else if (v instanceof Double) {
                double d = (Double) v;
                if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
                else sb.append(String.format(Locale.ROOT, "%.6g", d));
            } else if (v instanceof Float) {
                float f = (Float) v;
                if (Float.isNaN(f) || Float.isInfinite(f)) sb.append("null");
                else sb.append(String.format(Locale.ROOT, "%.6g", f));
            } else if (v instanceof Boolean) sb.append(((Boolean) v) ? "true" : "false");
            else sb.append(v.toString());
        }

        private static String escape(String s) {
            StringBuilder b = new StringBuilder(s.length() + 4);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '\\': b.append("\\\\"); break;
                    case '"': b.append("\\\""); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    default:
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                }
            }
            return b.toString();
        }

        @Override public String toString() { return sb.toString(); }
    }
}
