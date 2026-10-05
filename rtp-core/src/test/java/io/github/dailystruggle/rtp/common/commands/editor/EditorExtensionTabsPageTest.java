package io.github.dailystruggle.rtp.common.commands.editor;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 1 addon tabs of the web editor (ADR-107 §6.1, §7): the page's own renderer and YAML staging
 * helpers, lifted from docs/editor/index.html and run in GraalJS against a minimal DOM and a
 * stubbed channel client. Covers tab placement, the six widget kinds, placeholders for unknown
 * kinds, error descriptors, state pushes, declared-only sends and form staging into Hot-Apply.
 */
@DisplayName("ADR-107: the web editor renders addon tabs declaratively and stages addon YAML for Hot-Apply")
class EditorExtensionTabsPageTest {

    private static final String[] PAGE_FUNCTIONS = {
            "stripYamlValue", "parseYamlLines", "yamlKeyExtent", "yamlScalar", "yamlScalarAt", "yamlSetScalar",
            "switchPanel", "hotApplyChangedFiles"};
    private static final String[] PAGE_CONSTS = {"EXT_ID_RE", "EXT_PALETTE", "EXT_ERRORS", "EXT_FIELD_RE"};

    /** Minimal DOM (class / id / data-ext-tab selectors), channel client and staging globals. */
    private static final String STUBS = String.join("\n",
            "class El {",
            "  constructor(tag) { this.tagName = tag.toUpperCase(); this.children = []; this.style = {}; this.dataset = {};",
            "    this.className = ''; this._text = ''; this.attributes = []; this.id = ''; this.parent = null; }",
            "  get textContent() { return this._text + this.children.map(c => c.textContent).join(''); }",
            "  set textContent(v) { this._text = String(v); this.children = []; }",
            "  set innerHTML(v) { this.content._text = String(v); }",
            "  get content() { if (!this._content) this._content = new El('fragment'); return this._content; }",
            "  appendChild(c) { c.parent = this; this.children.push(c); return c; }",
            "  append(...cs) { for (const c of cs) this.appendChild(c); }",
            "  replaceChildren(...cs) { this.children = []; this._text = ''; this.append(...cs); }",
            "  remove() { if (this.parent) this.parent.children = this.parent.children.filter(c => c !== this); this.parent = null; }",
            "  get classList() { const s = this; return {",
            "    add(c) { if (!s.className.split(' ').includes(c)) s.className = (s.className + ' ' + c).trim(); },",
            "    remove(c) { s.className = s.className.split(' ').filter(x => x && x !== c).join(' '); } }; }",
            "  all() { return [this, ...this.children.flatMap(c => c.all())]; }",
            "  querySelectorAll(sel) { return this.all().slice(1).filter(e => matches(e, sel)); }",
            "}",
            "function matches(e, sel) {",
            "  if (sel === '[data-ext-tab]') return e.dataset.extTab !== undefined;",
            "  if (sel[0] === '.') { const cls = e.className.split(' '); return sel.slice(1).split('.').every(c => cls.includes(c)); }",
            "  return e.tagName === sel.toUpperCase();",
            "}",
            "let root;",
            "const document = {",
            "  createElement: (t) => new El(t),",
            "  getElementById: (id) => root.all().find(e => e.id === id) || null,",
            "  querySelector: (s) => root.all().find(e => matches(e, s)) || null,",
            "  querySelectorAll: (s) => root.all().filter(e => matches(e, s))",
            "};",
            "function resetDom() {",
            "  root = new El('body');",
            "  const nav = root.appendChild(new El('nav')); nav.className = 'nav-tabs';",
            "  const core = nav.appendChild(new El('button')); core.className = 'nav-tab active'; core.textContent = 'Regions';",
            "  const ws = root.appendChild(new El('main')); ws.id = 'workspace';",
            "  const p = ws.appendChild(new El('section')); p.id = 'panel-regions'; p.className = 'panel-view active';",
            "}",
            "const configs = {};",
            "const shippedConfigs = {};",
            "const appliedConfigs = {};",
            "let currentConfigFile = 'config.yml';",
            "const cfgEditor = null;",
            "const counters = { stages: 0 };",
            "function updateStagingDiff() { counters.stages++; }",
            "function resizeCanvas() {}",
            "function setTimeout(fn) { return 0; }",
            "function renderMarkdownToHtml(md) { return 'MD:' + md; }",
            "function confirm() { return true; }",
            "let connected = true;",
            "function channelConnected() { return connected; }",
            "const ns = {};",
            "const sent = [];",
            "let proto = 2;",
            "let serverExt = [];",
            "const statusFns = [];",
            "const EditorChannelClient = {",
            "  onStatus: (fn) => statusFns.push(fn),",
            "  onNamespace: (id, fn) => { ns[id] = fn; },",
            "  protocol: () => proto,",
            "  extensions: () => serverExt,",
            "  send: (type, body) => { sent.push({ type, body }); return true; }",
            "};",
            "function texts() { return root.all().map(e => e._text).filter(Boolean); }",
            "function byTag(tag) { return root.all().filter(e => e.tagName === tag); }",
            "function reset() {",
            "  resetDom(); connected = true; proto = 2; serverExt = []; sent.length = 0; counters.stages = 0;",
            "  for (const o of [configs, shippedConfigs, appliedConfigs, ns]) for (const k of Object.keys(o)) delete o[k];",
            "}",
            "");

