package io.github.dailystruggle.bytecode;

import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.CausalChainSlice;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.CausalFinding;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.CausalGraphReport;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.ConfidenceTier;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.FindingKind;
import io.github.dailystruggle.bytecode.LlmConfigDocSynthesizer.HttpTransport;
import io.github.dailystruggle.bytecode.LlmConfigDocSynthesizer.LlmHookConfig;
import io.github.dailystruggle.bytecode.LlmConfigDocSynthesizer.Provider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subscribed LLM causal-chain auditor and AST-grounded fault explainer
 * ({@code leaffuzz-ADR-001}, {@code ADR-125}, {@code corpus-atlas-ADR-001}, {@code corpus-atlas-ADR-002}).
 *
 * <p>Provides two strictly bounded capabilities over {@link BytecodeCausalGraphScanner} slices:
 * <ol>
 *   <li><b>AST-Grounded Root-Cause Explanations for Proven Fuzz/Wiring Faults:</b> Given an
 *       already-proven Stage 2/3/4/5 fault (stack signature, minimized {@code ddmin} command
 *       sequence, or deterministic {@link CausalFinding}), queries the configured
 *       {@link Provider} ({@code GEMINI}, {@code OPENAI_COMPATIBLE}, {@code ANTHROPIC},
 *       {@code OLLAMA}) with the compact {@code Jminor} causal chain and verifies every cited
 *       {@code Class#method} symbol against {@link CausalGraphReport#closedWorldSymbols()} in
 *       {@code < 5 ms}. Never alters scorecard pass/fail verdicts.</li>
 *   <li><b>Opt-In Advisory Semantic Causal Audit:</b> Evaluates {@code Jminor} causal slices against
 *       declared command/config behavior to surface semantic/guard-polarity mismatches as
 *       {@link ConfidenceTier#ADVISORY} findings, rejecting any finding that cites a hallucinated
 *       symbol outside {@link CausalGraphReport#closedWorldSymbols()}.</li>
 * </ol>
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy ADR-126
 * @governedBy leaffuzz-ADR-001
 * @governedBy corpus-atlas-ADR-001
 * @governedBy corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001
 * @trace REQ-CFG-ATLAS-002
 * @trace REQ-SLEUTH-FUZZ-004
 * @trace S-004
 * @trace S-005
 */
public final class LlmCausalChainAuditor {

    private static final Logger LOGGER = Logger.getLogger(LlmCausalChainAuditor.class.getName());
    private static final Pattern CITED_SYMBOL_RE = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9_$]{1,64})#([a-zA-Z0-9_$<>]{1,64})\\b"
    );
    private static final Pattern JSON_STRING_FIELD_RE = Pattern.compile(
            "\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\""
    );
    private static final Pattern REFUSAL_OR_INJECTION_RE = Pattern.compile(
            "(?i)(?:ignore\\s++(?:all\\s++)?(?:previous|prior)\\s++instructions|<\\s*+(?:/\\s*+)?script|javascript:|as\\s++an\\s++ai\\s++language\\s++model)"
    );

    private static final Set<String> ALLOWED_JDK_OR_BUKKIT_SYMBOLS = Set.of(
            "System#currentTimeMillis",
            "System#nanoTime",
            "CompletableFuture#supplyAsync",
            "CompletableFuture#runAsync",
            "CompletableFuture#thenAccept",
            "CompletableFuture#whenComplete",
            "PluginManager#registerEvents",
            "Bukkit#getPluginManager",
            "Bukkit#getPlayer",
            "Bukkit#getScheduler",
            "BukkitScheduler#runTaskAsynchronously",
            "BukkitScheduler#runTaskLater",
            "Player#teleportAsync",
            "Player#teleport",
            "Player#openInventory",
            "Player#sendMessage",
            "World#getChunkAt",
            "World#getChunkAtAsync",
            "Supplier#get",
            "Runnable#run"
    );

    private final LlmHookConfig config;
    private final HttpTransport transport;

    public LlmCausalChainAuditor(LlmHookConfig config) {
        this(config, null);
    }

    public LlmCausalChainAuditor(LlmHookConfig config, HttpTransport customTransport) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = customTransport != null ? customTransport : LlmCausalChainAuditor::defaultHttpPost;
    }

    public LlmHookConfig config() {
        return config;
    }

    /**
     * Enriches deterministic {@link CausalFinding} records with AST-grounded LLM root-cause
     * explanations when {@link Provider} is not {@link Provider#OFF}.
     *
     * @param report      causal graph report containing slices and closed-world symbol set
     * @param maxFindings maximum number of findings to enrich
     * @return list of findings (with {@code llmExplanation} populated on grounded successes)
     */
    public List<CausalFinding> enrichFindingsWithExplanations(
            CausalGraphReport report,
            int maxFindings
    ) {
        Objects.requireNonNull(report, "report");
        if (config.provider() == Provider.OFF || report.findings().isEmpty() || maxFindings <= 0) {
            return report.findings();
        }
        List<CausalFinding> enriched = new ArrayList<>(report.findings().size());
        int count = 0;
        for (CausalFinding f : report.findings()) {
            if (count < maxFindings && f.llmExplanation().isEmpty()) {
                String explanation = explainCausalFinding(f, report);
                if (!explanation.isEmpty()) {
                    enriched.add(f.withLlmExplanation(explanation));
                    count++;
                    continue;
                }
            }
            enriched.add(f);
        }
        return List.copyOf(enriched);
    }

    /**
     * Generates a concise, AST-grounded root-cause explanation for a proven Stage 2/3 fuzz fault
     * (e.g. an uncaught exception or main-thread tick stall shrunk by {@code ddmin}).
     *
     * @param faultSignatureId   proven fault signature ID (e.g. {@code exc:NullPointerException@WildTask#lambda$0:74})
     * @param topPluginFrame     top attributed plugin stack frame ({@code Class#method:line})
     * @param minimalCommandSeq  {@code ddmin}-shrunk command sequence
     * @param followUpShape      reproducing lifecycle shape (e.g. {@code quit_lt1s})
     * @param report             causal graph report for the suspect JAR
     * @return AST-grounded explanation string, or {@code ""} if provider is {@code OFF} or symbol gate rejects output
     */
    public String explainProvenFuzzFault(
            String faultSignatureId,
            String topPluginFrame,
            List<String> minimalCommandSeq,
            String followUpShape,
            CausalGraphReport report
    ) {
        if (config.provider() == Provider.OFF || report == null) {
            return "";
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("TARGET_PLUGIN: ").append(report.displayName()).append('\n');
        prompt.append("PROVEN_RUNTIME_FAULT: ").append(faultSignatureId).append('\n');
        if (topPluginFrame != null && !topPluginFrame.isBlank()) {
            prompt.append("TOP_STACK_FRAME: ").append(topPluginFrame).append('\n');
        }
        if (minimalCommandSeq != null && !minimalCommandSeq.isEmpty()) {
            prompt.append("MINIMAL_REPRODUCER_COMMANDS: ").append(String.join(" ; ", minimalCommandSeq)).append('\n');
        }
        if (followUpShape != null && !followUpShape.isBlank()) {
            prompt.append("PLAYER_LIFECYCLE_SHAPE: ").append(followUpShape).append('\n');
        }
        prompt.append("\nEXTRACTED_AST_CAUSAL_SLICES:\n");
        int slicesAdded = 0;
        for (CausalChainSlice slice : report.entryChains()) {
            if (slicesAdded >= 4) {
                break;
            }
            prompt.append(slice.toJminorPromptBlock()).append('\n');
            slicesAdded++;
        }
        prompt.append("Return compact JSON: {\"explanation\": \"1-2 sentence root cause citing only Class#method symbols from EXTRACTED_AST_CAUSAL_SLICES\"}");

        try {
            String raw = invokeProvider(
                    "You explain proven Minecraft plugin fuzz crashes using only the supplied bytecode AST causal slices. "
                            + "Never invent Class#method names not present in the prompt. Return JSON {\"explanation\":\"...\"}.",
                    prompt.toString()
            );
            Map<String, String> fields = parseJsonFields(LlmConfigDocSynthesizer.stripThinkAndFences(raw));
            String explanation = fields.getOrDefault("explanation", "").trim();
            if (explanation.isEmpty() || !passesClosedWorldSymbolGate(explanation, report.closedWorldSymbols())) {
                return "";
            }
            return explanation;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "[LeafConfig] LLM fault explanation fallback for " + faultSignatureId + ": " + e.getMessage(), e);
            return "";
        }
    }

    /**
     * Runs an opt-in second-order semantic causal audit over the extracted {@link CausalChainSlice}s,
     * returning only findings that pass the closed-world AST symbol grounding gate.
     *
     * @param report    causal graph report
     * @param maxChains maximum number of entry chains to audit
     * @return list of {@link ConfidenceTier#ADVISORY} semantic findings
     */
    public List<CausalFinding> auditCausalChainsWithLlm(
            CausalGraphReport report,
            int maxChains
    ) {
        Objects.requireNonNull(report, "report");
        if (config.provider() == Provider.OFF || report.entryChains().isEmpty() || maxChains <= 0) {
            return List.of();
        }
        List<CausalFinding> advisoryFindings = new ArrayList<>();
        int audited = 0;
        int seq = report.findings().size() + 1;

        for (CausalChainSlice slice : report.entryChains()) {
            if (audited >= maxChains) {
                break;
            }
            audited++;
            String prompt = "TARGET: " + report.displayName() + "\n"
                    + slice.toJminorPromptBlock() + "\n"
                    + "Check if the causal chain contradicts its DECLARED behavior (e.g. inverted guard, missing sink, or broken continuation).\n"
                    + "If intact, return {\"status\":\"OK\"}.\n"
                    + "If broken, return {\"status\":\"BUG\",\"entrySymbol\":\""
                    + LlmConfigDocSynthesizer.stripPackagePrefixes(slice.entrySymbol())
                    + "\",\"declaredSurface\":\"...\",\"causalDefect\":\"...\",\"clearsWhen\":\"...\"} citing only symbols from the chain.";
            try {
                String raw = invokeProvider(
                        "You are a bytecode causal-chain verifier. Only report a BUG when the provided Jminor slice proves a contradiction. "
                                + "Every Class#method cited must exist in the prompt.",
                        prompt
                );
                Map<String, String> parsed = parseJsonFields(LlmConfigDocSynthesizer.stripThinkAndFences(raw));
                if (!"BUG".equalsIgnoreCase(parsed.getOrDefault("status", ""))) {
                    continue;
                }
                String declaredSurface = parsed.getOrDefault("declaredSurface", slice.declaredBehavior()).trim();
                String causalDefect = parsed.getOrDefault("causalDefect", "").trim();
                String clearsWhen = parsed.getOrDefault("clearsWhen", "").trim();
                String combinedText = declaredSurface + " " + causalDefect + " " + clearsWhen;
                if (causalDefect.isEmpty()
                        || !passesClosedWorldSymbolGate(combinedText, report.closedWorldSymbols())) {
                    continue;
                }
                advisoryFindings.add(new CausalFinding(
                        "CAUSAL-LLM-" + (seq++),
                        report.displayName(),
                        2,
                        FindingKind.CAUSAL_LLM_SEMANTIC_MISMATCH,
                        ConfidenceTier.ADVISORY,
                        55,
                        slice.entrySymbol(),
                        declaredSurface,
                        "[ADVISORY / LLM-HYPOTHESIS] " + causalDefect,
                        clearsWhen.isEmpty() ? ("Inspect " + slice.entrySymbol()) : clearsWhen,
                        slice.hopTrace(),
                        causalDefect
                ));
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "[LeafConfig] LLM causal chain audit fallback for " + slice.entrySymbol() + ": " + e.getMessage(), e);
            }
        }
        return List.copyOf(advisoryFindings);
    }

    /**
     * Closed-World AST Symbol Grounding Gate ({@code < 5 ms}): verifies that every
     * {@code Class#method} reference in {@code candidateText} exists in {@code closedWorldSymbols}
     * (or standard JDK/Bukkit sink interfaces), rejecting hallucinated symbols and prompt injection.
     *
     * @param candidateText      LLM-generated explanation or finding text
     * @param closedWorldSymbols valid symbols extracted from the artifact's bytecode and configs
     * @return {@code true} if grounded and safe; {@code false} if any cited symbol is hallucinated
     */
    public static boolean passesClosedWorldSymbolGate(
            String candidateText,
            Set<String> closedWorldSymbols
    ) {
        if (candidateText == null || candidateText.isBlank()) {
            return false;
        }
        if (REFUSAL_OR_INJECTION_RE.matcher(candidateText).find()) {
            return false;
        }
        Set<String> valid = closedWorldSymbols == null ? Set.of() : closedWorldSymbols;
        Matcher m = CITED_SYMBOL_RE.matcher(candidateText);
        while (m.find()) {
            String fullRef = m.group(1) + "#" + m.group(2);
            String clsOnly = m.group(1);
            if (ALLOWED_JDK_OR_BUKKIT_SYMBOLS.contains(fullRef)) {
                continue;
            }
            boolean matched = valid.contains(fullRef) || valid.contains(clsOnly);
            if (!matched) {
                for (String sym : valid) {
                    if (sym.endsWith("." + fullRef) || sym.endsWith("$" + fullRef) || sym.endsWith("#" + m.group(2))) {
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private String explainCausalFinding(CausalFinding finding, CausalGraphReport report) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("TARGET: ").append(report.displayName()).append('\n');
        prompt.append("FINDING_KIND: ").append(finding.kind().name()).append(" (Order ").append(finding.order()).append(")\n");
        prompt.append("DECLARED_SURFACE: ").append(finding.declaredSurface()).append('\n');
        prompt.append("CAUSAL_DEFECT: ").append(finding.causalDefect()).append('\n');
        prompt.append("CAUSAL_CHAIN: ").append(String.join(" -> ", finding.causalChainSteps())).append('\n');
        prompt.append("Return compact JSON: {\"explanation\": \"1-sentence AST-grounded explanation citing only symbols from the prompt\"}");
        try {
            String raw = invokeProvider(
                    "Explain this proven bytecode wiring/causal finding in 1 concise sentence using only symbols in the prompt. Return JSON {\"explanation\":\"...\"}.",
                    prompt.toString()
            );
            Map<String, String> parsed = parseJsonFields(LlmConfigDocSynthesizer.stripThinkAndFences(raw));
            String exp = parsed.getOrDefault("explanation", "").trim();
            if (!exp.isEmpty() && passesClosedWorldSymbolGate(exp, report.closedWorldSymbols())) {
                return exp;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "[LeafConfig] LLM causal finding explanation fallback for " + finding.findingId() + ": " + e.getMessage(), e);
        }
        return "";
    }

    private String invokeProvider(String systemPrompt, String userPrompt) throws IOException {
        return switch (config.provider()) {
            case GEMINI -> invokeGemini(systemPrompt + "\n\n" + userPrompt);
            case ANTHROPIC -> invokeAnthropic(systemPrompt, userPrompt);
            case OPENAI_COMPATIBLE, OLLAMA -> invokeOpenAiCompatible(systemPrompt, userPrompt);
            case OFF -> "";
        };
    }

    private String invokeOpenAiCompatible(String systemPrompt, String userPrompt) throws IOException {
        String body = "{\"model\":\"" + SchemaInferrer.escapeJson(config.model()) + "\","
                + "\"temperature\":0.1,"
                + "\"max_tokens\":450,"
                + "\"response_format\":{\"type\":\"json_object\"},"
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + SchemaInferrer.escapeJson(systemPrompt) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + SchemaInferrer.escapeJson(userPrompt) + "\"}"
                + "]}";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        String key = config.resolveApiKey();
        if (!key.isEmpty()) {
            headers.put("Authorization", "Bearer " + key);
        }
        String rawHttp = transport.postJson(config.endpoint(), headers, body, config.timeoutMs());
        return extractContentOrDirectJson(rawHttp, "OpenAI/Ollama");
    }

    private String invokeGemini(String prompt) throws IOException {
        String key = config.resolveApiKey();
        if (key.isEmpty()) {
            throw new IOException("Missing Gemini API key (" + config.apiKeyEnv() + ")");
        }
        String baseEndpoint = config.endpoint().replace("{model}", config.model());
        String fullUrl = baseEndpoint.contains("?")
                ? (baseEndpoint + "&key=" + key)
                : (baseEndpoint + "?key=" + key);
        String body = "{\"contents\":[{\"parts\":[{\"text\":\"" + SchemaInferrer.escapeJson(prompt) + "\"}]}],"
                + "\"generationConfig\":{\"temperature\":0.1,\"maxOutputTokens\":450,\"responseMimeType\":\"application/json\"}}";
        Map<String, String> headers = Map.of(
                "Content-Type", "application/json",
                "Accept", "application/json"
        );
        String rawHttp = transport.postJson(fullUrl, headers, body, config.timeoutMs());
        return extractContentOrDirectJson(rawHttp, "Gemini");
    }

    private String invokeAnthropic(String systemPrompt, String userPrompt) throws IOException {
        String key = config.resolveApiKey();
        if (key.isEmpty()) {
            throw new IOException("Missing Anthropic API key (" + config.apiKeyEnv() + ")");
        }
        String body = "{\"model\":\"" + SchemaInferrer.escapeJson(config.model()) + "\","
                + "\"max_tokens\":450,"
                + "\"temperature\":0.1,"
                + "\"system\":\"" + SchemaInferrer.escapeJson(systemPrompt) + "\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"" + SchemaInferrer.escapeJson(userPrompt) + "\"}]}";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("x-api-key", key);
        headers.put("anthropic-version", "2023-06-01");
        String rawHttp = transport.postJson(config.endpoint(), headers, body, config.timeoutMs());
        return extractContentOrDirectJson(rawHttp, "Anthropic");
    }

    private static String extractContentOrDirectJson(String rawHttpJson, String providerLabel) throws IOException {
        if (rawHttpJson == null || rawHttpJson.isBlank()) {
            throw new IOException("Empty HTTP response from " + providerLabel + " endpoint");
        }
        if (rawHttpJson.contains("\"explanation\"") || rawHttpJson.contains("\"status\"")) {
            try {
                return LlmConfigDocSynthesizer.extractUniversalLlmContent(rawHttpJson, providerLabel);
            } catch (IOException e) {
                LOGGER.log(Level.FINEST, "[LeafConfig] Using direct JSON payload from " + providerLabel, e);
                return rawHttpJson;
            }
        }
        return LlmConfigDocSynthesizer.extractUniversalLlmContent(rawHttpJson, providerLabel);
    }

    private static String defaultHttpPost(String url, Map<String, String> headers, String jsonBody, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(Math.min(5_000, timeoutMs));
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        if (headers != null) {
            for (var e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        byte[] outBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(outBytes);
        }
        int status = conn.getResponseCode();
        InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) {
            throw new IOException("HTTP " + status + " from " + url);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = is.read(buf)) != -1) {
            baos.write(buf, 0, r);
        }
        String resp = baos.toString(StandardCharsets.UTF_8);
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + ": " + (resp.length() > 180 ? resp.substring(0, 180) : resp));
        }
        return resp;
    }

    private static Map<String, String> parseJsonFields(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        Matcher m = JSON_STRING_FIELD_RE.matcher(json);
        while (m.find()) {
            String k = m.group(1);
            String v = m.group(2)
                    .replace("\\\"", "\"")
                    .replace("\\n", " ")
                    .replace("\\r", " ")
                    .replace("\\\\", "\\")
                    .trim();
            out.put(k, v);
        }
        return out;
    }
}
