package io.github.dailystruggle.rtp.anvil;

import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BuiltInTestable;
import io.github.dailystruggle.bit.PbitResult;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dynamic zero-dependency host binding for Zstandard decompression with Built-In Testing (PBIT).
 *
 * <p>Probes the host server classpath for native or bundled Zstandard implementations
 * (such as {@code com.github.luben.zstd.Zstd} provided by Leaves, Gale, LinearPaper, or Fabric mods)
 * and verifies functionality via a startup canary self-test. Leaves the runtime plugin jar completely
 * dependency-free.</p>
 */
public final class DynamicZstdBinding implements BuiltInTestable {

    private static final Logger LOG = Logger.getLogger(DynamicZstdBinding.class.getName());
    public static final DynamicZstdBinding INSTANCE = new DynamicZstdBinding();

    /**
     * Pre-computed valid Zstandard frame encoding "RTP_CANARY_TEST\n" (16 bytes).
     */
    private static final byte[] CANARY_FRAME = new byte[] {
            0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 0x00, 0x50, (byte) 0x81, 0x00, 0x00,
            0x52, 0x54, 0x50, 0x5f, 0x43, 0x41, 0x4e, 0x41, 0x52, 0x59, 0x5f,
            0x54, 0x45, 0x53, 0x54, 0x0a
    };

    private static final String CANARY_EXPECTED = "RTP_CANARY_TEST\n";

    private interface DecompressorInvoker {
        byte[] decompress(byte[] src, int srcOff, int srcLen, int maxDecompressedBytes) throws IOException;
    }

    /** Wraps a non-IO failure from a reflective host call; preserves the cause. */
    private static IOException invocationFailure(Throwable t) {
        if (t instanceof IOException io) return io;
        CorruptRegionEntryException ex = new CorruptRegionEntryException("Zstandard decompression failed: " + t.getMessage());
        ex.initCause(t);
        return ex;
    }

    private static final DecompressorInvoker INVOKER;
    private static final PbitResult PBIT_RESULT;
    private static final BitHealth HEALTH;
    private static final boolean HOST_ENGINE_MISSING;

    static {
        DecompressorInvoker resolvedInvoker = null;
        boolean hostEngineMissing = false;
        PbitResult result;
        long start = System.nanoTime();

        try {
            resolvedInvoker = resolveInvoker();
            if (resolvedInvoker != null) {
                // Execute PBIT canary self-test
                byte[] decompressed = resolvedInvoker.decompress(CANARY_FRAME, 0, CANARY_FRAME.length, 1024);
                String text = new String(decompressed, StandardCharsets.UTF_8);
                if (CANARY_EXPECTED.equals(text)) {
                    long duration = System.nanoTime() - start;
                    result = PbitResult.ok("DynamicZstdBinding", duration, "Host Zstd runtime operational");
                } else {
                    long duration = System.nanoTime() - start;
                    resolvedInvoker = null;
                    result = PbitResult.failed("DynamicZstdBinding", duration,
                            "Canary output mismatch: expected '" + CANARY_EXPECTED + "', got '" + text + "'", null);
                }
            } else {
                long duration = System.nanoTime() - start;
                hostEngineMissing = true;
                result = PbitResult.failed("DynamicZstdBinding", duration,
                        "Host Zstandard engine not present on classpath", null);
            }
        } catch (Throwable t) {
            long duration = System.nanoTime() - start;
            resolvedInvoker = null;
            result = PbitResult.failed("DynamicZstdBinding", duration,
                    "Host Zstd failed PBIT canary: " + t.getMessage(), t);
        }

        INVOKER = resolvedInvoker;
        PBIT_RESULT = result;
        HEALTH = (resolvedInvoker != null) ? BitHealth.HEALTHY : BitHealth.DEGRADED;
        HOST_ENGINE_MISSING = hostEngineMissing;

        if (resolvedInvoker != null) {
            LOG.fine("[RTP] DynamicZstdBinding passed PBIT in " + (PBIT_RESULT.durationNanos() / 1_000_000.0) + " ms");
        } else {
            LOG.log(Level.INFO, "[RTP] Host Zstandard runtime not active ({0}). Safe live-load fallback active.",
                    PBIT_RESULT.diagnosticMessage());
        }
    }

    private DynamicZstdBinding() {}

    /**
     * Checks if a functional host Zstandard decompressor is available.
     */
    public static boolean isAvailable() {
        return INVOKER != null;
    }

    /** Returns whether no host Zstandard engine was found, rather than one failing its canary. */
    public static boolean hostEngineMissing() {
        return HOST_ENGINE_MISSING;
    }

    /**
     * Returns the PBIT result executed at initialization.
     */
    public static PbitResult pbitResult() {
        return PBIT_RESULT;
    }

