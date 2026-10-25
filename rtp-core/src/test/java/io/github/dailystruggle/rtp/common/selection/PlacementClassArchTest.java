package io.github.dailystruggle.rtp.common.selection;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.RegionCacheTask;
import io.github.dailystruggle.rtp.common.tasks.scan.ScanTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mechanical architecture check for ADR-123 Section 2 & 11 (Work Placement Classes).
 *
 * <p>Verifies that {@code rtp-core} selection, cache, scan, and teleport classes do not invoke
 * location-less {@link RTPScheduler#runTask(Runnable)} or {@link RTPScheduler#runTaskLater(Runnable, long)}
 * outside the explicit allow-list of global-bound callers, and that region-task future completions
 * route through the async scheduler with a counted inline fallback.
 */
@DisplayName("ADR-123: Work placement mechanical architecture check and off-region completion hand-off")
class PlacementClassArchTest {

    private static final String SCHEDULER_INTERNAL_NAME =
            "io/github/dailystruggle/rtp/api/scheduling/RTPScheduler";
    private static final Set<String> LOCATIONLESS_DESCRIPTORS = Set.of(
            "runTask:(Ljava/lang/Runnable;)V",
            "runTaskLater:(Ljava/lang/Runnable;J)V"
    );

    /**
     * Explicit allow-list of top-level classes in {@code selection}, {@code tasks/scan}, and
     * {@code tasks/teleport} that legitimately schedule global-bound work.
     *
     * <ul>
     *   <li>{@code RegionQueueManager}: reload/startup ticket validation sweep pump.</li>
     *   <li>{@code ScanTask}: {@code reportTeardown} main-lane completion callback.</li>
     *   <li>{@code TeleportPipelineTask}: player invulnerability expiry and no-chunkSet immediate fallback.</li>
     * </ul>
     */
    private static final Set<String> ALLOWED_GLOBAL_CALLERS = Set.of(
            "io/github/dailystruggle/rtp/common/selection/region/RegionQueueManager",
            "io/github/dailystruggle/rtp/common/tasks/scan/ScanTask",
            "io/github/dailystruggle/rtp/common/tasks/teleport/TeleportPipelineTask"
    );

    @Test
    @DisplayName("Selection, cache, scan, and teleport bytecode calls location-less runTask/runTaskLater only from allow-listed classes")
    void selectionScanAndTeleportBytecodeHonorsPlacementAllowList() throws Exception {
        URL rootUrl = SelectionAPI.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotNull(rootUrl, "Could not locate rtp-core code source");
        Path codeSource = Path.of(rootUrl.toURI());
        Map<String, byte[]> classBytesByInternalName = loadTargetClassBytes(codeSource);

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : classBytesByInternalName.entrySet()) {
            String internalClassName = entry.getKey();
            String topLevelName = internalClassName.contains("$")
                    ? internalClassName.substring(0, internalClassName.indexOf('$'))
                    : internalClassName;
            List<String> calls = findLocationlessSchedulerCalls(internalClassName, entry.getValue());
            if (!calls.isEmpty() && !ALLOWED_GLOBAL_CALLERS.contains(topLevelName)) {
                violations.add(internalClassName + " -> " + calls);
            }
        }

        assertTrue(classBytesByInternalName.size() > 10,
                "Expected to scan rtp-core selection/scan/teleport classes, found " + classBytesByInternalName.size());
        assertTrue(violations.isEmpty(),
                "Unapproved location-less RTPScheduler#runTask or #runTaskLater calls found (ADR-123 Section 11): "
                        + violations);

        // Specifically verify RegionCacheTask, QueueTask, and PregenTask have zero location-less calls
        String regionPkg = "io/github/dailystruggle/rtp/common/selection/region/";
        assertTrue(findLocationlessSchedulerCalls(
                regionPkg + "RegionCacheTask",
                classBytesByInternalName.get(regionPkg + "RegionCacheTask")).isEmpty(),
                "RegionCacheTask must use async delayed tasks instead of location-less runTaskLater");
        assertTrue(findLocationlessSchedulerCalls(
                regionPkg + "QueueTask",
                classBytesByInternalName.get(regionPkg + "QueueTask")).isEmpty(),
                "QueueTask must not invoke location-less runTask/runTaskLater");
        assertTrue(findLocationlessSchedulerCalls(
                regionPkg + "PregenTask",
                classBytesByInternalName.get(regionPkg + "PregenTask")).isEmpty(),
                "PregenTask must not invoke location-less runTask/runTaskLater");
    }

    @Test
    @DisplayName("Off-region completion hand-off routes through runTaskAsynchronously and counts inline fallbacks on rejection")
    void offRegionCompletionRoutesAsyncAndCountsFallback(@TempDir Path tempDir) throws Exception {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        ScanTask.resetInlineFallbackCount();

        Method scanCompleteOffRegion = ScanTask.class.getDeclaredMethod(
                "completeOffRegion", CompletableFuture.class, boolean.class);
        scanCompleteOffRegion.setAccessible(true);

        Method regionCacheAsyncDelayed = RegionCacheTask.class.getDeclaredMethod(
                "scheduleAsyncDelayed", Runnable.class, long.class);
        regionCacheAsyncDelayed.setAccessible(true);

        AtomicBoolean asyncCalled = new AtomicBoolean(false);
        AtomicBoolean timerAsyncCalled = new AtomicBoolean(false);
        AtomicBoolean rejectAsync = new AtomicBoolean(false);

        RTPScheduler baseScheduler = accessor.getScheduler();
        RTPScheduler interceptingScheduler = new RTPScheduler() {
            @Override
            public TrackedRTPTask runTaskAsynchronously(Runnable task) {
                if (rejectAsync.get()) {
                    throw new IllegalStateException("Simulated async scheduler rejection");
                }
                asyncCalled.set(true);
                return baseScheduler.runTaskAsynchronously(task);
            }

            @Override public void runTask(Runnable task) { baseScheduler.runTask(task); }
            @Override public void runTask(io.github.dailystruggle.rtp.api.world.RTPLocation loc, Runnable task) { baseScheduler.runTask(loc, task); }
            @Override public void runTask(io.github.dailystruggle.rtp.api.world.RTPWorld<?> w, int cx, int cz, Runnable task) { baseScheduler.runTask(w, cx, cz, task); }
            @Override public void runTaskLater(Runnable task, long delay) { baseScheduler.runTaskLater(task, delay); }
            @Override public void runTaskLater(io.github.dailystruggle.rtp.api.world.RTPWorld<?> w, int cx, int cz, Runnable task, long delay) { baseScheduler.runTaskLater(w, cx, cz, task, delay); }
            @Override public Object runTaskTimer(Runnable task, long delay, long period) { return baseScheduler.runTaskTimer(task, delay, period); }
            @Override public Object runTaskTimer(io.github.dailystruggle.rtp.api.world.RTPWorld<?> w, int cx, int cz, Runnable task, long delay, long period) { return baseScheduler.runTaskTimer(w, cx, cz, task, delay, period); }
            @Override
            public Object runTaskTimerAsynchronously(Runnable task, long delay, long period) {
                timerAsyncCalled.set(true);
                return baseScheduler.runTaskTimerAsynchronously(task, delay, period);
            }
            @Override public void cancelTask(Object task) { baseScheduler.cancelTask(task); }
            @Override public void runTaskForPlayer(io.github.dailystruggle.rtp.api.entity.RTPPlayer p, io.github.dailystruggle.rtp.common.tasks.RTPRunnable t, long d) { baseScheduler.runTaskForPlayer(p, t, d); }
        };
        RTPRuntime.setServerAccessor(new MockRTPServerAccessor(tempDir.toFile()) {
            @Override
            public RTPScheduler getScheduler() {
                return interceptingScheduler;
            }
        });
        RTPRuntime.setScheduler(interceptingScheduler);

        // 1. Normal off-region completion routes through runTaskAsynchronously
        CompletableFuture<Boolean> fut1 = new CompletableFuture<>();
        AtomicBoolean dependentRanAfterAsync = new AtomicBoolean(false);
        fut1.thenAccept(val -> dependentRanAfterAsync.set(Boolean.TRUE.equals(val) && asyncCalled.get()));
        scanCompleteOffRegion.invoke(null, fut1, true);
        assertTrue(fut1.isDone());
        assertTrue(dependentRanAfterAsync.get(), "Future dependent must run after async hand-off");
        assertEquals(0L, ScanTask.inlineFallbackCount());

        // 2. Rejected async hand-off falls back inline and increments inlineFallbackCount
        rejectAsync.set(true);
        CompletableFuture<Boolean> fut2 = new CompletableFuture<>();
        scanCompleteOffRegion.invoke(null, fut2, false);
        assertTrue(fut2.isDone());
        assertFalse(fut2.getNow(true));
        assertEquals(1L, ScanTask.inlineFallbackCount(),
                "Rejected off-region hand-off must increment inlineFallbackCount");

        // 3. RegionCacheTask.scheduleAsyncDelayed uses runTaskTimerAsynchronously
        AtomicBoolean delayedFired = new AtomicBoolean(false);
        regionCacheAsyncDelayed.invoke(null, (Runnable) () -> delayedFired.set(true), 5L);
        assertTrue(timerAsyncCalled.get(), "scheduleAsyncDelayed must use runTaskTimerAsynchronously");
    }

    private static boolean isTargetInternalName(String internalName) {
        return internalName.startsWith("io/github/dailystruggle/rtp/common/selection/")
                || internalName.startsWith("io/github/dailystruggle/rtp/common/tasks/scan/")
                || internalName.startsWith("io/github/dailystruggle/rtp/common/tasks/teleport/");
    }

    private static Map<String, byte[]> loadTargetClassBytes(Path codeSource) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (Files.isDirectory(codeSource)) {
            try (Stream<Path> walk = Files.walk(codeSource)) {
                for (Path cf : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                    String internalName = codeSource.relativize(cf).toString()
                            .replace(File.separatorChar, '/')
                            .replaceAll("\\.class$", "");
                    if (isTargetInternalName(internalName)) {
                        out.put(internalName, Files.readAllBytes(cf));
                    }
                }
            }
        } else {
            try (JarFile jar = new JarFile(codeSource.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.endsWith(".class")) {
                        String internalName = name.substring(0, name.length() - ".class".length());
                        if (isTargetInternalName(internalName)) {
                            try (InputStream is = jar.getInputStream(entry)) {
                                out.put(internalName, is.readAllBytes());
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * Parses the Java classfile constant pool to detect any Methodref / InterfaceMethodref
     * targeting {@code RTPScheduler.runTask(Runnable)} or {@code RTPScheduler.runTaskLater(Runnable, long)}.
     */
    private static List<String> findLocationlessSchedulerCalls(String className, byte[] classBytes) throws IOException {
        assertNotNull(classBytes, "Missing bytecode for " + className);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) {
                throw new IOException("Invalid classfile magic in " + className);
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();

            String[] utf8 = new String[cpCount];
            int[] classNameIndex = new int[cpCount];
            int[][] refIndices = new int[cpCount][]; // [classIndex, nameAndTypeIndex]
            int[][] nameAndTypeIndices = new int[cpCount][]; // [nameIndex, descIndex]

            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++; // 8-byte constants take two constant-pool slots
                    }
                    case 7 -> classNameIndex[i] = in.readUnsignedShort();
                    case 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 9, 10, 11 -> refIndices[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                    case 12 -> nameAndTypeIndices[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    case 17, 18 -> {
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException("Unknown constant pool tag " + tag + " in " + className);
                }
            }

            List<String> matches = new ArrayList<>();
            for (int i = 1; i < cpCount; i++) {
                int[] ref = refIndices[i];
                if (ref == null) continue;
                int clsIdx = ref[0];
                int natIdx = ref[1];
                String owner = (clsIdx > 0 && clsIdx < cpCount && classNameIndex[clsIdx] > 0)
                        ? utf8[classNameIndex[clsIdx]]
                        : null;
                int[] nat = (natIdx > 0 && natIdx < cpCount) ? nameAndTypeIndices[natIdx] : null;
                if (owner == null || nat == null) continue;
                String name = utf8[nat[0]];
                String desc = utf8[nat[1]];
                String sig = name + ":" + desc;
                if (SCHEDULER_INTERNAL_NAME.equals(owner) && LOCATIONLESS_DESCRIPTORS.contains(sig)) {
                    matches.add(owner + "." + sig);
                }
            }
            return matches;
        }
    }
}
