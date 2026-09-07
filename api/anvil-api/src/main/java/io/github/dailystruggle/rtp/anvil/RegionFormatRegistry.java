package io.github.dailystruggle.rtp.anvil;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe registry of pluggable region-file formats and readers (ADR-077).
 *
 * <p>Supports standard Anvil ({@code .mca}) by default, and allows external addons
 * or runtime modules (such as Linear format via {@code LeafRTPLinearAddon}) to register
 * custom decoders either programmatically or through {@link ServiceLoader}.</p>
 */
public final class RegionFormatRegistry {

    private static final Logger LOG = Logger.getLogger(RegionFormatRegistry.class.getName());

    private static final Map<String, RegionFileReader> REGISTRY = new ConcurrentHashMap<>();

    static {
        // Register default vanilla Anvil format
        REGISTRY.put(".mca", AnvilReader.INSTANCE);
        // Discover any SPI providers on classpath
        loadServiceProviders();
    }

    private RegionFormatRegistry() {}

    /**
     * Loads any {@link RegionFileReaderProvider} services discovered via {@link ServiceLoader}.
     */
    public static void loadServiceProviders() {
        try {
            ServiceLoader<RegionFileReaderProvider> loader = ServiceLoader.load(RegionFileReaderProvider.class,
                    RegionFormatRegistry.class.getClassLoader());
            for (RegionFileReaderProvider provider : loader) {
                if (provider != null && provider.extension() != null && provider.reader() != null) {
                    register(provider.extension(), provider.reader());
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[RTP] Failed to discover RegionFileReaderProvider services", t);
        }
    }

    /**
     * Registers a reader for the specified file extension.
     *
     * @param extension the file extension, e.g. {@code ".linear"} or {@code "linear"}
     * @param reader    the reader implementation
     */
    public static void register(String extension, RegionFileReader reader) {
        Objects.requireNonNull(extension, "extension");
        Objects.requireNonNull(reader, "reader");
        String normalized = normalizeExtension(extension);
        REGISTRY.put(normalized, reader);
    }

    /**
     * Unregisters a previously registered format. Vanilla {@code .mca} cannot be unregistered.
     *
     * @param extension the file extension
     * @return the removed reader, or null
     */
    public static RegionFileReader unregister(String extension) {
        if (extension == null) return null;
        String normalized = normalizeExtension(extension);
        if (".mca".equals(normalized)) {
            return null; // Cannot unregister built-in Anvil reader
        }
        return REGISTRY.remove(normalized);
    }

    /**
     * Retrieves the reader for the given extension, or null if not registered.
     *
     * @param extension the file extension
     * @return the reader, or null
     */
    public static RegionFileReader getReader(String extension) {
        if (extension == null) return null;
        return REGISTRY.get(normalizeExtension(extension));
    }

    /**
     * Checks if a reader is registered for the given extension.
     */
    public static boolean isRegistered(String extension) {
        if (extension == null) return false;
        return REGISTRY.containsKey(normalizeExtension(extension));
    }

    /**
     * Returns an unmodifiable set of all registered extensions (with leading dot).
     */
    public static Set<String> getRegisteredExtensions() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }

    /**
     * Resets the registry back to default (only built-in {@code .mca}) and re-polls ServiceLoader.
     */
    public static void reset() {
        REGISTRY.clear();
        REGISTRY.put(".mca", AnvilReader.INSTANCE);
        loadServiceProviders();
    }

    private static String normalizeExtension(String ext) {
        String clean = ext.trim().toLowerCase(Locale.ROOT);
        return clean.startsWith(".") ? clean : "." + clean;
    }
}