    /** A full extension: one tab with all six widget kinds and one unknown kind; plus an error extension. */
    private static final String DESCRIPTORS = String.join("\n",
            "[{ id: 'demo', version: 1, name: 'Demo', inbound: ['run', 'refresh'], files: ['addons/Demo/actions.yml'],",
            "   snapshot: { rows: [{ name: 'spawn', uses: 3, meta: { deep: 1 } }], info: { zones: 2 } },",
            "   tabs: [{ id: 'main', title: 'Actions', icon: '⚡', widgets: [",
            "     { kind: 'markdown', props: { text: 'Hello **addon**' } },",
            "     { kind: 'status', props: { label: 'Health', source: 'state:/health', levels: { ok: 'green', bad: 'red' } } },",
            "     { kind: 'keyValue', props: { source: 'snapshot:/info', labels: { zones: 'Trigger zones' } } },",
            "     { kind: 'table', props: { source: 'snapshot:/rows', columns: [{ key: 'name', title: 'Name' }, { key: 'uses', title: 'Uses', format: 'number' }],",
            "       rowAction: { label: 'Run', send: 'run' } } },",
            "     { kind: 'button', props: { label: 'Refresh', send: 'refresh', body: { all: true } } },",
            "     { kind: 'button', props: { label: 'Sneaky', send: 'wipe' } },",
            "     { kind: 'form', props: { file: 'addons/Demo/actions.yml', fields: [",
            "       { path: 'settings.cooldown', title: 'Cooldown', kind: 'integer' },",
            "       { path: 'settings.mode', title: 'Mode', kind: 'enum', options: ['fast', 'safe mode'] }] } },",
            "     { kind: 'form', props: { file: 'config.yml', fields: [{ path: 'x', kind: 'string' }] } },",
            "     { kind: 'mapOverlay', props: {} }] }] },",
            " { id: 'broken', version: 2, name: 'Broken', error: 'snapshot-cap' }]",
            "");

    private static Context context;

