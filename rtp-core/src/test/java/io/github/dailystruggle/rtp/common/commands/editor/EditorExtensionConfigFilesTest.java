package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.api.editor.EditorExtension;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-107 §7: addon YAML declared by {@code configFiles()} joins the session {@code files} map and
 * is written only through Hot-Apply - structural checks, the owning extension's {@code validate},
 * {@code .bak}, atomic write, then {@code applied}. Errors fail the whole apply (S-004).
 */
@DisplayName("ADR-107 / S-004: addon config files go through the one Hot-Apply write path")
class EditorExtensionConfigFilesTest {

    private static final String FILE = "addons/Demo/zones/spawn/deep/actions.yml";

    @TempDir
    Path tempDir;
    private Path pluginDir;

    /** Declares {@link #FILE}; validate answers from {@code check}; records every applied path. */
    static class Owner implements EditorExtension {
        final String id;
        final List<String> files;
        volatile BiFunction<String, String, List<String>> check = (p, y) -> List.of();
        final List<String> validated = new CopyOnWriteArrayList<>();
        final List<String> applied = new CopyOnWriteArrayList<>();

        Owner(String id, List<String> files) {
            this.id = id;
            this.files = files;
        }

        @Override public String id() { return id; }
        @Override public int version() { return 1; }
        @Override public String displayName() { return "Owner"; }
        @Override public List<String> configFiles() { return files; }

        @Override
        public List<String> validate(String path, String yaml) {
            validated.add(path);
            return check.apply(path, yaml);
        }

        @Override public void applied(String path) { applied.add(path); }
    }

    @BeforeEach
    void setUp() throws IOException {
        File dir = tempDir.resolve("plugins/RTP").toFile();
        dir.mkdirs();
        RTPTestSetup.install(dir);
        pluginDir = RTP.serverAccessor.getPluginDirectory().toPath();
        Path f = pluginDir.resolve(FILE);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "cooldown: 5\n", StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        EditorExtensions.unregisterAll();
        EditorLiveFeed.stopActive("test teardown");
        RTPTestSetup.cleanUp();
    }

    private Owner register() {
        Owner o = new Owner("demo", List.of(FILE));
        EditorExtensions.registry().register(o);
        return o;
    }

    private void apply(Map<String, String> files) {
        EditorSessionManager m = EditorSessionManager.getInstance();
        m.applyPayload(m.createPayloadJson(files)).join();
    }

    @Test
    @DisplayName("Declared paths stay out of the plugin folder root, core folders and hidden folders")
    void pathRules() {
        assertEquals("addons/Demo/a/b/c.yaml", EditorExtensions.validatePath("addons\\Demo\\a\\b\\c.yaml"));
        for (String bad : new String[]{"actions.yml", "config.yml", "regions/x.yml", "Advanced/x.yml", "lang/en/x.yml",
                "addons/.secret/x.yml", "addons/x.json", "../x.yml", "/abs/x.yml", "C:/x.yml"}) {
            assertThrows(IllegalArgumentException.class, () -> EditorExtensions.validatePath(bad), bad);
        }
        assertThrows(IllegalArgumentException.class,
                () -> EditorExtensions.registry().register(new Owner("demo", List.of("regions/default.yml"))),
                "registration rejects a core file");
    }

    @Test
    @DisplayName("The session files map carries declared files at any depth; the first owner keeps a shared path")
    void collected() {
        register();
        Owner late = new Owner("zeta", List.of(FILE));
        EditorExtensions.registry().register(late);
        Map<String, String> files = EditorSessionManager.getInstance().collectCurrentConfigs();
        assertEquals("cooldown: 5\n", files.get(FILE), "deeper than the folder walk");
        assertEquals("demo", EditorExtensions.fileOwners().get(FILE), "first owner in id order");
    }

    @Test
    @DisplayName("Accepted: the owner validates, the file is backed up and written, then applied() fires")
    void acceptedApply() throws IOException {
        Owner o = register();
        apply(Map.of(FILE, "cooldown: 9\n"));
        Path f = pluginDir.resolve(FILE);
        assertEquals("cooldown: 9\n", Files.readString(f, StandardCharsets.UTF_8));
        assertEquals("cooldown: 5\n", Files.readString(f.resolveSibling("actions.yml.bak"), StandardCharsets.UTF_8));
        assertEquals(List.of(FILE), o.validated);
        assertEquals(List.of(FILE), o.applied);
    }

    @Test
    @DisplayName("Rejected: every addon error fails the whole apply, nothing is written and applied() never fires")
    void rejectedApply() throws IOException {
        Owner o = register();
        o.check = (p, y) -> List.of("cooldown must be at least 0", " ", "zone 'spawn' has no radius");
        CompletionException e = assertThrows(CompletionException.class,
                () -> apply(Map.of(FILE, "cooldown: -1\n", "addons/Other/free.yml", "x: 1\n")));
        String msg = e.getCause().getMessage();
        assertTrue(msg.contains("cooldown must be at least 0"), msg);
        assertTrue(msg.contains("zone 'spawn' has no radius"), msg);
        assertTrue(msg.contains(FILE + " (demo)"), "errors name the file and its owner: " + msg);
        assertEquals("cooldown: 5\n", Files.readString(pluginDir.resolve(FILE), StandardCharsets.UTF_8));
        assertFalse(Files.exists(pluginDir.resolve("addons/Other/free.yml")), "the whole apply fails, not just the addon file");
        assertTrue(o.applied.isEmpty());
    }

    @Test
    @DisplayName("A throwing validate fails the apply visibly instead of passing the file")
    void throwingValidate() {
        Owner o = register();
        o.check = (p, y) -> { throw new IllegalStateException("parser exploded"); };
        CompletionException e = assertThrows(CompletionException.class, () -> apply(Map.of(FILE, "cooldown: 1\n")));
        assertTrue(e.getCause().getMessage().contains("validation failed: parser exploded"), e.getCause().getMessage());
        assertTrue(o.applied.isEmpty());
    }

    @Test
    @DisplayName("A throwing applied() is logged only; the write stands")
    void throwingApplied() throws IOException {
        Owner o = new Owner("demo", List.of(FILE)) {
            @Override public void applied(String path) { throw new IllegalStateException("reload failed"); }
        };
        EditorExtensions.registry().register(o);
        apply(Map.of(FILE, "cooldown: 2\n"));
        assertEquals("cooldown: 2\n", Files.readString(pluginDir.resolve(FILE), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("A declared path that escapes the plugin folder through a symlink is left out and refused")
    void symlinkEscape() throws IOException {
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("x.yml"), "secret: 1\n", StandardCharsets.UTF_8);
        Path link = pluginDir.resolve("addons/Link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("symlinks unavailable here: " + e);
        }
        EditorExtensions.registry().register(new Owner("demo", List.of("addons/Link/x.yml")));
        assertFalse(EditorExtensions.insideRealPath(pluginDir, "addons/Link/x.yml"));
        assertFalse(EditorSessionManager.getInstance().collectCurrentConfigs().containsKey("addons/Link/x.yml"));
        CompletionException e = assertThrows(CompletionException.class, () -> apply(Map.of("addons/Link/x.yml", "secret: 2\n")));
        assertTrue(e.getCause().getMessage().contains("outside the plugin folder"), e.getCause().getMessage());
        assertEquals("secret: 1\n", Files.readString(outside.resolve("x.yml"), StandardCharsets.UTF_8));
    }
}
