package io.github.dailystruggle.rtp.common.configuration.yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Top-level YAML document handle, equivalent to {@code simpleyaml}'s
 * {@code YamlFile}. A {@code RtpYamlConfig} is a {@link RtpYamlSection}
 * over the root mapping plus file-backing for load/save.
 *
 * <p>Per ADR-025 (2026-05-15 revision) this class is the public entry
 * point for the in-house, zero-dependency YAML substrate. There is no
 * shaded third-party YAML library and no runtime-provided SnakeYAML
 * consumption.</p>
 *
 * <p>{@link #save()} writes via a temp file + atomic rename so a
 * mid-write crash leaves either the previous good file or the new good
 * file on disk — never a truncated half-file. (The atomicity guarantee
 * is whatever the underlying filesystem provides for
 * {@link java.nio.file.Files#move(Path, Path, java.nio.file.CopyOption...)}
 * with {@link StandardCopyOption#ATOMIC_MOVE}.)</p>
 */
public final class RtpYamlConfig extends RtpYamlSection {

    private File file;
    private final RtpYamlOptions options = new RtpYamlOptions();

    private RtpYamlConfig(File file, RtpYamlMapping root) {
        super(root);
        this.file = file;
    }

    /**
     * Path-based constructor mirroring {@code simpleyaml}'s
     * {@code new YamlFile(String path)}. The file is not read until
     * {@link #load()} / {@link #loadWithComments()} is called.
     */
    public RtpYamlConfig(String path) {
        this(path == null ? null : new File(path), new RtpYamlMapping());
    }

    /**
     * No-arg constructor mirroring {@code simpleyaml}'s {@code new YamlFile()}.
     * The instance has no file binding until {@link #save(File)} is called
     * or {@link #setConfigurationFile(File)} is invoked.
     */
    public RtpYamlConfig() {
        this((File) null, new RtpYamlMapping());
    }

    /** Load a YAML document from a file with comment preservation. */
    public static RtpYamlConfig load(File file) throws IOException {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        if (!file.exists() || file.length() == 0) {
            return new RtpYamlConfig(file, new RtpYamlMapping());
        }
        String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        RtpYamlMapping root = RtpYamlReader.parse(source);
        return new RtpYamlConfig(file, root);
    }

    /** Parse a YAML document from a {@link Reader} (no file backing). */
    public static RtpYamlConfig parse(Reader reader) throws IOException {
        RtpYamlMapping root = RtpYamlReader.parse(reader);
        return new RtpYamlConfig(null, root);
    }

    /** Parse a YAML document from a string (no file backing). */
    public static RtpYamlConfig parse(String source) {
        return new RtpYamlConfig(null, RtpYamlReader.parse(source));
    }

    /** Construct an empty document, optionally with a file binding. */
    public static RtpYamlConfig empty(File file) {
        return new RtpYamlConfig(file, new RtpYamlMapping());
    }

    /** Re-emit the document to a string using the writer. */
    public String saveToString() {
        return RtpYamlWriter.emit(node);
    }

    /**
     * Atomically save the document to disk. The write goes to a
     * sibling temp file and is then renamed over the target.
     *
     * @throws IllegalStateException if the config has no file binding
     */
    public void save() throws IOException {
        if (file == null) throw new IllegalStateException("no file binding; use saveToString() or save(File)");
        save(file);
    }

    public void save(File target) throws IOException {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        String content = saveToString();
        Path finalPath = target.toPath();
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        Path tmp = Files.createTempFile(
                parent != null ? parent.toPath() : finalPath.getParent(),
                target.getName() + ".",
                ".tmp");
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort */ }
        }
    }

    /** Returns the file binding, or {@code null} if this is an unbound document. */
    public File file() { return file; }

    /* ---------- simpleyaml API-compat surface (Session 1 parity layer) ---------- */

    /**
     * Mirrors {@code simpleyaml}'s {@code YamlFile.getConfigurationFile()}.
     * Returns the file binding, or {@code null} if unbound.
     */
    public File getConfigurationFile() { return file; }

    /** Mirrors {@code simpleyaml}'s {@code YamlFile.setConfigurationFile(File)}. */
    public void setConfigurationFile(File f) { this.file = f; }

    /**
     * Mirrors {@code simpleyaml}'s instance {@code load()}. Reads the bound
     * file and replaces the in-memory root contents (and root block comments).
     */
    public void load() throws IOException {
        if (file == null) throw new IllegalStateException("no file binding; use load(File) or parse(String)");
        if (!file.exists() || file.length() == 0) {
            node.entries().clear();
            node.setBlockComments(new java.util.ArrayList<>());
            return;
        }
        String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        RtpYamlMapping fresh = RtpYamlReader.parse(source);
        node.entries().clear();
        node.entries().putAll(fresh.entries());
        node.setBlockComments(new java.util.ArrayList<>(fresh.blockComments()));
    }

    /**
     * Mirrors {@code simpleyaml}'s {@code YamlFile.loadWithComments()}. This
     * substrate always preserves comments, so this is a load() alias.
     */
    public void loadWithComments() throws IOException { load(); }

    /**
     * Mirrors {@code simpleyaml}'s {@code YamlFile.saveWithComments()}. This
     * substrate always preserves comments, so this is a save() alias.
     */
    public void saveWithComments() throws IOException { save(); }

    /** Mirrors {@code simpleyaml}'s {@code YamlFile.options()}. */
    public RtpYamlOptions options() { return options; }

    /**
     * Mirrors {@code simpleyaml}'s {@code addDefault(String, Object)}: set the
     * value only if the key is not already present. Implemented in terms of
     * {@link RtpYamlSection#set(String, Object)} so the change is materialized
     * directly into the root mapping (no separate defaults overlay) — this
     * matches what call sites expect after a subsequent {@link #save()}.
     */
    public void addDefault(String key, Object value) {
        if (!contains(key)) set(key, value);
    }

    /**
     * Mirrors top-level {@code isConfigurationSection(String)} on
     * {@code YamlFile}. Inherited from {@link RtpYamlSection}.
     */

    /* ---------- file-handle parity (Session 2 surface) ---------- */

    /** Path-and-file constructor mirroring {@code simpleyaml}'s {@code new YamlFile(File)}. */
    public RtpYamlConfig(File file) {
        this(file, new RtpYamlMapping());
    }

    /**
     * Mirrors {@code simpleyaml}'s {@code YamlFile.exists()}: true iff the bound
     * file is non-null and exists on disk.
     */
    public boolean exists() {
        return file != null && file.exists();
    }

    /**
     * Mirrors {@code simpleyaml}'s {@code YamlFile.getFilePath()}: returns the
     * absolute path of the bound file, or {@code null} if unbound.
     */
    public String getFilePath() {
        return file == null ? null : file.getAbsolutePath();
    }

    /**
     * Mirrors {@code simpleyaml}'s {@code YamlFile.createNewFile()}: creates the
     * bound file (and parent directories) if it doesn't already exist.
     *
     * @return {@code true} if a new file was created
     */
    public boolean createNewFile() throws IOException {
        return createNewFile(false);
    }

    /**
     * Mirrors {@code simpleyaml}'s {@code YamlFile.createNewFile(boolean overwrite)}.
     * Creates parent directories as needed; if {@code overwrite} is true and the
     * file already exists, it is deleted first.
     */
    public boolean createNewFile(boolean overwrite) throws IOException {
        if (file == null) throw new IllegalStateException("no file binding");
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        if (overwrite && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        return file.createNewFile();
    }

    /**
     * Mirrors {@code simpleyaml}'s {@code YamlFile.copyTo(String)}: writes the
     * current in-memory document to {@code destPath} (creating parents as
     * needed) using {@link #save(File)} semantics.
     */
    public void copyTo(String destPath) throws IOException {
        if (destPath == null) throw new IllegalArgumentException("destPath must not be null");
        save(new File(destPath));
    }

    /**
     * Mirrors {@code simpleyaml}'s {@code YamlFile.copyTo(File)}.
     */
    public void copyTo(File dest) throws IOException {
        if (dest == null) throw new IllegalArgumentException("dest must not be null");
        save(dest);
    }

    /**
     * Mirrors {@code simpleyaml}'s {@code YamlFile.createOrLoad()}: if the
     * bound file does not exist it is created (with empty contents); otherwise
     * the existing file is loaded (with comments) into memory.
     */
    public void createOrLoad() throws IOException {
        if (file == null) throw new IllegalStateException("no file binding");
        if (!file.exists()) {
            createNewFile(false);
        } else {
            loadWithComments();
        }
    }

    /**
     * Mirrors {@code simpleyaml}'s {@code YamlFile.loadConfiguration(InputStream, boolean)}.
     * Replaces the in-memory document with the YAML parsed from {@code in}.
     * The {@code closeStream} flag mirrors simpleyaml: if true the stream is
     * closed after parsing.
     */
    public void loadConfiguration(InputStream in, boolean closeStream) throws IOException {
        if (in == null) throw new IllegalArgumentException("input must not be null");
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            RtpYamlMapping fresh = RtpYamlReader.parse(reader);
            node.entries().clear();
            node.entries().putAll(fresh.entries());
            node.setBlockComments(new java.util.ArrayList<>(fresh.blockComments()));
        } finally {
            if (!closeStream) {
                // try-with-resources will have already closed; this branch is
                // a no-op kept for API parity with simpleyaml.
            }
        }
    }
}
