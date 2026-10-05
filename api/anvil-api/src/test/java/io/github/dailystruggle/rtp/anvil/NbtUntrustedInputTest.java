package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Untrusted-input limits of {@link Nbt}: nesting depth (vanilla 512) and declared counts vs
 * remaining bytes. Every reader path (full, selective recurse, selective skip) must fail with
 * {@link IOException}, never {@link StackOverflowError} or an up-front large allocation.
 */
@DisplayName("ADR-016: NBT reader rejects deep nesting and oversized declared counts")
class NbtUntrustedInputTest {

    private static final Nbt.SelectiveFilter RECURSE_ALL = (p, n, t) -> Nbt.SelectiveFilter.Decision.RECURSE;
    private static final Nbt.SelectiveFilter SKIP_ALL = (p, n, t) -> Nbt.SelectiveFilter.Decision.SKIP;

    @Test
    @DisplayName("512 nested containers decode on every path; 513 are rejected")
    void depthBoundaryMatchesVanilla() throws IOException {
        for (byte[] ok : List.of(nestedCompounds(Nbt.MAX_DEPTH), nestedLists(Nbt.MAX_DEPTH))) {
            Nbt.readRootCompound(ok);
            Nbt.readRootCompoundSelective(ok, RECURSE_ALL);
            Nbt.readRootCompoundSelective(ok, SKIP_ALL);
        }
        for (byte[] bad : List.of(nestedCompounds(Nbt.MAX_DEPTH + 1), nestedLists(Nbt.MAX_DEPTH + 1))) {
            assertAllPathsReject(bad, "max depth");
        }
    }

    @Test
    @DisplayName("200k-deep nesting fails with IOException, not StackOverflowError")
    void deepNestingIsIoException() throws IOException {
        assertAllPathsReject(nestedCompounds(200_000), "max depth");
        assertAllPathsReject(nestedLists(200_000), "max depth");
    }

    @Test
    @DisplayName("16M-element declarations with a short body are rejected before allocation")
    void oversizedCountsRejectedWithoutAllocation() throws IOException {
        int n = Nbt.MAX_ARRAY_LENGTH;
        byte[][] cases = {
            oversized(Nbt.TAG_BYTE_ARRAY, -1, n),
            oversized(Nbt.TAG_INT_ARRAY, -1, n),
            oversized(Nbt.TAG_LONG_ARRAY, -1, n),
            oversized(Nbt.TAG_LIST, Nbt.TAG_LONG, n),
            oversized(Nbt.TAG_LIST, Nbt.TAG_COMPOUND, n),
            oversized(Nbt.TAG_LIST, Nbt.TAG_LIST, n),
        };
        for (byte[] c : cases) {
            long full = allocatedBy(() -> assertThrowsRemain(() -> Nbt.readRootCompound(c)));
            long keep = allocatedBy(() -> assertThrowsRemain(() -> Nbt.readRootCompoundSelective(c,
                    (p, name, t) -> Nbt.SelectiveFilter.Decision.KEEP)));
            long rec = allocatedBy(() -> assertThrowsRemain(() -> Nbt.readRootCompoundSelective(c, RECURSE_ALL)));
            // Smallest rejected array would be 16 MiB; a few KiB of bookkeeping is expected.
            assertTrue(full < 4 << 20 && keep < 4 << 20 && rec < 4 << 20,
                    "allocated full=" + full + " keep=" + keep + " recurse=" + rec);
        }
    }

    @Test
    @DisplayName("Counts that exactly fit the remaining bytes still decode")
    void exactFitStillDecodes() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(Nbt.TAG_COMPOUND);
        out.writeUTF("");
        out.writeByte(Nbt.TAG_LIST);
        out.writeUTF("c");
        out.writeByte(Nbt.TAG_COMPOUND);
        out.writeInt(2);
        out.writeByte(Nbt.TAG_END);
        out.writeByte(Nbt.TAG_END);
        out.writeByte(Nbt.TAG_LONG_ARRAY);
        out.writeUTF("l");
        out.writeInt(1);
        out.writeLong(7L);
        out.writeByte(Nbt.TAG_END);
        byte[] b = bos.toByteArray();

        for (LinkedHashMap<String, Object> root : List.of(Nbt.readRootCompound(b),
                Nbt.readRootCompoundSelective(b, RECURSE_ALL))) {
            assertEquals(2, ((Nbt.NbtList) root.get("c")).items.size());
            assertEquals(7L, ((long[]) root.get("l"))[0]);
        }
    }

    // ------------------------------------------------------------------------------ helpers

    private static void assertAllPathsReject(byte[] b, String msg) {
        for (Executable e : List.<Executable>of(
                () -> Nbt.readRootCompound(b),
                () -> Nbt.readRootCompoundSelective(b, RECURSE_ALL),
                () -> Nbt.readRootCompoundSelective(b, SKIP_ALL))) {
            IOException ex = assertThrows(IOException.class, e);
            assertTrue(ex.getMessage().contains(msg), ex.getMessage());
        }
    }

    private static void assertThrowsRemain(Executable e) {
        IOException ex = assertThrows(IOException.class, e);
        assertTrue(ex.getMessage().contains("remain"), ex.getMessage());
    }

    private static long allocatedBy(Runnable r) {
        java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
        assumeTrue(base instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) base;
        assumeTrue(mx.isThreadAllocatedMemorySupported() && mx.isThreadAllocatedMemoryEnabled());
        long before = mx.getCurrentThreadAllocatedBytes();
        r.run();
        return mx.getCurrentThreadAllocatedBytes() - before;
    }

    /** Root compound plus {@code containers - 1} nested compounds. */
    private static byte[] nestedCompounds(int containers) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(containers * 5);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(Nbt.TAG_COMPOUND);
        out.writeUTF("");
        for (int i = 1; i < containers; i++) {
            out.writeByte(Nbt.TAG_COMPOUND);
            out.writeUTF("c");
        }
        for (int i = 0; i < containers; i++) out.writeByte(Nbt.TAG_END);
        return bos.toByteArray();
    }

    /** Root compound holding {@code containers - 1} singly-nested lists (innermost empty). */
    private static byte[] nestedLists(int containers) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(containers * 5);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(Nbt.TAG_COMPOUND);
        out.writeUTF("");
        out.writeByte(Nbt.TAG_LIST);
        out.writeUTF("l");
        for (int i = 2; i < containers; i++) {
            out.writeByte(Nbt.TAG_LIST);
            out.writeInt(1);
        }
        out.writeByte(Nbt.TAG_END);
        out.writeInt(0);
        out.writeByte(Nbt.TAG_END);
        return bos.toByteArray();
    }

    /** Root compound with one child declaring {@code count} elements, followed by 16 bytes. */
    private static byte[] oversized(byte type, int elemType, int count) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(Nbt.TAG_COMPOUND);
        out.writeUTF("");
        out.writeByte(type);
        out.writeUTF("x");
        if (type == Nbt.TAG_LIST) out.writeByte(elemType);
        out.writeInt(count);
        out.write(new byte[16]);
        return bos.toByteArray();
    }
}