    /**
     * Returns the current operational health of the Zstd binding.
     */
    public static BitHealth health() {
        return HEALTH;
    }

    @Override
    public PbitResult executePbit() {
        return PBIT_RESULT;
    }

    @Override
    public BitHealth getHealthStatus() {
        return HEALTH;
    }

    /**
     * Decompresses the given Zstandard frame using the discovered host engine.
     *
     * @param src                  source byte array
     * @param srcOff               offset in source
     * @param srcLen               length of compressed frame
     * @param maxDecompressedBytes maximum allowed uncompressed byte count
     * @return uncompressed bytes
     * @throws IOException on decompression failure or missing runtime
     */
    public static byte[] decompress(byte[] src, int srcOff, int srcLen, int maxDecompressedBytes) throws IOException {
        DecompressorInvoker invoker = INVOKER;
        if (invoker == null) {
            throw new UnsupportedAnvilFormatException("Zstandard decompression requested but host runtime is unavailable: "
                    + PBIT_RESULT.diagnosticMessage());
        }
        try {
            return invoker.decompress(src, srcOff, srcLen, maxDecompressedBytes);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            CorruptRegionEntryException ex = new CorruptRegionEntryException("Zstandard decompression failed: " + t.getMessage());
            ex.initCause(t);
            throw ex;
        }
    }

    private static DecompressorInvoker resolveInvoker() {
        ClassLoader cl = DynamicZstdBinding.class.getClassLoader();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();

        // 1. Candidate: com.github.luben.zstd.Zstd (standard in Leaves, Gale, LinearPaper, Fabric Linear)
        try {
            Class<?> zstdClass = Class.forName("com.github.luben.zstd.Zstd", true, cl);
            MethodHandle decompressByteArray = lookup.findStatic(zstdClass, "decompressByteArray",
                    MethodType.methodType(long.class, byte[].class, int.class, int.class, byte[].class, int.class, int.class));
            MethodHandle decompressedSize = lookup.findStatic(zstdClass, "decompressedSize",
                    MethodType.methodType(long.class, byte[].class, int.class, int.class));
            MethodHandle isError = lookup.findStatic(zstdClass, "isError",
                    MethodType.methodType(boolean.class, long.class));
            MethodHandle getErrorName = lookup.findStatic(zstdClass, "getErrorName",
                    MethodType.methodType(String.class, long.class));

            return (src, srcOff, srcLen, maxDecompressedBytes) -> {
                try {
                    long expectedSize = (long) decompressedSize.invokeExact(src, srcOff, srcLen);
                    int targetSize;
                    if (expectedSize > 0 && expectedSize <= maxDecompressedBytes) {
                        targetSize = (int) expectedSize;
                    } else if (expectedSize <= 0) {
                        targetSize = Math.min(maxDecompressedBytes, Math.max(8192, srcLen * 4));
                    } else {
                        throw new CorruptRegionEntryException("Declared Zstd uncompressed size " + expectedSize
                                + " exceeds limit " + maxDecompressedBytes);
                    }

                    byte[] dst = new byte[targetSize];
                    long written = (long) decompressByteArray.invokeExact(dst, 0, targetSize, src, srcOff, srcLen);
                    if ((boolean) isError.invokeExact(written)) {
                        String err = (String) getErrorName.invokeExact(written);
                        throw new IOException("Zstd error: " + err);
                    }
                    if (written < dst.length) {
                        byte[] exact = new byte[(int) written];
                        System.arraycopy(dst, 0, exact, 0, (int) written);
                        return exact;
                    }
                    return dst;
                } catch (Throwable t) {
                    throw invocationFailure(t);
                }
            };
        } catch (Throwable ignored) {
            // Not present or signature mismatch, continue to next candidate
        }

        // 2. Candidate: io.airlift.compress.zstd.ZstdDecompressor
        try {
            Class<?> decompressorClass = Class.forName("io.airlift.compress.zstd.ZstdDecompressor", true, cl);
            Object decompressorInstance = decompressorClass.getConstructor().newInstance();
            MethodHandle decompressMethod = lookup.findVirtual(decompressorClass, "decompress",
                    MethodType.methodType(int.class, byte[].class, int.class, int.class, byte[].class, int.class, int.class));

            return (src, srcOff, srcLen, maxDecompressedBytes) -> {
                byte[] dst = new byte[maxDecompressedBytes];
                int written;
                try {
                    written = (int) decompressMethod.invoke(decompressorInstance, src, srcOff, srcLen, dst, 0, maxDecompressedBytes);
                } catch (Throwable t) {
                    throw invocationFailure(t);
                }
                if (written < dst.length) {
                    byte[] exact = new byte[written];
                    System.arraycopy(dst, 0, exact, 0, written);
                    return exact;
                }
                return dst;
            };
        } catch (Throwable ignored) {
            // Fallback not present
        }

        return null;
    }
}