    private static String page() throws IOException {
        Path page = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(page)) page = Paths.get("..", "docs", "editor", "index.html");
        assertTrue(Files.exists(page), "docs/editor/index.html must exist at " + page.toAbsolutePath());
        return Files.readString(page, StandardCharsets.UTF_8);
    }

    private static String braced(String html, int start, int from, String what) {
        int open = html.indexOf('{', from);
        int depth = 0;
        for (int i = open; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return html.substring(start, i + 1);
        }
        throw new AssertionError("unbalanced braces in " + what);
    }

    private static String functionSource(String html, String name) {
        int start = html.indexOf("function " + name + "(");
        assertTrue(start >= 0, "page function missing: " + name);
        assertEquals(-1, html.indexOf("function " + name + "(", start + 1), "page function declared once: " + name);
        return braced(html, start, html.indexOf(')', start), name);
    }

    private static String constSource(String html, String name) {
        int start = html.indexOf("const " + name + " = ");
        assertTrue(start >= 0, "page constant missing: " + name);
        String line = html.substring(start, html.indexOf('\n', start));
        if (line.trim().endsWith(";")) return line;
        String body = braced(html, start, start, name);
        return html.substring(start, html.indexOf(';', start + body.length()) + 1);
    }

    private static String moduleSource(String html) {
        int start = html.indexOf("var EditorExtensionTabs = (() => {");
        assertTrue(start >= 0, "EditorExtensionTabs module missing");
        int end = html.indexOf("\n})();", start);
        assertTrue(end > start, "EditorExtensionTabs module not closed");
        return html.substring(start, end + 6);
    }

    @BeforeAll
    static void startEngine() throws IOException {
        String html = page();
        StringBuilder src = new StringBuilder(STUBS);
        for (String c : PAGE_CONSTS) src.append(constSource(html, c)).append('\n');
        for (String fn : PAGE_FUNCTIONS) src.append(functionSource(html, fn)).append('\n');
        src.append(moduleSource(html)).append('\n');
        context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
        context.eval("js", src.toString());
    }

    @AfterAll
    static void stopEngine() {
        if (context != null) context.close();
    }

    @BeforeEach
    void resetPage() {
        js("reset()");
    }

    private static Value js(String code) {
        return context.eval("js", code);
    }

    private static void install() {
        js("EditorExtensionTabs.install(" + DESCRIPTORS + ")");
    }

    @Test
    @DisplayName("yamlSetScalar edits in place, keeps comments, creates missing parents and never replaces a block")
    void yamlStaging() {
        js("var y = 'a: 1\\nsettings:\\n  cooldown: 5 # seconds\\n  other: x\\n'");
        assertEquals("a: 1\nsettings:\n  cooldown: 9 # seconds\n  other: x\n",
                js("yamlSetScalar(y, ['settings', 'cooldown'], '9')").asString());
        assertEquals("5", js("yamlScalarAt(y, 'settings.cooldown')").asString());
        assertTrue(js("yamlScalarAt(y, 'settings') === undefined").asBoolean(), "a block parent is not a scalar");
        assertEquals("a: 1\nsettings:\n  cooldown: 5 # seconds\n  other: x\n  mode: fast\n",
                js("yamlSetScalar(y, ['settings', 'mode'], 'fast')").asString());
        assertEquals("a: 1\nsettings:\n  cooldown: 5 # seconds\n  other: x\nzones:\n  spawn:\n    radius: 4\n",
                js("yamlSetScalar(y, ['zones', 'spawn', 'radius'], '4')").asString());
        assertEquals("k: v\n", js("yamlSetScalar('', ['k'], 'v')").asString());
        assertTrue(js("(() => { try { yamlSetScalar(y, ['settings'], '1'); return false; } catch (e) { return true; } })()").asBoolean(),
                "a block is never overwritten by a scalar");
        assertTrue(js("(() => { try { yamlSetScalar(y, ['a', 'b'], '1'); return false; } catch (e) { return true; } })()").asBoolean(),
                "a scalar never becomes a parent");
    }

    @Test
    @DisplayName("Extension tabs follow the core tabs, unknown kinds become placeholders and error descriptors show their reason")
    void tabsAndPlaceholders() {
        install();
        Value navTexts = js("document.querySelector('.nav-tabs').children.map(c => c.textContent)");
        assertEquals("Regions", navTexts.getArrayElement(0).asString(), "core tabs keep their indexes");
        assertEquals("⚡ Actions", navTexts.getArrayElement(1).asString());
        assertEquals("🧩 Broken", navTexts.getArrayElement(2).asString());
        assertNotNull(js("document.getElementById('ext-demo-main')").as(Object.class), "panel id ext-<id>-<tab>");
        assertTrue(js("texts().some(t => t.includes('(mapOverlay) needs a newer editor'))").asBoolean(), "unknown kind placeholder");
        assertTrue(js("texts().some(t => t.includes('over the session size limit'))").asBoolean(), "error descriptor shown in its tab");
        assertTrue(js("texts().some(t => t.includes('did not declare: config.yml'))").asBoolean(), "forms only edit declared files");
        assertTrue(js("texts().includes('MD:Hello **addon**')").asBoolean(), "markdown through the page renderer");

        js("switchPanel('ext-demo-main', document.querySelector('.nav-tabs').children[1])");
        install();
        assertEquals("panel-regions", js("document.querySelector('.panel-view.active').id").asString(),
                "reinstalling removes the open addon panel and returns to the core tab");
        assertEquals(3, js("document.querySelector('.nav-tabs').children.length").asInt(), "no duplicate tabs on a new snapshot");
    }

    @Test
    @DisplayName("Bound widgets read snapshot and state pointers; an <id>.state push refreshes them")
    void bindings() {
        install();
        assertTrue(js("texts().includes('● -')").asBoolean(), "status without state shows its empty value");
        assertTrue(js("texts().includes('Trigger zones') && texts().includes('2')").asBoolean(), "keyValue from snapshot with labels");
        assertTrue(js("texts().includes('spawn') && texts().includes('3')").asBoolean(), "table rows from snapshot");
        js("ns.demo({ type: 'demo.state', state: { health: 'ok' } })");
        assertTrue(js("texts().includes('● ok')").asBoolean());
        assertEquals("var(--green)", js("root.all().find(e => e._text === '● ok').style.color").asString(), "level colours by palette name");
        js("ns.demo({ type: 'demo.error', reason: 'failed' })");
        assertTrue(js("texts().some(t => t.includes('disabled for this session'))").asBoolean());
        assertEquals(1, js("EditorExtensionTabs.pointer({ 'a/b': { '~k': 1 } }, '/a~1b/~0k')").asInt(), "RFC 6901 escapes");
    }

    @Test
    @DisplayName("Buttons and row actions send only declared inbound types, over a protocol 2 link")
    void sends() {
        install();
        js("byTag('BUTTON').find(b => b._text === 'Refresh').onclick()");
        js("byTag('BUTTON').find(b => b._text === 'Run').onclick()");
        js("byTag('BUTTON').find(b => b._text === 'Sneaky').onclick()");
        assertEquals(2, js("sent.length").asInt(), "the undeclared 'wipe' is never sent");
        assertEquals("demo.refresh", js("sent[0].type").asString());
        assertTrue(js("sent[0].body.all === true").asBoolean());
        assertEquals("demo.run", js("sent[1].type").asString());
        assertEquals("spawn", js("sent[1].body.row.name").asString());
        assertTrue(js("sent[1].body.row.meta === undefined").asBoolean(), "row actions carry scalar cells only");

        js("proto = 1; byTag('BUTTON').find(b => b._text === 'Refresh').onclick()");
        js("proto = 2; connected = false; byTag('BUTTON').find(b => b._text === 'Refresh').onclick()");
        assertEquals(2, js("sent.length").asInt(), "nothing to a protocol 1 plugin or without a link");

        js("ns.demo({ type: 'demo.error', reason: 'failed' }); connected = true; byTag('BUTTON').find(b => b._text === 'Refresh').onclick()");
        assertEquals(2, js("sent.length").asInt(), "a failed extension sends nothing");
    }

    @Test
    @DisplayName("Form edits are validated by kind and staged into configs, so Hot-Apply sends the addon file")
    void formStaging() {
        js("shippedConfigs['addons/Demo/actions.yml'] = 'settings:\\n  cooldown: 5\\n'");
        install();
        js("var inputs = byTag('INPUT'); var cooldown = inputs[0];");
        assertEquals("5", js("cooldown.value").asString(), "current value read from the staged file");
        js("cooldown.value = 'abc'; cooldown.onchange()");
        assertTrue(js("configs['addons/Demo/actions.yml'] === undefined").asBoolean(), "an invalid integer is not staged");
        js("cooldown.value = '12'; cooldown.onchange()");
        assertEquals("settings:\n  cooldown: 12\n", js("configs['addons/Demo/actions.yml']").asString());
        js("var mode = byTag('SELECT')[0]; mode.value = 'safe mode'; mode.onchange()");
        assertEquals("settings:\n  cooldown: 12\n  mode: \"safe mode\"\n", js("configs['addons/Demo/actions.yml']").asString(),
                "enum and string values are quoted when needed");
        assertTrue(js("counters.stages >= 2").asBoolean(), "the staging diff is refreshed");
        assertTrue(js("Object.keys(hotApplyChangedFiles()).includes('addons/Demo/actions.yml')").asBoolean(),
                "the next Hot-Apply carries the addon file");
    }

    @Test
    @DisplayName("A changed server extension set after reconnect is announced in the addon tabs")
    void serverSetChange() {
        install();
        js("serverExt = [{ id: 'demo', version: 1 }, { id: 'broken', version: 2 }]; statusFns.forEach(f => f('connected'))");
        assertFalse(js("texts().some(t => t.includes('addons changed'))").asBoolean(), "same set: no banner");
        js("serverExt = [{ id: 'demo', version: 2 }]; statusFns.forEach(f => f('connected'))");
        assertTrue(js("texts().some(t => t.includes('addons changed'))").asBoolean());
    }

    @Test
    @DisplayName("Page contract: no addon HTML or script in the page origin; markdown links https: only")
    void pageContract() throws IOException {
        String html = page();
        String module = moduleSource(html);
        assertEquals(1, module.split("innerHTML", -1).length - 1, "the only innerHTML is the sanitised markdown template");
        assertTrue(module.contains("tpl.innerHTML = renderMarkdownToHtml("), "markdown goes through the page renderer");
        assertTrue(module.contains("querySelectorAll('img, button, script, iframe, object, embed, style, form, input')"));
        assertTrue(module.contains("if (!/^https:\\/\\//i.test(a.getAttribute('href') || ''))"), "non-https links become text");
        assertTrue(module.contains("a.setAttribute('rel', 'noopener noreferrer')"));
        assertFalse(module.contains("eval("), "no addon code evaluated");
        assertFalse(module.contains("new Function"), "no addon code evaluated");
        assertTrue(module.contains("if (!ext.desc.inbound.includes(local))"), "sends gated by the declared inbound types");
        assertTrue(module.contains("if (!ext.desc.files.includes(file))"), "forms gated by the declared files");
        assertFalse(module.contains("send('apply'"), "addon tabs can stage but never apply");
        assertTrue(html.contains("if (EditorExtensionTabs) EditorExtensionTabs.install(payload.extensions);"), "installed from the snapshot");
    }
}
