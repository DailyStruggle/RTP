package io.github.dailystruggle.rtp.anvil;

/**
 * Lean, zero-allocation read-only wrapper over a Minecraft 4-bit nibble array
 * (such as section-level {@code BlockLight} or {@code SkyLight} layers).
 *
 * <p>Fails closed safely: empty, null, or truncated byte arrays never throw exceptions
 * and return {@code 0} for all coordinates.
 */
public final class NibbleArray {

    private final byte[] data;

    public NibbleArray(byte[] data) {
        this.data = data;
    }

    /**
     * Reads the 4-bit value at section-local coordinates {@code (x, y, z)}, each in {@code 0..15}.
     * Standard Minecraft block coordinate indexing: {@code (y << 8) | (z << 4) | x}.
     *
     * @return 4-bit nibble value {@code 0..15}, or {@code 0} if data is absent, truncated, or invalid
     */
    public int get(int x, int y, int z) {
        if ((x | y | z) < 0 || x > 15 || y > 15 || z > 15) {
            return 0;
        }
        int index = (y << 8) | (z << 4) | x;
        return get(index);
    }

    /**
     * Reads the 4-bit nibble at flat index {@code 0..4095}.
     *
     * @param index flat nibble index
     * @return 4-bit nibble value {@code 0..15}, or {@code 0} if data is absent or index out of range
     */
    public int get(int index) {
        if (data == null || index < 0) {
            return 0;
        }
        int byteIndex = index >> 1;
        if (byteIndex >= data.length) {
            return 0;
        }
        int val = data[byteIndex] & 0xFF;
        if ((index & 1) == 0) {
            return val & 0x0F;
        } else {
            return (val >>> 4) & 0x0F;
        }
    }

    /**
     * The raw backing bytes, or {@code null} if absent.
     */
    public byte[] data() {
        return data;
    }

    /**
     * True if the backing byte array has the full standard 2048-byte length (4096 nibbles).
     */
    public boolean isComplete() {
        return data != null && data.length >= 2048;
    }
}
