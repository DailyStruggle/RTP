package io.github.dailystruggle.rtp.fabric.version;

/**
 * Mojmap-name-stable replacement for Fabric {@code ResourceLocation}
 * (a.k.a. {@code Identifier} from MC 1.21.11+, intermediary
 * {@code class_2960}). See {@code rtp-fabric-ADR-007}.
 *
 * <p>Common-side callers consume the {@code namespace:path} form via
 * {@link #key()}, matching how {@code rtp-core} config keys are written.
 * Per-version adapters construct one from whichever Mojmap type their
 * mapping snapshot uses (e.g. {@code ResourceLocation#getNamespace()} /
 * {@code #getPath()} on 1.21.5; the renamed accessors on 1.21.11+).</p>
 */
public record RTPRegistryKey(String namespace, String path) {
    /**
     * Convenience for the common {@code namespace:path} string form.
     */
    public String key() {
        return namespace + ":" + path;
    }

    @Override
    public String toString() {
        return key();
    }
}
