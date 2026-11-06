package io.github.dailystruggle.bytecode;

import io.github.dailystruggle.bit.config.DirectiveSchemaParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic schema and spatial-gizmo synthesizer for untagged third-party and engine
 * configuration keys ({@code leafconfig-ADR-001} Section 2).
 *
 * <p>Combines explicit {@code yaml-api-ADR-003} comment directives ({@code @type}, {@code @range},
 * {@code @options}, {@code @unit}, {@code @source}) with value-literal heuristics, key-suffix
 * conventions, prose comment regex extraction, bytecode enum constant resolution, and spatial
 * coordinate gizmo classification for the 2D Server Map panel.
 *
 * @governedBy ADR-115
 * @governedBy ADR-126
 * @governedBy leafconfig-ADR-001
 * @governedBy yaml-api-ADR-003
 * @trace REQ-CFG-ATLAS-002
 */
public final class SchemaInferrer {

    private static final Pattern COMMENT_OPTIONS_RE = Pattern.compile(
            "(?i)(?:valid\\s+values|allowed\\s+values|options|modes|possible\\s+values|choices)\\s*[:=]\\s*\\[?([A-Za-z0-9_,\\s|/\"'-]{3,160})\\]?"
    );
    private static final Pattern COMMENT_RANGE_BETWEEN_RE = Pattern.compile(
            "(?i)(?:between|range(?:\\s+of)?|from)\\s+(-?\\d+(?:\\.\\d+)?)\\s+(?:and|to|\\.\\.|-)\\s+(-?\\d+(?:\\.\\d+)?)"
    );
    private static final Pattern COMMENT_PAREN_RANGE_RE = Pattern.compile(
            "\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*(?:\\.\\.|\\s+-\\s+)\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)"
    );
    private static final Pattern INTEGER_LITERAL_RE = Pattern.compile("^-?\\d+$");
    private static final Pattern FLOAT_LITERAL_RE = Pattern.compile("^-?\\d+\\.\\d+$");
    private static final Pattern DURATION_UNIT_LITERAL_RE = Pattern.compile("^(\\d+(?:\\.\\d+)?)(ms|t|ticks|s|sec|m|min|h|d)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTANCE_UNIT_LITERAL_RE = Pattern.compile("^(\\d+(?:\\.\\d+)?)(b|blocks|c|chunks|r|regions|km|mi)$", Pattern.CASE_INSENSITIVE);

    /** Provenance tier for documentation and schema metadata ({@code leafconfig-ADR-001} Section 3). */
    public enum DocProvenance {
        AUTHOR_COMMENT,
        OFFICIAL_DOCS,
        BYTECODE_INFERRED,
        LLM_SYNTHESIZED
    }

    /** Reload lifecycle tier for a configuration key ({@code leafconfig-ADR-001} Section 3). */
    public enum ReloadTier {
        HOT_RELOAD,
        PLUGIN_RELOAD,
        SERVER_RESTART
    }

    /** Spatial coordinate gizmo role for rendering arbitrary plugin config keys on the 2D Map panel. */
    public enum SpatialGizmoKind {
        NONE,
        CENTER_X,
        CENTER_Z,
        RADIUS,
        INNER_RADIUS,
        MIN_X,
        MAX_X,
        MIN_Z,
        MAX_Z,
        SPAWN_COORDINATE,
        POLYGON_VERTICES,
        WORLD_REFERENCE
    }

    /** Confidence classification tier for documented vs. AST-investigated configuration keys. */
    public enum ConfidenceTier {
        VERIFIED_DOCS,
        HIGH_AST,
        MEDIUM_AST,
        LOW_PENDING_REVAL
    }

    /**
     * Multi-pass AST/bytecode investigatory documentation result for a configuration key.
     *
     * <p>When official or author documentation is absent, {@link SchemaInferrer} and
     * {@link ServerConfigAtlas} synthesize an investigatory dossier across up to three progressive
     * passes (Pass 1: surface/constant-pool, Pass 2: method-body opcode &amp; getter call-site
     * slicing, Pass 3: cross-method field-flow &amp; subsystem call-graph correlation), storing
     * intermediate results and re-evaluating low-confidence entries over time.
     *
     * @param investigationPass         completed AST investigation depth ({@code 1}, {@code 2}, or {@code 3})
     * @param confidenceScore           calibrated confidence in {@code [0.0, 1.0]}
     * @param confidenceTier            discrete confidence tier badge
     * @param confidenceHistory         chronological confidence scores across completed passes
     * @param synthesizedSummary        human-readable documentation (player-first prose + AST context)
     * @param playerSummary             plain-language explanation of what this setting does in player/gameplay terms
     * @param whatChanges               concrete bullet points explaining what changes in-game when the value is raised, lowered, or switched
     * @param evidenceChain             ordered list of AST/bytecode evidence signals and their confidence weights
     * @param methodCallSites           formatted {@code Class#method -> getter(fallback)} call-site descriptors
     * @param fieldFlows                formatted {@code field -> downstream Class#method} flow descriptors
     * @param subsystemTags             inferred runtime subsystem API tags (e.g. {@code Combat/DamagePipeline})
     * @param needsFurtherInvestigation whether this entry should be queued for deeper AST re-evaluation
     */
    public record InvestigatoryDocResult(
            int investigationPass,
            double confidenceScore,
            ConfidenceTier confidenceTier,
            List<Double> confidenceHistory,
            String synthesizedSummary,
            String playerSummary,
            List<String> whatChanges,
            List<String> evidenceChain,
            List<String> methodCallSites,
            List<String> fieldFlows,
            List<String> subsystemTags,
            boolean needsFurtherInvestigation
    ) {
        public InvestigatoryDocResult(
                int investigationPass,
                double confidenceScore,
                ConfidenceTier confidenceTier,
                List<Double> confidenceHistory,
                String synthesizedSummary,
                List<String> evidenceChain,
                List<String> methodCallSites,
                List<String> fieldFlows,
                List<String> subsystemTags,
                boolean needsFurtherInvestigation
        ) {
            this(
                    investigationPass,
                    confidenceScore,
                    confidenceTier,
                    confidenceHistory,
                    synthesizedSummary,
                    synthesizedSummary,
                    List.of(),
                    evidenceChain,
                    methodCallSites,
                    fieldFlows,
                    subsystemTags,
                    needsFurtherInvestigation
            );
        }

        public InvestigatoryDocResult {
            investigationPass = Math.max(1, Math.min(3, investigationPass));
            confidenceScore = Math.max(0.0, Math.min(1.0, Math.round(confidenceScore * 100.0) / 100.0));
            confidenceTier = confidenceTier == null ? classifyConfidenceTier(confidenceScore, false) : confidenceTier;
            confidenceHistory = confidenceHistory == null ? List.of(confidenceScore) : List.copyOf(confidenceHistory);
            synthesizedSummary = synthesizedSummary == null ? "" : synthesizedSummary;
            playerSummary = (playerSummary == null || playerSummary.isBlank()) ? synthesizedSummary : playerSummary;
            whatChanges = whatChanges == null ? List.of() : List.copyOf(whatChanges);
            evidenceChain = evidenceChain == null ? List.of() : List.copyOf(evidenceChain);
            methodCallSites = methodCallSites == null ? List.of() : List.copyOf(methodCallSites);
            fieldFlows = fieldFlows == null ? List.of() : List.copyOf(fieldFlows);
            subsystemTags = subsystemTags == null ? List.of() : List.copyOf(subsystemTags);
        }
    }

    public static ConfidenceTier classifyConfidenceTier(double score, boolean hasAuthorOrOfficialDocs) {
        if (hasAuthorOrOfficialDocs && score >= 0.85) {
            return ConfidenceTier.VERIFIED_DOCS;
        }
        if (score >= 0.78) {
            return ConfidenceTier.HIGH_AST;
        }
        if (score >= 0.60) {
            return ConfidenceTier.MEDIUM_AST;
        }
        return ConfidenceTier.LOW_PENDING_REVAL;
    }

    /**
     * Synthesized schema, provenance, and spatial gizmo metadata for a single configuration key.
     *
     * @param inferredType   canonical type ({@code boolean}, {@code integer}, {@code double}, {@code duration}, {@code distance}, {@code enum}, {@code reference}, {@code list}, {@code string})
     * @param unit           inferred or declared unit ({@code ticks}, {@code ms}, {@code s}, {@code blocks}, {@code chunks}, or {@code null})
     * @param rangeMin       minimum numeric bound, or {@code null}
     * @param rangeMax       maximum numeric bound, or {@code null}
     * @param options        valid enum/choice strings
     * @param referenceSource external entity binding source (e.g. {@code server:worlds}, {@code minecraft:material}), or {@code null}
     * @param reloadTier     reload lifecycle classification
     * @param provenance     documentation/schema provenance badge
     * @param spatialGizmo   spatial map gizmo role if this key controls world coordinates or radii
     */
    public record InferredSchema(
            String inferredType,
            String unit,
            Double rangeMin,
            Double rangeMax,
            List<String> options,
            String referenceSource,
            ReloadTier reloadTier,
            DocProvenance provenance,
            SpatialGizmoKind spatialGizmo
    ) {
        public InferredSchema {
            Objects.requireNonNull(inferredType, "inferredType");
            options = options == null ? List.of() : List.copyOf(options);
            reloadTier = reloadTier == null ? ReloadTier.PLUGIN_RELOAD : reloadTier;
            provenance = provenance == null ? DocProvenance.BYTECODE_INFERRED : provenance;
            spatialGizmo = spatialGizmo == null ? SpatialGizmoKind.NONE : spatialGizmo;
        }

        /** Serializes this schema into a compact JSON object string for `ast_config_nodes.directives_json`. */
        public String toDirectivesJson() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("{\"type\":\"").append(escapeJson(inferredType)).append("\"");
            if (unit != null && !unit.isEmpty()) {
                sb.append(",\"unit\":\"").append(escapeJson(unit)).append("\"");
            }
            if (rangeMin != null || rangeMax != null) {
                sb.append(",\"range\":[")
                        .append(rangeMin == null ? "null" : formatNumber(rangeMin))
                        .append(",")
                        .append(rangeMax == null ? "null" : formatNumber(rangeMax))
                        .append("]");
            }
            if (!options.isEmpty()) {
                sb.append(",\"options\":[");
                for (int i = 0; i < options.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escapeJson(options.get(i))).append("\"");
                }
                sb.append("]");
            }
            if (referenceSource != null && !referenceSource.isEmpty()) {
                sb.append(",\"source\":\"").append(escapeJson(referenceSource)).append("\"");
            }
            if (spatialGizmo != SpatialGizmoKind.NONE) {
                sb.append(",\"spatialGizmo\":\"").append(spatialGizmo.name()).append("\"");
            }
            sb.append("}");
            return sb.toString();
        }
    }

    private SchemaInferrer() {
    }

    /**
     * Infers a structured {@link InferredSchema} for a configuration key from its explicit
     * directives, key path, raw scalar/list value, comment prose, and bytecode enum constants.
     *
     * @param keyPath         dotted YAML/TOML key path (e.g. {@code performance.queue-size})
     * @param rawValue        default or live value object (may be {@code null})
     * @param commentLines    contiguous `#` comment lines above the key (may be empty)
     * @param bytecodeOptions enum constants discovered via Plane 1 bytecode scanning (may be empty)
     * @param hasOfficialDocs whether external/official markdown prose matched this key
     * @return deterministic inferred schema
     */
    public static InferredSchema infer(
            String keyPath,
            Object rawValue,
            List<String> commentLines,
            List<String> bytecodeOptions,
            boolean hasOfficialDocs
    ) {
        Objects.requireNonNull(keyPath, "keyPath");
        List<String> safeComments = commentLines == null ? List.of() : commentLines;
        DirectiveSchemaParser explicit = DirectiveSchemaParser.parse(safeComments);

        String rawLeaf = extractLeaf(keyPath);
        String leaf = normalizeCamelToHyphen(rawLeaf);
        String lowerPath = normalizeCamelToHyphen(keyPath);
        String joinedComment = String.join("\n", safeComments);

        // 1. Provenance classification
        DocProvenance provenance;
        if (hasNonDirectiveProse(safeComments) || explicit.hasChecks()) {
            provenance = hasOfficialDocs ? DocProvenance.OFFICIAL_DOCS : DocProvenance.AUTHOR_COMMENT;
        } else if (hasOfficialDocs) {
            provenance = DocProvenance.OFFICIAL_DOCS;
        } else {
            provenance = DocProvenance.BYTECODE_INFERRED;
        }

        String valStr = rawValue == null ? "" : String.valueOf(rawValue).trim();
        boolean isNumericLiteral = rawValue instanceof Number
                || INTEGER_LITERAL_RE.matcher(valStr).matches()
                || FLOAT_LITERAL_RE.matcher(valStr).matches();
        boolean isBooleanLiteral = rawValue instanceof Boolean
                || "true".equalsIgnoreCase(valStr)
                || "false".equalsIgnoreCase(valStr);

        // 2. Options resolution: explicit @options > comment regex > bytecode enum constants > built-in engine enums (only for non-numeric/non-boolean values)
        Set<String> resolvedOptions = new LinkedHashSet<>(explicit.options());
        if (resolvedOptions.isEmpty() && !joinedComment.isEmpty()) {
            resolvedOptions.addAll(extractCommentOptions(joinedComment));
        }
        if (resolvedOptions.isEmpty() && !isNumericLiteral && !isBooleanLiteral
                && bytecodeOptions != null && !bytecodeOptions.isEmpty()) {
            resolvedOptions.addAll(bytecodeOptions);
        }
        if (resolvedOptions.isEmpty() && !isNumericLiteral && !isBooleanLiteral) {
            resolvedOptions.addAll(resolveBuiltinEngineEnumOptions(keyPath, rawValue));
        }

        // 3. Range resolution: explicit @range > comment regex > key-suffix non-negative floor
        Double rangeMin = explicit.rangeMin();
        Double rangeMax = explicit.rangeMax();
        if (rangeMin == null && rangeMax == null && !joinedComment.isEmpty()) {
            double[] commentRange = extractCommentRange(joinedComment);
            if (commentRange != null) {
                rangeMin = commentRange[0];
                rangeMax = commentRange[1];
            }
        }

        // 4. Type, unit, and reference source inference
        String type = explicit.type();
        String unit = explicit.unit();
        String source = explicit.source();

        if (type == null || type.isEmpty()) {
            if (rawValue instanceof Boolean || "true".equalsIgnoreCase(valStr) || "false".equalsIgnoreCase(valStr)) {
                type = "boolean";
            } else if (rawValue instanceof List<?>) {
                type = "list";
            } else if (!resolvedOptions.isEmpty()) {
                type = "enum";
            } else if (matchesWorldReference(leaf, lowerPath)) {
                type = "reference";
                if (source == null) source = "server:worlds";
            } else if (matchesMinecraftRegistry(leaf)) {
                type = "enum";
                if (source == null) source = "minecraft:" + normalizeRegistryName(leaf);
            } else if (isDurationSuffix(leaf)) {
                type = "integer";
                if (unit == null) unit = inferDurationUnit(leaf);
                if (rangeMin == null) rangeMin = "-1".equals(valStr) ? -1.0 : 0.0;
            } else if (isCountOrDistanceSuffix(leaf)) {
                type = "integer";
                if (unit == null) unit = inferDistanceUnit(leaf);
                if (rangeMin == null && !isCoordinateAxis(leaf)) rangeMin = "-1".equals(valStr) ? -1.0 : 0.0;
            } else if (isRatioOrChanceSuffix(leaf)) {
                type = "double";
                if (rangeMin == null && (leaf.contains("chance") || leaf.contains("percent") || leaf.contains("probability"))) {
                    rangeMin = 0.0;
                    if (rangeMax == null) {
                        rangeMax = leaf.contains("percent") ? 100.0 : 1.0;
                    }
                }
            } else {
                Matcher durMatcher = DURATION_UNIT_LITERAL_RE.matcher(valStr);
                Matcher distMatcher = DISTANCE_UNIT_LITERAL_RE.matcher(valStr);
                if (durMatcher.matches()) {
                    type = "duration";
                    if (unit == null) unit = durMatcher.group(2).toLowerCase(Locale.ROOT);
                    if (rangeMin == null) rangeMin = 0.0;
                } else if (distMatcher.matches()) {
                    type = "distance";
                    if (unit == null) unit = distMatcher.group(2).toLowerCase(Locale.ROOT);
                    if (rangeMin == null) rangeMin = 0.0;
                } else if (rawValue instanceof Integer || rawValue instanceof Long || INTEGER_LITERAL_RE.matcher(valStr).matches()) {
                    type = "integer";
                } else if (rawValue instanceof Number || FLOAT_LITERAL_RE.matcher(valStr).matches()) {
                    type = "double";
                } else {
                    type = "string";
                }
            }
        }

        // 5. Reload tier heuristic
        ReloadTier reloadTier = classifyReloadTier(lowerPath, joinedComment);

        // 6. Spatial coordinate gizmo classification for the 2D Map panel
        SpatialGizmoKind gizmoKind = classifySpatialGizmo(leaf, lowerPath, type);

        return new InferredSchema(
                type,
                unit,
                rangeMin,
                rangeMax,
                new ArrayList<>(resolvedOptions),
                source,
                reloadTier,
                provenance,
                gizmoKind
        );
    }

    /**
     * Synthesizes or progressively re-evaluates an {@link InvestigatoryDocResult} for a configuration key.
     *
     * <p>If authoritative documentation ({@link DocProvenance#OFFICIAL_DOCS} or
     * {@link DocProvenance#AUTHOR_COMMENT}) is already present, returns a {@link ConfidenceTier#VERIFIED_DOCS}
     * record immediately without scheduling AST re-evaluation. Otherwise executes the requested
     * AST investigation pass ({@code 1..3}) and appends the updated confidence score to
     * {@link InvestigatoryDocResult#confidenceHistory()}.
     */
    public static InvestigatoryDocResult synthesizeInvestigatoryDoc(
            String artifactDisplayName,
            String filePath,
            String keyPath,
            String liveValueStr,
            String commentText,
            InferredSchema schema,
            List<String> readByClasses,
            List<BytecodeConfigScanner.KeyCallSite> callSites,
            int targetPass,
            InvestigatoryDocResult previousResult
    ) {
        return synthesizeInvestigatoryDoc(
                artifactDisplayName,
                filePath,
                keyPath,
                liveValueStr,
                null,
                commentText,
                schema,
                readByClasses,
                callSites,
                targetPass,
                previousResult
        );
    }

    /**
     * Synthesizes or progressively re-evaluates an {@link InvestigatoryDocResult} including
     * plain-language player-terms behavior and value-adjustment impact.
     */
    public static InvestigatoryDocResult synthesizeInvestigatoryDoc(
            String artifactDisplayName,
            String filePath,
            String keyPath,
            String liveValueStr,
            String jarDefaultStr,
            String commentText,
            InferredSchema schema,
            List<String> readByClasses,
            List<BytecodeConfigScanner.KeyCallSite> callSites,
            int targetPass,
            InvestigatoryDocResult previousResult
    ) {
        Objects.requireNonNull(keyPath, "keyPath");
        Objects.requireNonNull(schema, "schema");
        int pass = Math.max(1, Math.min(3, targetPass));
        List<String> safeClasses = readByClasses == null ? List.of() : readByClasses;
        List<BytecodeConfigScanner.KeyCallSite> safeSites = callSites == null ? List.of() : callSites;

        String cleanComment = stripCommentPrefixes(commentText);
        boolean hasExplicitDocs = !cleanComment.isEmpty()
                || schema.provenance() == DocProvenance.OFFICIAL_DOCS
                || schema.provenance() == DocProvenance.AUTHOR_COMMENT;

        if (hasExplicitDocs) {
            double docConf = schema.provenance() == DocProvenance.OFFICIAL_DOCS ? 0.98 : 0.92;
            if (schema.rangeMin() != null || !schema.options().isEmpty()) {
                docConf = Math.min(0.99, docConf + 0.03);
            }
            List<String> docEvidence = new ArrayList<>();
            docEvidence.add(schema.provenance() == DocProvenance.OFFICIAL_DOCS
                    ? "+98% [Verified] Authoritative Markdown / Knowledge Pack documentation matched"
                    : "+92% [Verified] Author YAML block comment & directive schema extracted");
            if (!safeClasses.isEmpty()) {
                docEvidence.add("+Corroborated by bytecode consumer: " + String.join(", ", safeClasses));
            }
            List<String> mSites = formatCallSites(safeSites);
            List<String> fFlows = formatFieldFlows(safeSites);
            List<String> subs = collectSubsystems(safeSites);
            String playerSummary = buildPlayerTermsSummary(
                    artifactDisplayName, filePath, keyPath, liveValueStr, jarDefaultStr, cleanComment,
                    schema, safeClasses, safeSites, subs, 3, true
            );
            List<String> whatChanges = buildWhatChangesBullets(
                    keyPath, liveValueStr, jarDefaultStr, cleanComment, schema, safeSites, subs, 3, true
            );
            return new InvestigatoryDocResult(
                    pass,
                    docConf,
                    ConfidenceTier.VERIFIED_DOCS,
                    List.of(docConf),
                    playerSummary,
                    playerSummary,
                    whatChanges,
                    docEvidence,
                    mSites,
                    fFlows,
                    subs,
                    false
            );
        }

        // Undocumented key: synthesize progressive AST/bytecode investigatory documentation
        double score = 0.38;
        List<String> evidence = new ArrayList<>();

        // Pass 1 signals: Key morphology, scalar literal, and constant-pool class references
        evidence.add("+38% [Pass 1] Key path morphology (`" + keyPath + "`) & scalar literal (`"
                + (liveValueStr == null ? "null" : liveValueStr) + "` -> `" + schema.inferredType() + "`)");
        if (schema.unit() != null && !schema.unit().isEmpty()) {
            score += 0.08;
            evidence.add("+8% [Pass 1] Unit suffix/domain convention inferred (`" + schema.unit() + "`)");
        }
        if (schema.spatialGizmo() != SpatialGizmoKind.NONE) {
            score += 0.08;
            evidence.add("+8% [Pass 1] 2D Spatial coordinate gizmo role (`" + schema.spatialGizmo().name() + "`)");
        }
        if (!safeClasses.isEmpty()) {
            score += 0.12;
            evidence.add("+12% [Pass 1] Constant-pool `READ_BY_CLASS` consumer: " + simpleClassList(safeClasses));
        }

        List<String> formattedSites = List.of();
        List<String> formattedFlows = List.of();
        List<String> subsystems = List.of();

        // Pass 2 signals: Method-body bytecode call-site slicing, typed getter, code fallback & unit scaling
        if (pass >= 2) {
            formattedSites = formatCallSites(safeSites);
            if (!formattedSites.isEmpty()) {
                score += 0.16;
                evidence.add("+16% [Pass 2] Method-body opcode call site: `" + String.join("`, `", formattedSites) + "`");
            } else if (!safeClasses.isEmpty()) {
                score += 0.08;
                evidence.add("+8% [Pass 2] Class-scoped config section accessor in `" + safeClasses.get(0) + "`");
            }
            for (BytecodeConfigScanner.KeyCallSite cs : safeSites) {
                if (cs.codeFallbackValue() != null) {
                    score += 0.05;
                    evidence.add("+5% [Pass 2] Bytecode 2-arg fallback literal in `" + simpleClass(cs.className())
                            + "#" + cs.methodName() + "`: `" + cs.codeFallbackValue() + "`");
                    break;
                }
            }
            for (BytecodeConfigScanner.KeyCallSite cs : safeSites) {
                if (cs.unitScalingHint() != null) {
                    score += 0.06;
                    evidence.add("+6% [Pass 2] Bytecode arithmetic scaling: `" + cs.unitScalingHint() + "`");
                    break;
                }
            }
            for (BytecodeConfigScanner.KeyCallSite cs : safeSites) {
                List<String> nonOptionGuards = cs.guardPredicates().stream()
                        .filter(g -> !g.startsWith("enum: ") && !g.startsWith("match == "))
                        .toList();
                if (!nonOptionGuards.isEmpty()) {
                    score += 0.05;
                    evidence.add("+5% [Pass 2] Bytecode guard/clamp predicate: `" + String.join(", ", nonOptionGuards) + "`");
                    break;
                }
            }
            if (!schema.options().isEmpty()) {
                score += 0.07;
                evidence.add("+7% [Pass 2] Bytecode `Enum.valueOf` constant set: `" + schema.options() + "`");
            }
        }

        // Pass 3 signals: Cross-method field-flow (`putfield -> getfield`) & runtime subsystem API call-graph
        if (pass >= 3) {
            formattedFlows = formatFieldFlows(safeSites);
            subsystems = collectSubsystems(safeSites);
            if (!formattedFlows.isEmpty()) {
                score += 0.11;
                evidence.add("+11% [Pass 3] Field data-flow (`putfield -> getfield`): `" + String.join("`, `", formattedFlows) + "`");
            }
            if (!subsystems.isEmpty()) {
                score += 0.09;
                evidence.add("+9% [Pass 3] Downstream runtime subsystem API footprint: `" + String.join("`, `", subsystems) + "`");
            }
            if (formattedFlows.isEmpty() && subsystems.isEmpty() && !safeClasses.isEmpty()) {
                score += 0.07;
                evidence.add("+7% [Pass 3] Closed-world JAR call-graph verified across `" + safeClasses.size() + "` class(es)");
            }
        }

        double clampedScore = Math.max(0.40, Math.min(0.93, Math.round(score * 100.0) / 100.0));
        List<Double> history = new ArrayList<>();
        if (previousResult != null && !previousResult.confidenceHistory().isEmpty()) {
            history.addAll(previousResult.confidenceHistory());
        }
        if (history.isEmpty() || Math.abs(history.get(history.size() - 1) - clampedScore) > 1e-4 || history.size() < pass) {
            history.add(clampedScore);
        }

        ConfidenceTier tier = classifyConfidenceTier(clampedScore, false);
        boolean needsMore = pass < 3 && clampedScore < 0.84;

        String playerSummary = buildPlayerTermsSummary(
                artifactDisplayName, filePath, keyPath, liveValueStr, jarDefaultStr, "",
                schema, safeClasses, safeSites, subsystems, pass, false
        );
        List<String> whatChanges = buildWhatChangesBullets(
                keyPath, liveValueStr, jarDefaultStr, "", schema, safeSites, subsystems, pass, false
        );
        String summary = buildInvestigatoryProse(
                playerSummary,
                safeSites,
                pass
        );

        return new InvestigatoryDocResult(
                pass,
                clampedScore,
                tier,
                history,
                summary,
                playerSummary,
                whatChanges,
                evidence,
                formattedSites,
                formattedFlows,
                subsystems,
                needsMore
        );
    }

    private static String buildInvestigatoryProse(
            String playerSummary,
            List<BytecodeConfigScanner.KeyCallSite> callSites,
            int pass
    ) {
        StringBuilder sb = new StringBuilder(playerSummary);
        if (pass >= 2) {
            for (BytecodeConfigScanner.KeyCallSite cs : callSites) {
                if (cs.unitScalingHint() != null && !playerSummary.contains(cs.unitScalingHint())) {
                    sb.append(" (Bytecode scaling: `").append(cs.unitScalingHint()).append("`.)");
                    break;
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * Synthesizes a plain-language, player-facing explanation of what the setting does in-game
     * and what its current value means in human units (seconds, %, blocks, items).
     *
     * <p>When {@code hasExplicitDocs} is {@code false} (undocumented key / {@code Provider.OFF}),
     * delegates to {@link #composeDeterministicAstClause} to compose a single natural sentence from
     * bytecode call-site method names, guard predicates/clamp bounds, {@code @unit} suffixes,
     * and camelCase/domain concept tokens.
     */
    private static String buildPlayerTermsSummary(
            String artifactDisplayName,
            String filePath,
            String keyPath,
            String liveValueStr,
            String jarDefaultStr,
            String cleanComment,
            InferredSchema schema,
            List<String> readByClasses,
            List<BytecodeConfigScanner.KeyCallSite> callSites,
            List<String> subsystems,
            int pass,
            boolean hasExplicitDocs
    ) {
        String kLow = normalizeCamelToHyphen(keyPath);
        String leaf = normalizeCamelToHyphen(extractLeaf(keyPath));
        String val = sanitizeDisplayLiteral((liveValueStr == null || liveValueStr.isBlank()) ? "default" : liveValueStr.trim());
        String defVal = sanitizeDisplayLiteral(resolveEffectiveDefault(jarDefaultStr, callSites, val));
        Double numVal = parseFiniteDouble(val);
        Double defNum = parseFiniteDouble(defVal);
        String humanVal = formatValueInPlayerTerms(leaf, val, numVal, schema);

        // If author/official prose exists, combine it with the concrete player-terms translation of the current value
        if (hasExplicitDocs && !cleanComment.isEmpty()) {
            String base = cleanComment.endsWith(".") ? cleanComment : (cleanComment + ".");
            if (base.toLowerCase(Locale.ROOT).startsWith("valid values:") && !schema.options().isEmpty()) {
                return describeEnumSettingInPlayerTerms(kLow, leaf, val, schema.options()) + " (" + base + ")";
            }
            StringBuilder sb = new StringBuilder(base);
            sb.append(" Currently set to ").append(humanVal);
            if (defNum != null && numVal != null && Math.abs(numVal - defNum) > 1e-6 && defNum > 0) {
                double ratio = numVal / defNum;
                if (isIntervalOrCooldown(leaf) && ratio < 1.0) {
                    sb.append(String.format(Locale.ROOT, " — runs %.1fx faster than the default (%s).", 1.0 / ratio, formatValueInPlayerTerms(leaf, defVal, defNum, schema)));
                } else if (isIntervalOrCooldown(leaf) && ratio > 1.0) {
                    sb.append(String.format(Locale.ROOT, " — waits %.1fx longer than the default (%s).", ratio, formatValueInPlayerTerms(leaf, defVal, defNum, schema)));
                } else {
                    sb.append(" (default: ").append(formatValueInPlayerTerms(leaf, defVal, defNum, schema)).append(").");
                }
            } else {
                sb.append(".");
            }
            return sb.toString();
        }

        return composeDeterministicAstClause(
                artifactDisplayName,
                filePath,
                keyPath,
                val,
                defVal,
                schema,
                readByClasses,
                callSites,
                subsystems,
                pass
        );
    }

    /**
     * Deterministic AST clause composer for undocumented configuration keys ({@code Provider.OFF}).
     *
     * <p>Translates bytecode call-site method names, comparison operators/clamp bounds, {@code @unit}
     * suffixes, arithmetic unit scaling, and camelCase/domain concept tokens into a natural
     * 1-sentence operator description with zero remote LLM calls and zero generic filler prose.
     */
    public static String composeDeterministicAstClause(
            String artifactDisplayName,
            String filePath,
            String keyPath,
            String liveValueStr,
            String jarDefaultStr,
            InferredSchema schema,
            List<String> readByClasses,
            List<BytecodeConfigScanner.KeyCallSite> callSites,
            List<String> subsystems,
            int pass
    ) {
        String rawLeaf = extractLeaf(keyPath);
        String kLow = normalizeCamelToHyphen(keyPath);
        String leaf = normalizeCamelToHyphen(rawLeaf);
        String val = sanitizeDisplayLiteral((liveValueStr == null || liveValueStr.isBlank()) ? "default" : liveValueStr.trim());
        String defVal = sanitizeDisplayLiteral(resolveEffectiveDefault(jarDefaultStr, callSites, val));
        Double numVal = parseFiniteDouble(val);
        Double defNum = parseFiniteDouble(defVal);

        // Infer effective unit if bytecode scaling reveals seconds -> ticks or seconds -> ms
        String scalingHint = firstScalingHint(callSites, pass);
        String humanVal = formatValueWithScaling(leaf, val, numVal, schema, scalingHint);

        // Collect AST call-site / downstream method, guard predicate, and bound clause
        String astTargetRef = resolveAstTargetMethodOrClass(readByClasses, callSites, pass);
        String triggerLead = describeDownstreamEventLead(rawLeaf, leaf, callSites, subsystems, pass);
        String effectTail = describeOperationalConsequenceTail(rawLeaf, kLow, leaf, callSites, subsystems, pass);
        String astMeta = buildAstBoundsAndGuardClause(leaf, val, defVal, numVal, defNum, schema, callSites, scalingHint, effectTail.isEmpty(), pass);

        // 1. Spatial coordinate / gizmo settings
        if (schema.spatialGizmo() != SpatialGizmoKind.NONE && !kLow.contains("hysteresis")) {
            return describeSpatialSettingInPlayerTerms(kLow, leaf, val, numVal, schema.spatialGizmo(), astTargetRef, astMeta);
        }

        // 2. Enum / mode / policy settings
        if ("enum".equals(schema.inferredType()) || !schema.options().isEmpty()
                || leaf.endsWith("-mode") || leaf.equals("mode") || leaf.equals("provider") || leaf.endsWith("-policy")) {
            String subject = humanizeConceptPhrase(kLow, leaf, "mode", "type", "policy");
            String optDetail = explainEnumConstantForPlayers(val, kLow);
            String cleanDetail = optDetail.endsWith(".") ? optDetail.substring(0, optDetail.length() - 1) : optDetail;
            String whereClause = !astTargetRef.isEmpty() ? (" in `" + astTargetRef + "`") : "";
            return "Selects `" + val + "` for " + subject + whereClause + astMeta
                    + " (" + decapitalize(cleanDetail) + ").";
        }

        // 3. Boolean gates
        if ("boolean".equals(schema.inferredType())) {
            boolean enabled = "true".equalsIgnoreCase(val);
            String subject = humanizeConceptPhrase(kLow, leaf, "enable", "enabled", "allow", "deny", "restore");
            String whereClause = !astTargetRef.isEmpty() ? (" in `" + astTargetRef + "`") : "";
            if (leaf.startsWith("deny-") || leaf.startsWith("block-") || leaf.startsWith("disable-") || leaf.startsWith("prevent-")) {
                String action = humanizeConceptPhrase(kLow, leaf, "deny", "block", "disable", "prevent");
                return (enabled ? "Blocks " : "Permits ") + action + " (`" + val + "`" + stripOuterParens(astMeta) + ")"
                        + whereClause + effectTail + ".";
            }
            if (leaf.startsWith("restore-")) {
                String target = humanizeConceptPhrase(kLow, leaf, "restore");
                return (enabled ? "Restores " : "Skips restoring ") + target + " (`" + val + "`" + stripOuterParens(astMeta) + ")"
                        + whereClause + effectTail + ".";
            }
            if (leaf.startsWith("require-")) {
                String req = humanizeConceptPhrase(kLow, leaf, "require");
                return (enabled ? "Requires " : "Bypasses requiring ") + req + " (`" + val + "`" + stripOuterParens(astMeta) + ")"
                        + whereClause + effectTail + ".";
            }
            if (leaf.startsWith("set-")) {
                String act = humanizeConceptPhrase(kLow, leaf, "set");
                return (enabled ? "Updates " : "Leaves unchanged ") + act + " (`" + val + "`" + stripOuterParens(astMeta) + ")"
                        + whereClause + effectTail + ".";
            }
            return (enabled ? "Enables " : "Disables ") + subject + " (`" + val + "`" + stripOuterParens(astMeta) + ")"
                    + whereClause + effectTail + ".";
        }

        // 4. Probability / chance / percentage thresholds
        if (leaf.contains("chance") || leaf.contains("probability") || leaf.contains("percent")) {
            String subject = humanizeConceptPhrase(kLow, leaf, "chance", "probability", "percent");
            String whereClause = !astTargetRef.isEmpty() ? (" in `" + astTargetRef + "`") : "";
            if (!triggerLead.isEmpty()) {
                return triggerLead + ", sets a " + humanVal + " trigger probability" + astMeta
                        + " for " + subject + effectTail + ".";
            }
            return "Sets a " + humanVal + " trigger probability" + astMeta + " for " + subject
                    + whereClause + effectTail + ".";
        }

        // 5. Proximity wake / sleep / cancel distance thresholds
        if (leaf.contains("radius") || leaf.contains("distance")) {
            String whereClause = !astTargetRef.isEmpty() ? (" in `" + astTargetRef + "`") : "";
            if (leaf.contains("wake")) {
                return "Sets the player proximity wake radius to " + humanVal + astMeta + whereClause
                        + (effectTail.isEmpty() ? ", waking virtualized chunks when a player enters the radius" : effectTail) + ".";
            }
            if (leaf.contains("sleep")) {
                return "Sets the outer player sleep boundary to " + humanVal + astMeta + whereClause
                        + (effectTail.isEmpty() ? ", keeping unpaused chunks live while any player remains inside" : effectTail) + ".";
            }
            if (leaf.contains("cancel")) {
                return (!triggerLead.isEmpty() ? triggerLead + ", sets " : "Sets ")
                        + "the movement cancellation threshold to " + humanVal + astMeta
                        + (triggerLead.isEmpty() ? whereClause : "")
                        + (effectTail.isEmpty() ? " before aborting an in-flight teleport" : effectTail) + ".";
            }
            String subject = humanizeConceptPhrase(kLow, leaf, "blocks", "chunks");
            return "Sets the " + subject + " boundary to " + humanVal + astMeta + whereClause + effectTail + ".";
        }

        // 6. Minimum stability / floor thresholds (e.g. minStableTicksBeforeUnload, mspt-floor)
        if (leaf.startsWith("min-") || leaf.endsWith("-floor") || leaf.contains("threshold")) {
            String subject = humanizeConceptPhrase(kLow, leaf, "min", "ticks", "seconds", "ms", "blocks");
            String whereClause = !astTargetRef.isEmpty() ? (" in `" + astTargetRef + "`") : "";
            String forClause = effectTail.isEmpty() ? (" for " + subject) : " of stable calibration";
            return (!triggerLead.isEmpty() ? triggerLead + ", requires " : "Requires ")
                    + "at least " + humanVal + forClause + astMeta
                    + (triggerLead.isEmpty() ? whereClause : "") + effectTail + ".";
        }

        // 7. Cooldown / interval / delay / hysteresis timers
        if (isIntervalOrCooldown(leaf) || leaf.contains("hysteresis")) {
            String subject = humanizeConceptPhrase(kLow, leaf, "ticks", "seconds", "sec", "ms", "millis", "cooldown", "interval", "delay", "hysteresis");
            String timerNoun = leaf.contains("hysteresis")
                    ? "hysteresis hold window"
                    : (leaf.contains("cooldown") ? "cooldown window" : (leaf.contains("delay") ? "warmup delay" : "cycle interval"));
            String whereClause = !astTargetRef.isEmpty() ? (" in `" + astTargetRef + "`") : "";
            String forClause = effectTail.isEmpty() ? (" for " + subject) : "";
            if (!triggerLead.isEmpty()) {
                return triggerLead + ", enforces a " + humanVal + " " + timerNoun + astMeta
                        + forClause + effectTail + ".";
            }
            return "Enforces a " + humanVal + " " + timerNoun + astMeta + forClause
                    + whereClause + effectTail + ".";
        }

        // 8. Duration / timeout / TTL / expiry windows
        if (leaf.contains("duration") || leaf.contains("timeout") || leaf.contains("ttl") || leaf.contains("expire") || leaf.contains("reset")) {
            String subject = humanizeConceptPhrase(kLow, leaf, "seconds", "sec", "ticks", "ms", "millis", "duration", "timeout", "ttl");
            String whereClause = !astTargetRef.isEmpty() ? (" in `" + astTargetRef + "`") : "";
            if (leaf.contains("timeout")) {
                return "Sets the " + subject + " request timeout to " + humanVal + astMeta + whereClause + effectTail + ".";
            }
            if (leaf.contains("ttl")) {
                String ttlSubject = humanizeConceptPhrase(kLow, leaf, "seconds", "sec", "ticks", "ms", "millis", "ttl", "session", "sessions");
                return "Keeps " + ttlSubject + " sessions active for " + humanVal + astMeta + whereClause + " before expiring.";
            }
            return (!triggerLead.isEmpty() ? triggerLead + ", keeps " : "Keeps ")
                    + subject + " active for " + humanVal + astMeta
                    + (triggerLead.isEmpty() ? whereClause : "")
                    + (effectTail.isEmpty() ? " before expiring" : effectTail) + ".";
        }

        // 9. Maximum caps / batch limits / quotas / character bounds
        if (leaf.contains("batch") || leaf.contains("amount") || leaf.contains("max")
                || leaf.contains("limit") || leaf.contains("cap") || leaf.contains("count")
                || leaf.contains("uses") || leaf.contains("chars") || leaf.contains("size")) {
            String subject = humanizeConceptPhrase(kLow, leaf, "max", "limit", "cap", "count", "per", "batch", "ticks", "chars");
            if (leaf.endsWith("-cap") && !subject.contains("capacity")) {
                subject = subject + " capacity";
            }
            String whereClause = !astTargetRef.isEmpty() ? (" in `" + astTargetRef + "`") : "";
            if (!triggerLead.isEmpty()) {
                String compactSubject = !effectTail.isEmpty()
                        ? humanizeConceptPhrase(leaf, leaf, "max", "limit", "cap", "count", "per", "batch", "ticks", "chars")
                        : subject;
                return triggerLead + ", caps " + compactSubject + " at " + humanVal + astMeta + effectTail + ".";
            }
            return "Caps " + subject + " at " + humanVal + astMeta + whereClause + effectTail + ".";
        }

        // 10. Multipliers / weights / scaling factors
        if (leaf.contains("multiplier") || leaf.contains("weight") || leaf.contains("factor") || leaf.contains("scale")) {
            String subject = humanizeConceptPhrase(kLow, leaf, "multiplier", "weight", "factor", "scale");
            String whereClause = !astTargetRef.isEmpty() ? (" in `" + astTargetRef + "`") : "";
            return "Scales " + subject + " by " + humanVal + astMeta + whereClause + effectTail + ".";
        }

        // 11. General AST-grounded fallback clause
        String subject = humanizeConceptPhrase(kLow, leaf);
        String whereClause = !astTargetRef.isEmpty() ? (" in `" + astTargetRef + "`") : "";
        return "Configures " + subject + " to " + humanVal + astMeta + whereClause + effectTail + ".";
    }

    /**
     * Synthesizes concrete bullet points explaining what changes for players and server performance
     * when the operator raises, lowers, toggles, or switches this setting.
     */
    public static List<String> buildWhatChangesBullets(
            String keyPath,
            String liveValueStr,
            String jarDefaultStr,
            String cleanComment,
            InferredSchema schema,
            List<BytecodeConfigScanner.KeyCallSite> callSites,
            List<String> subsystems,
            int pass,
            boolean hasExplicitDocs
    ) {
        String kLow = normalizeCamelToHyphen(keyPath);
        String leaf = normalizeCamelToHyphen(extractLeaf(keyPath));
        String val = sanitizeDisplayLiteral((liveValueStr == null || liveValueStr.isBlank()) ? "default" : liveValueStr.trim());
        String defVal = sanitizeDisplayLiteral(resolveEffectiveDefault(jarDefaultStr, callSites, val));
        Double numVal = parseFiniteDouble(val);
        Double defNum = parseFiniteDouble(defVal);
        String humanVal = formatValueInPlayerTerms(leaf, val, numVal, schema);
        String humanDef = formatValueInPlayerTerms(leaf, defVal, defNum, schema);

        List<String> bullets = new ArrayList<>();

        // 1. Live vs Default comparison if modified, or clear translation of current active value
        String triggerPhrase = describeDownstreamGameplayTrigger(callSites, subsystems, pass);
        if (numVal != null && defNum != null && Math.abs(numVal - defNum) > 1e-6 && defNum > 0) {
            double ratio = numVal / defNum;
            if (isIntervalOrCooldown(leaf) && ratio < 1.0) {
                bullets.add(String.format(Locale.ROOT,
                        "Modified from default: Currently %s vs. default %s — happens %.1fx more frequently in-game.",
                        humanVal, humanDef, 1.0 / ratio));
            } else if (isIntervalOrCooldown(leaf) && ratio > 1.0) {
                bullets.add(String.format(Locale.ROOT,
                        "Modified from default: Currently %s vs. default %s — players/systems wait %.1fx longer between cycles.",
                        humanVal, humanDef, ratio));
            } else {
                bullets.add("Modified from default: Currently " + humanVal + " (JAR default is " + humanDef + ").");
            }
        } else if (!hasExplicitDocs && pass == 1) {
            bullets.add("Current active value: " + humanVal + " (initial estimate — verifying default & gameplay trigger in background).");
        } else if (!hasExplicitDocs && pass >= 3 && !triggerPhrase.isEmpty()) {
            bullets.add("Current gameplay behavior: " + triggerPhrase + ", uses " + humanVal
                    + (defVal != null && !defVal.isEmpty() && !defVal.equals("default") ? " (matches default " + humanDef + ")." : "."));
        } else {
            bullets.add("Current active value: " + humanVal
                    + (defVal != null && !defVal.isEmpty() && !defVal.equals("default") ? " (matches default)." : "."));
        }

        // 2. Category-specific player impact when changed
        if (leaf.contains("chance") || leaf.contains("probability") || leaf.contains("percent")) {
            String subject = humanizeSubject(kLow, leaf, "chance", "probability", "percent");
            String maxStr = (schema.rangeMax() != null && schema.rangeMax() > 1.0) ? "100%" : "1.0 (100%)";
            bullets.add("If increased (toward " + maxStr + "): " + capitalize(subject)
                    + " triggers on a higher percentage of hits/actions (at " + maxStr + ", it triggers every single time).");
            bullets.add("If decreased (toward 0.0 / 0%): " + capitalize(subject)
                    + " triggers less often; setting 0.0 disables the effect completely.");
            return bullets;
        }

        if (isIntervalOrCooldown(leaf)) {
            if (kLow.contains("hopper") || kLow.contains("transfer")) {
                bullets.add("If increased (e.g. 16-20 ticks / 0.8s-1.0s): Hoppers pause longer between transfers — player sorting systems move items slower, but chunk tick (MSPT) lag drops significantly.");
                bullets.add("If decreased (e.g. 1-4 ticks / 0.05s-0.2s): Hoppers move items much faster for high-speed player sorters, at the cost of higher server tick usage.");
            } else if (kLow.contains("spawn") || kLow.contains("wave") || kLow.contains("arena") || kLow.contains("mob")) {
                bullets.add("If increased (higher ticks/seconds): Longer breather between mob/boss spawns — gives players more time to heal and clear waves, and lowers active mob counts.");
                bullets.add("If decreased (lower ticks/seconds): Waves spawn rapidly back-to-back, making combat much harder for players and increasing entity tick load.");
            } else if (kLow.contains("teleport") || kLow.contains("rtp") || kLow.contains("warmup")) {
                bullets.add("If increased: Players must stand still longer after running the command before teleporting.");
                bullets.add("If decreased (0): Players teleport immediately with no warmup countdown.");
            } else {
                String subject = humanizeSubject(kLow, leaf, "ticks", "seconds", "ms", "cooldown", "interval", "delay");
                bullets.add("If increased: Longer wait between " + subject + " actions (slower pace for players, lower server CPU/tick load).");
                bullets.add("If decreased: Shorter wait — " + subject + " runs more frequently (faster response, higher server tick cost).");
            }
            return bullets;
        }

        if (leaf.contains("duration") || leaf.contains("timeout") || leaf.contains("ttl")) {
            String subject = humanizeSubject(kLow, leaf, "seconds", "ticks", "duration", "timeout");
            if (kLow.contains("tag") || kLow.contains("combat") || kLow.contains("pvp")) {
                bullets.add("If increased (e.g. 20-60s): Players stay combat-tagged longer after taking or dealing a hit before they can safely log out or teleport.");
                bullets.add("If decreased (e.g. 0-5s): Combat restrictions wear off sooner after a fight ends (0 disables combat tagging).");
            } else {
                bullets.add("If increased: " + capitalize(subject) + " lasts longer before expiring.");
                bullets.add("If decreased: " + capitalize(subject) + " expires sooner (0 disables the timer).");
            }
            return bullets;
        }

        if (leaf.contains("multiplier") || leaf.contains("scale") || leaf.contains("factor")) {
            String subject = humanizeSubject(kLow, leaf, "multiplier", "scale", "factor");
            bullets.add("If increased (higher multiplier): Scales " + subject + " up (e.g. 2.0 = double strength/health per player), making encounters tougher.");
            bullets.add("If decreased (closer to 1.0 or lower): Reduces " + subject + " scaling (1.0 = unscaled base value).");
            return bullets;
        }

        if (leaf.contains("batch") || leaf.contains("amount") || leaf.contains("max") || leaf.contains("limit") || leaf.contains("cap") || leaf.contains("count")) {
            String subject = humanizeSubject(kLow, leaf, "max", "limit", "cap", "count", "per", "batch");
            if (kLow.contains("hopper") || kLow.contains("item")) {
                bullets.add("If increased (e.g. 32-64 items): Moves larger stacks in a single transfer cycle, speeding up bulk chest/hopper throughput.");
                bullets.add("If decreased (e.g. 1-4 items): Moves fewer items per cycle (1 = vanilla single-item hopper transfer).");
            } else {
                bullets.add("If increased: Raises the cap on " + subject + ", allowing more concurrent items/entities/tasks.");
                bullets.add("If decreased: Tightens the cap on " + subject + " to conserve server memory and tick time.");
            }
            return bullets;
        }

        if ("enum".equals(schema.inferredType()) || !schema.options().isEmpty()) {
            for (String opt : schema.options()) {
                boolean isCur = opt.equalsIgnoreCase(val);
                bullets.add(opt + (isCur ? " (Current): " : ": ") + explainEnumConstantForPlayers(opt, kLow));
            }
            return bullets;
        }

        if ("boolean".equals(schema.inferredType())) {
            String subject = humanizeSubject(kLow, leaf, "enable", "enabled", "allow", "deny");
            if (leaf.startsWith("deny") || leaf.startsWith("cancel") || leaf.startsWith("prevent")) {
                String action = humanizeSubject(leaf, leaf, "deny", "cancel", "prevent");
                bullets.add("true: Cancels/blocks " + action + " for players in this region or mode.");
                bullets.add("false: Allows players to perform " + action + " normally.");
            } else {
                bullets.add("true: Turns on " + subject + " in-game.");
                bullets.add("false: Turns off " + subject + " so the feature is skipped.");
            }
            return bullets;
        }

        if (schema.spatialGizmo() != SpatialGizmoKind.NONE) {
            if (schema.spatialGizmo() == SpatialGizmoKind.RADIUS || schema.spatialGizmo() == SpatialGizmoKind.INNER_RADIUS) {
                bullets.add("If increased: Expands the region/protection boundary further outward across the world map.");
                bullets.add("If decreased: Shrinks the active zone tightly around its center coordinate.");
            } else if (schema.spatialGizmo() == SpatialGizmoKind.WORLD_REFERENCE) {
                bullets.add("Changing world name: Moves this region, arena, or protection rule to the target Minecraft world.");
            } else {
                bullets.add("Changing coordinate: Shifts the center anchor point of this zone on the 2D world map.");
            }
            return bullets;
        }

        bullets.add("If increased: Raises the " + humanizeSubject(kLow, leaf) + " threshold applied by the plugin.");
        bullets.add("If decreased: Lowers the " + humanizeSubject(kLow, leaf) + " threshold.");
        return bullets;
    }

    private static String resolveEffectiveDefault(
            String jarDefaultStr,
            List<BytecodeConfigScanner.KeyCallSite> callSites,
            String fallbackVal
    ) {
        if (jarDefaultStr != null && !jarDefaultStr.isBlank()) {
            return jarDefaultStr.trim();
        }
        if (callSites != null) {
            for (BytecodeConfigScanner.KeyCallSite cs : callSites) {
                if (cs.codeFallbackValue() != null && !cs.codeFallbackValue().isBlank()) {
                    return cs.codeFallbackValue().trim();
                }
            }
        }
        return fallbackVal;
    }

    private static Double parseFiniteDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isIntervalOrCooldown(String leaf) {
        return leaf.contains("interval") || leaf.contains("cooldown") || leaf.contains("delay")
                || leaf.contains("period") || leaf.contains("frequency");
    }

    private static String firstScalingHint(List<BytecodeConfigScanner.KeyCallSite> sites, int pass) {
        if (pass < 2 || sites == null) return null;
        for (BytecodeConfigScanner.KeyCallSite cs : sites) {
            if (cs.unitScalingHint() != null && !cs.unitScalingHint().isEmpty()) {
                return cs.unitScalingHint();
            }
        }
        return null;
    }

    private static String formatValueWithScaling(
            String leaf,
            String rawVal,
            Double numVal,
            InferredSchema schema,
            String scalingHint
    ) {
        if (numVal != null && (schema == null || schema.unit() == null || schema.unit().isEmpty() || "ticks".equals(schema.unit()))) {
            if (scalingHint != null && scalingHint.contains("*1000_MS") && !leaf.endsWith("ms") && !leaf.endsWith("millis")) {
                long ticks = Math.round(numVal * 20.0);
                return "`" + rawVal + " seconds` (" + ticks + " game ticks)";
            }
            if (scalingHint != null && scalingHint.contains("*20_TICKS") && !leaf.endsWith("ticks")) {
                long ticks = Math.round(numVal * 20.0);
                return "`" + rawVal + " seconds` (" + ticks + " game ticks)";
            }
        }
        return formatValueInPlayerTerms(leaf, rawVal, numVal, schema);
    }

    private static String resolveAstTargetMethodOrClass(
            List<String> readByClasses,
            List<BytecodeConfigScanner.KeyCallSite> sites,
            int pass
    ) {
        if (pass >= 3 && sites != null) {
            for (BytecodeConfigScanner.KeyCallSite cs : sites) {
                for (String dm : cs.downstreamMethods()) {
                    String s = simpleClass(dm);
                    String low = s.toLowerCase(Locale.ROOT);
                    if (!low.endsWith("#load") && !low.endsWith("#loaddefaults")
                            && !low.endsWith("#loadconfig") && !low.endsWith("#loadconfiguration")) {
                        return s;
                    }
                }
            }
            for (BytecodeConfigScanner.KeyCallSite cs : sites) {
                if (!cs.downstreamMethods().isEmpty()) {
                    return simpleClass(cs.downstreamMethods().get(0));
                }
            }
        }
        if (pass >= 2 && sites != null && !sites.isEmpty()) {
            BytecodeConfigScanner.KeyCallSite cs = sites.get(0);
            return simpleClass(cs.className()) + "#" + cs.methodName();
        }
        if (readByClasses != null && !readByClasses.isEmpty()) {
            return simpleClass(readByClasses.get(0));
        }
        return "";
    }

    private static String buildAstBoundsAndGuardClause(
            String leaf,
            String val,
            String defVal,
            Double numVal,
            Double defNum,
            InferredSchema schema,
            List<BytecodeConfigScanner.KeyCallSite> sites,
            String scalingHint,
            boolean includeComparisonGuardInMeta,
            int pass
    ) {
        List<String> parts = new ArrayList<>(4);
        if (defVal != null && !defVal.isEmpty() && !"default".equalsIgnoreCase(defVal)) {
            if (!defVal.equals(val)) {
                parts.add("default `" + defVal + "`");
            } else if (pass >= 2 && sites != null && sites.stream().anyMatch(s -> s.codeFallbackValue() != null)) {
                parts.add("default `" + defVal + "`");
            }
        }
        if (schema != null && (schema.rangeMin() != null || schema.rangeMax() != null)) {
            if (schema.rangeMin() != null && schema.rangeMax() != null && !Double.isNaN(schema.rangeMax())) {
                parts.add("range `" + formatNumber(schema.rangeMin()) + ".." + formatNumber(schema.rangeMax()) + "`");
            } else if (schema.rangeMin() != null && schema.rangeMin() > 0.0) {
                parts.add("min `" + formatNumber(schema.rangeMin()) + "`");
            }
        }
        if (pass >= 2 && sites != null) {
            for (BytecodeConfigScanner.KeyCallSite cs : sites) {
                for (String g : cs.guardPredicates()) {
                    if (g.startsWith("enum: ") || g.startsWith("match == ")) {
                        continue;
                    }
                    if (g.startsWith("clamp ") || g.startsWith("capacity ")) {
                        String item = "`" + g + "`";
                        if (!parts.contains(item) && parts.size() < 3) {
                            parts.add(item);
                        }
                    } else if (pass >= 3 && includeComparisonGuardInMeta) {
                        String item = "guard `" + g + "`";
                        if (!parts.contains(item) && parts.size() < 3) {
                            parts.add(item);
                        }
                    }
                }
            }
        }
        if (pass >= 2 && scalingHint != null && !scalingHint.isEmpty()) {
            parts.add("scaled `" + scalingHint + "`");
        }
        if (parts.isEmpty()) {
            return "";
        }
        return " (" + String.join(", ", parts) + ")";
    }

    private static String describeDownstreamEventLead(
            String rawLeaf,
            String leaf,
            List<BytecodeConfigScanner.KeyCallSite> sites,
            List<String> subsystems,
            int pass
    ) {
        if (pass < 3 || sites == null) return "";
        for (BytecodeConfigScanner.KeyCallSite s : sites) {
            List<String> targets = new ArrayList<>(s.downstreamMethods());
            targets.add(s.className() + "#" + s.methodName());
            for (String dm : targets) {
                String ref = simpleClass(dm);
                String mLow = ref.toLowerCase(Locale.ROOT);
                if (mLow.contains("onentitydamage") || mLow.contains("damageentity")) {
                    return "When an entity takes combat damage (`" + ref + "`)";
                }
                if (mLow.contains("schedulecombattag") || mLow.contains("tagexpiry")) {
                    return "When scheduling combat tag expiry (`" + ref + "`)";
                }
                if (mLow.contains("hopperinventory") || mLow.contains("movehopper")) {
                    return "When a hopper transfers container items (`" + ref + "`)";
                }
                if (mLow.contains("evaluateproximityhysteresis")) {
                    return "In `" + ref + "`";
                }
                if (mLow.contains("onchunkunload")) {
                    return "When an active chunk unloads (`" + ref + "`)";
                }
                if (mLow.contains("onchunkreload")) {
                    return "When an unloaded chunk reloads (`" + ref + "`)";
                }
                if (mLow.contains("onplayermove")) {
                    return "When a player moves during teleport warmup (`" + ref + "`)";
                }
                if (mLow.contains("onplayerteleport") || mLow.contains("stopteleport")) {
                    return "When intercepting an in-flight teleport (`" + ref + "`)";
                }
                if (mLow.contains("parseandvalidateresponse")) {
                    return "When validating LLM synthesis responses (`" + ref + "`)";
                }
            }
        }
        return "";
    }

    private static String describeOperationalConsequenceTail(
            String rawLeaf,
            String kLow,
            String leaf,
            List<BytecodeConfigScanner.KeyCallSite> sites,
            List<String> subsystems,
            int pass
    ) {
        if (pass < 2) {
            return "";
        }
        if (pass >= 3 && sites != null) {
            for (BytecodeConfigScanner.KeyCallSite s : sites) {
                for (String g : s.guardPredicates()) {
                    if (g.contains("distanceSquared >=")) {
                        return " before cancelling the pending teleport via `RTPTeleportCancel`";
                    }
                    if (g.equals("value <= wakeRadiusBlocks")) {
                        return ", waking virtualized farm chunks when player distance `<= wakeRadiusBlocks`";
                    }
                    if (g.equals("value <= sleepRadiusBlocks")) {
                        return ", keeping unpaused chunks live while player distance `<= sleepRadiusBlocks`";
                    }
                    if (g.equals("value >= rehibernateHysteresisTicks")) {
                        return " before re-hibernating a chunk once outside-radius time `>= rehibernateHysteresisTicks`";
                    }
                    if (g.equals("value < minStableTicksBeforeUnload") || g.equals("value >= minStableTicksBeforeUnload")) {
                        return " before arming chunk-unload catch-up virtualization";
                    }
                    if (g.equals("clamp <= maxUnloadCatchUpTicks")) {
                        return " when accruing `L95` virtual yield";
                    }
                    if (g.startsWith("window = tick + reloadCooldownTicks")) {
                        return " to prevent chunk unload/reload catch-up thrashing";
                    }
                }
            }
        }
        if (subsystems != null && !subsystems.isEmpty()) {
            if (subsystems.contains("PlayerFeedback/VisualAudio")
                    && subsystems.contains("Combat/DamagePipeline")
                    && (kLow.contains("combat") || kLow.contains("damage") || kLow.contains("bleed") || kLow.contains("pvp") || kLow.contains("tag"))) {
                return " to inflict combat damage and emit visual/particle feedback";
            }
            if (subsystems.contains("Hopper/InventoryTransfer")) {
                return " to throttle container item transfers and bound chunk MSPT";
            }
            if (subsystems.contains("Network/HttpSshTransport")) {
                return " when connecting to the remote LLM synthesis endpoint";
            }
            if (subsystems.contains("ChunkIO/BlockAccess") && leaf.contains("cap")) {
                return " to bound region chunk ticket and candidate buffer memory";
            }
        }
        return "";
    }

    private static String stripOuterParens(String meta) {
        if (meta == null || meta.isEmpty()) return "";
        String t = meta.trim();
        if (t.startsWith("(") && t.endsWith(")")) {
            return ", " + t.substring(1, t.length() - 1);
        }
        return meta;
    }

    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String formatValueInPlayerTerms(String leaf, String rawVal, Double numVal, InferredSchema schema) {
        if (rawVal == null || rawVal.isEmpty()) return "unset";
        if (numVal == null) return "`" + rawVal + "`";

        String unit = schema != null && schema.unit() != null ? schema.unit() : "";
        if ("ticks".equalsIgnoreCase(unit) || leaf.contains("ticks") || leaf.endsWith("-tick")) {
            double seconds = numVal / 20.0;
            String secStr = seconds >= 60.0
                    ? String.format(Locale.ROOT, "%.1f min / %.0fs", seconds / 60.0, seconds)
                    : (seconds == Math.rint(seconds)
                            ? String.format(Locale.ROOT, "%.0f seconds", seconds)
                            : String.format(Locale.ROOT, "%.2f seconds", seconds));
            return "`" + rawVal + " ticks` (" + secStr + ")";
        }
        if ("s".equalsIgnoreCase(unit) || "seconds".equalsIgnoreCase(unit) || leaf.contains("seconds") || leaf.endsWith("-sec")) {
            long ticks = Math.round(numVal * 20.0);
            return "`" + rawVal + " seconds` (" + ticks + " game ticks)";
        }
        if ("ms".equalsIgnoreCase(unit) || leaf.contains("millis") || leaf.endsWith("-ms")) {
            return "`" + rawVal + " ms` (" + String.format(Locale.ROOT, "%.2f ticks", numVal / 50.0) + ")";
        }
        if (leaf.contains("chance") || leaf.contains("probability")) {
            if (numVal >= 0.0 && numVal <= 1.0) {
                return String.format(Locale.ROOT, "%.0f%% (`%s`)", numVal * 100.0, rawVal);
            }
            return "`" + rawVal + "%`";
        }
        if (leaf.contains("multiplier")) {
            return String.format(Locale.ROOT, "`%sx` (%.0f%% of base)", rawVal, numVal * 100.0);
        }
        if ("blocks".equalsIgnoreCase(unit) || leaf.contains("radius") || leaf.endsWith("blocks") || leaf.contains("distance")) {
            long chunks = Math.round(numVal / 16.0);
            return "`" + rawVal + " blocks`" + (chunks > 0 ? " (~" + chunks + " chunks)" : "");
        }
        if ("chunks".equalsIgnoreCase(unit) || leaf.endsWith("chunks")) {
            long blocks = Math.round(numVal * 16.0);
            return "`" + rawVal + " chunks` (" + blocks + " blocks)";
        }
        if (leaf.contains("batch") || leaf.contains("items")) {
            return "`" + rawVal + " items`";
        }
        if (leaf.endsWith("chars")) {
            return "`" + rawVal + " chars`";
        }
        return "`" + rawVal + "`" + (!unit.isEmpty() ? " " + unit : "");
    }

    private static String describeDownstreamGameplayTrigger(
            List<BytecodeConfigScanner.KeyCallSite> sites,
            List<String> subsystems,
            int pass
    ) {
        if (pass < 3 || sites == null) return "";
        for (BytecodeConfigScanner.KeyCallSite s : sites) {
            for (String dm : s.downstreamMethods()) {
                String mLow = dm.toLowerCase(Locale.ROOT);
                if (mLow.contains("entitydamage") || mLow.contains("damage") || mLow.contains("attack")) {
                    return "Whenever a player or mob hits an entity in combat";
                }
                if (mLow.contains("hopper") || mLow.contains("inventorymove") || mLow.contains("transfer")) {
                    return "Whenever a hopper transfers items between containers";
                }
                if (mLow.contains("blockbreak") || mLow.contains("blockplace")) {
                    return "Whenever a player breaks or places a block";
                }
                if (mLow.contains("teleport") || mLow.contains("move")) {
                    return "Whenever a player moves or teleports";
                }
                if (mLow.contains("spawn") || mLow.contains("wave")) {
                    return "During mob/wave spawning";
                }
                if (mLow.contains("schedule") || mLow.contains("timer") || mLow.contains("expiry")) {
                    return "On the plugin's combat/task timer";
                }
            }
        }
        if (subsystems != null && !subsystems.isEmpty()) {
            if (subsystems.contains("Combat/DamagePipeline")) return "During player/entity combat";
            if (subsystems.contains("Hopper/InventoryTransfer")) return "During hopper item transfers";
            if (subsystems.contains("EntitySpawning/MobAI")) return "During mob spawning and AI ticks";
            if (subsystems.contains("WorldBorder/SpatialTeleport")) return "During world border and teleport checks";
        }
        return "";
    }

    private static String describeEnumSettingInPlayerTerms(String kLow, String leaf, String val, List<String> options) {
        String subject = humanizeSubject(kLow, leaf, "mode", "type", "policy");
        String curExplanation = explainEnumConstantForPlayers(val, kLow);
        return "Selects the active " + subject + " behavior for players (currently `" + val + "`: "
                + curExplanation.substring(0, 1).toLowerCase(Locale.ROOT) + curExplanation.substring(1) + ")";
    }

    private static String explainEnumConstantForPlayers(String option, String kLow) {
        String u = option.toUpperCase(Locale.ROOT);
        if (u.equals("STRICT")) {
            return "Blocks unauthorized block breaking, placing, and interactions for all players inside the protected area.";
        }
        if (u.equals("PASSTHROUGH")) {
            return "Disables claim blocking so players can walk through and interact as if in unclaimed wilderness.";
        }
        if (u.equals("OP_BYPASS")) {
            return "Enforces protection against regular players while letting server operators (`op`) build and break blocks freely.";
        }
        if (u.equals("STRICT_LOCK")) {
            return "Tagged players cannot teleport, run restricted commands, or safely log out until combat ends.";
        }
        if (u.equals("ENDERPEARL_ONLY")) {
            return "Tagged players can use commands normally, but Ender Pearl throws are blocked during combat.";
        }
        if (u.equals("TELEPORT_CANCEL")) {
            return "Tagged players can fight normally, but `/rtp`, `/spawn`, and `/home` teleports are cancelled while in combat.";
        }
        if (u.equals("SAFE_SURFACE")) {
            return "Spawns entities or players only on solid, sky-exposed surface blocks.";
        }
        if (u.equals("CAVE_FLOOR")) {
            return "Allows spawning on underground cave floors beneath solid ceilings.";
        }
        if (u.equals("SKY_ISLAND")) {
            return "Targets elevated floating islands and high-altitude terrain.";
        }
        if (u.equals("OBSERVE")) {
            return "Passive lag attribution, mechanism archetype classification, and causal web tracing only.";
        }
        if (u.equals("GOVERN_SAFE")) {
            return "Contraption-safe Pareto governor with specialist hopper and 40-tick piston immunity.";
        }
        if (u.equals("GOVERN_BLACKBOX")) {
            return "Full Pareto governor plus provably-generative farm black-box virtualization and proximity wake.";
        }
        if (u.equals("FLEET_GLOBAL")) {
            return "Synchronizes setting across all servers in the fleet.";
        }
        if (u.equals("SELECTIVE_GROUP")) {
            return "Synchronizes setting only within matching server groups/regions.";
        }
        if (u.equals("PINNED_LOCAL")) {
            return "Locks setting to the local server instance and never overwrites across fleet sync.";
        }
        if (u.equals("GEMINI")) {
            return "Uses the Google Gemini public REST API endpoint via the configured API key environment variable.";
        }
        if (u.equals("OPENAI_COMPATIBLE")) {
            return "Uses any OpenAI-compatible `/v1/chat/completions` endpoint (OpenAI, OpenRouter, Groq, vLLM, LM Studio).";
        }
        if (u.equals("ANTHROPIC")) {
            return "Uses the Anthropic Claude Messages API (`/v1/messages`) via `ANTHROPIC_API_KEY`.";
        }
        if (u.equals("OLLAMA")) {
            return "Uses a local or SSH-forwarded Ollama endpoint with automatic `<think>` block stripping.";
        }
        if (u.equals("OFF")) {
            return "Uses deterministic local bytecode AST clause synthesis with zero network calls.";
        }
        if (u.equals("REROLL")) {
            return "Redraws candidate coordinates when a sampled chunk is marked unsafe.";
        }
        if (u.equals("ACCUMULATE")) {
            return "Samples directly from the remaining open/safe chunk index space.";
        }
        return "Applies `" + option + "` rules to " + humanizeConceptPhrase(kLow, extractLeaf(kLow)) + ".";
    }

    private static String describeSpatialSettingInPlayerTerms(
            String kLow,
            String leaf,
            String val,
            Double numVal,
            SpatialGizmoKind gizmo,
            String astTargetRef,
            String astMeta
    ) {
        String rawScope = kLow.contains(".") ? kLow.substring(0, kLow.lastIndexOf('.')) : "region";
        String whereClause = (astTargetRef != null && !astTargetRef.isEmpty()) ? (" in `" + astTargetRef + "`") : "";
        return switch (gizmo) {
            case RADIUS -> "Sets the outer boundary radius of the `" + rawScope + "` zone to `" + val + " blocks`"
                    + (numVal != null ? " (~" + Math.round(numVal / 16.0) + " chunks from center)" : "")
                    + astMeta + whereClause + ".";
            case INNER_RADIUS -> "Sets the inner exclusion hole radius of `" + rawScope + "` to `" + val + " blocks`"
                    + astMeta + whereClause + ".";
            case CENTER_X -> "Sets the East/West (X) center coordinate of the `" + rawScope + "` zone to `" + val + "`"
                    + astMeta + whereClause + ".";
            case CENTER_Z -> "Sets the North/South (Z) center coordinate of the `" + rawScope + "` zone to `" + val + "`"
                    + astMeta + whereClause + ".";
            case WORLD_REFERENCE -> "Assigns the `" + rawScope + "` zone to the `" + val + "` Minecraft world"
                    + astMeta + whereClause + ".";
            default -> "Sets the `" + leaf + "` coordinate (`" + val + "`) for `" + rawScope + "`"
                    + astMeta + whereClause + ".";
        };
    }

    static String normalizeCamelToHyphen(String s) {
        if (s == null || s.isEmpty()) return "";
        String pre = s.replace("PvP", "Pvp").replace("PVP", "Pvp");
        String split = pre
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
                .replace('_', '-');
        return split.toLowerCase(Locale.ROOT);
    }

    private static String humanizeConceptPhrase(String kLow, String leaf, String... stripTokens) {
        return humanizeSubject(kLow, leaf, stripTokens);
    }

    private static String humanizeSubject(String kLow, String leaf, String... stripTokens) {
        String normLeaf = normalizeCamelToHyphen(leaf).replace('-', ' ').replace('_', ' ');
        int lastDot = kLow.lastIndexOf('.');
        String parentPart = lastDot >= 0 ? normalizeCamelToHyphen(kLow.substring(0, lastDot)).replace('.', ' ').replace('-', ' ').replace('_', ' ') : "";
        Set<String> leafTokenSet = new LinkedHashSet<>(List.of(normLeaf.split("\\s+")));
        StringBuilder combined = new StringBuilder();
        if (!parentPart.isEmpty()) {
            for (String pt : parentPart.split("\\s+")) {
                if (pt.isEmpty() || "defaults".equalsIgnoreCase(pt) || leafTokenSet.contains(pt)) {
                    continue;
                }
                if (combined.length() > 0) combined.append(' ');
                combined.append(pt);
            }
        }
        if (combined.length() > 0 && !normLeaf.isEmpty()) {
            combined.append(' ');
        }
        combined.append(normLeaf);
        String normPath = combined.toString();
        for (String st : stripTokens) {
            normPath = normPath.replaceAll("(?i)\\b" + Pattern.quote(st) + "\\b", " ");
        }
        normPath = normPath.trim().replaceAll("\\s+", " ");
        if (normPath.isEmpty()) {
            normPath = normLeaf.trim();
        }
        // Format common technical acronyms cleanly
        String[] words = normPath.split("\\s+");
        List<String> formatted = new ArrayList<>(words.length);
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (!formatted.isEmpty() && formatted.get(formatted.size() - 1).equalsIgnoreCase(w)) {
                continue;
            }
            formatted.add(switch (w) {
                case "ttl" -> "TTL";
                case "mspt" -> "MSPT";
                case "ssh" -> "SSH";
                case "http" -> "HTTP";
                case "llm" -> "LLM";
                case "ide" -> "IDE";
                case "rtp" -> "RTP";
                case "pvp" -> "PvP";
                case "gui" -> "GUI";
                case "jar" -> "JAR";
                case "rehibernate" -> "re-hibernation";
                default -> w;
            });
        }
        return formatted.isEmpty() ? leaf : String.join(" ", formatted);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static List<String> formatCallSites(List<BytecodeConfigScanner.KeyCallSite> sites) {
        List<String> out = new ArrayList<>();
        for (BytecodeConfigScanner.KeyCallSite s : sites) {
            if (out.size() >= 16) break;
            String fb = s.codeFallbackValue() != null ? (", default=" + s.codeFallbackValue()) : "";
            String guards = !s.guardPredicates().isEmpty() ? (" [" + String.join(", ", s.guardPredicates()) + "]") : "";
            String desc = simpleClass(s.className()) + "#" + s.methodName() + " -> " + s.getterMethod() + "(key" + fb + ")" + guards;
            if (!out.contains(desc)) {
                out.add(desc);
            }
        }
        return out;
    }

    private static List<String> formatFieldFlows(List<BytecodeConfigScanner.KeyCallSite> sites) {
        List<String> out = new ArrayList<>();
        for (BytecodeConfigScanner.KeyCallSite s : sites) {
            if (out.size() >= 16) break;
            if (s.assignedField() != null && !s.downstreamMethods().isEmpty()) {
                List<String> simpleDown = new ArrayList<>();
                for (String dm : s.downstreamMethods()) {
                    if (simpleDown.size() >= 8) break;
                    simpleDown.add(simpleClass(dm));
                }
                String flow = "this." + s.assignedField() + " -> " + String.join(", ", simpleDown);
                if (!out.contains(flow)) {
                    out.add(flow);
                }
            } else if (s.assignedField() != null) {
                String flow = "this." + s.assignedField() + " (cached field)";
                if (!out.contains(flow)) {
                    out.add(flow);
                }
            }
        }
        return out;
    }

    private static List<String> collectSubsystems(List<BytecodeConfigScanner.KeyCallSite> sites) {
        Set<String> set = new LinkedHashSet<>();
        for (BytecodeConfigScanner.KeyCallSite s : sites) {
            set.addAll(s.subsystemApis());
        }
        return new ArrayList<>(set);
    }

    private static String simpleClass(String binaryOrMethodRef) {
        if (binaryOrMethodRef == null) return "";
        int hash = binaryOrMethodRef.indexOf('#');
        String cls = hash >= 0 ? binaryOrMethodRef.substring(0, hash) : binaryOrMethodRef;
        String suffix = hash >= 0 ? binaryOrMethodRef.substring(hash) : "";
        int dot = cls.lastIndexOf('.');
        String shortCls = dot >= 0 ? cls.substring(dot + 1) : cls;
        int dollar = shortCls.lastIndexOf('$');
        if (dollar >= 0 && dollar + 1 < shortCls.length()) {
            shortCls = shortCls.substring(dollar + 1);
        }
        return shortCls + suffix;
    }

    private static String simpleClassList(List<String> classes) {
        List<String> out = new ArrayList<>(classes.size());
        for (String c : classes) {
            out.add("`" + simpleClass(c) + "`");
        }
        return String.join(", ", out);
    }

    private static String stripCommentPrefixes(String commentText) {
        if (commentText == null || commentText.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : commentText.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#")) t = t.substring(1).trim();
            if (t.isEmpty() || t.startsWith("@") || t.startsWith("---")) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(t);
        }
        return sb.toString().trim();
    }

    /** Classifies whether a config key represents a spatial coordinate, radius, or region boundary on the 2D Map. */
    public static SpatialGizmoKind classifySpatialGizmo(String leaf, String lowerPath, String inferredType) {
        if ("vertices".equals(leaf) || lowerPath.endsWith(".vertices") || lowerPath.endsWith(".polygon-points")) {
            return SpatialGizmoKind.POLYGON_VERTICES;
        }
        if ("world".equals(leaf) || "world-name".equals(leaf) || "worldname".equals(leaf) || lowerPath.endsWith(".world")) {
            return SpatialGizmoKind.WORLD_REFERENCE;
        }
        boolean numericOrDist = "integer".equals(inferredType) || "double".equals(inferredType)
                || "number".equals(inferredType) || "distance".equals(inferredType);
        if (!numericOrDist) {
            return SpatialGizmoKind.NONE;
        }
        if ("centerradius".equals(leaf) || "center-radius".equals(leaf) || "innerradius".equals(leaf) || "inner-radius".equals(leaf)) {
            return SpatialGizmoKind.INNER_RADIUS;
        }
        if ("radius".equals(leaf) || "max-radius".equals(leaf) || "maxradius".equals(leaf)
                || "border-radius".equals(leaf) || "claim-radius".equals(leaf) || "spawn-radius".equals(leaf)) {
            return SpatialGizmoKind.RADIUS;
        }
        if ("centerx".equals(leaf) || "center-x".equals(leaf) || "origin-x".equals(leaf)
                || (leaf.equals("x") && (lowerPath.contains("center") || lowerPath.contains("region") || lowerPath.contains("border")))) {
            return SpatialGizmoKind.CENTER_X;
        }
        if ("centerz".equals(leaf) || "center-z".equals(leaf) || "origin-z".equals(leaf)
                || (leaf.equals("z") && (lowerPath.contains("center") || lowerPath.contains("region") || lowerPath.contains("border")))) {
            return SpatialGizmoKind.CENTER_Z;
        }
        if ("minx".equals(leaf) || "min-x".equals(leaf) || "x1".equals(leaf) || "pos1-x".equals(leaf)) {
            return SpatialGizmoKind.MIN_X;
        }
        if ("maxx".equals(leaf) || "max-x".equals(leaf) || "x2".equals(leaf) || "pos2-x".equals(leaf)) {
            return SpatialGizmoKind.MAX_X;
        }
        if ("minz".equals(leaf) || "min-z".equals(leaf) || "z1".equals(leaf) || "pos1-z".equals(leaf)) {
            return SpatialGizmoKind.MIN_Z;
        }
        if ("maxz".equals(leaf) || "max-z".equals(leaf) || "z2".equals(leaf) || "pos2-z".equals(leaf)) {
            return SpatialGizmoKind.MAX_Z;
        }
        if (lowerPath.contains("spawn") && (leaf.endsWith("x") || leaf.endsWith("z"))) {
            return SpatialGizmoKind.SPAWN_COORDINATE;
        }
        return SpatialGizmoKind.NONE;
    }

    private static ReloadTier classifyReloadTier(String lowerPath, String comment) {
        String lowComment = comment.toLowerCase(Locale.ROOT);
        if (lowComment.contains("requires a server restart")
                || lowComment.contains("requires restart")
                || lowComment.contains("restart required")
                || lowerPath.contains("database.host")
                || lowerPath.contains("database.port")
                || lowerPath.contains("io-threads")
                || lowerPath.contains("worker-threads")) {
            return ReloadTier.SERVER_RESTART;
        }
        if (lowComment.contains("hot-apply") || lowComment.contains("hot reload")
                || lowerPath.startsWith("regions.") || lowerPath.startsWith("performance.")) {
            return ReloadTier.HOT_RELOAD;
        }
        return ReloadTier.PLUGIN_RELOAD;
    }

    private static List<String> extractCommentOptions(String comment) {
        Matcher m = COMMENT_OPTIONS_RE.matcher(comment);
        if (!m.find()) {
            return List.of();
        }
        String rawGroup = m.group(1);
        String[] parts = rawGroup.split("[,|/]|\\s+or\\s+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String cleaned = p.trim().replaceAll("^['\"`]+|['\"`.]+$", "").trim();
            if (cleaned.length() >= 2 && cleaned.length() <= 40 && cleaned.matches("^[A-Za-z0-9_.-]+$")) {
                out.add(cleaned);
            }
        }
        return out.size() >= 2 ? out : List.of();
    }

    private static double[] extractCommentRange(String comment) {
        Matcher m1 = COMMENT_RANGE_BETWEEN_RE.matcher(comment);
        if (m1.find()) {
            try {
                double a = Double.parseDouble(m1.group(1));
                double b = Double.parseDouble(m1.group(2));
                return new double[]{Math.min(a, b), Math.max(a, b)};
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher m2 = COMMENT_PAREN_RANGE_RE.matcher(comment);
        if (m2.find()) {
            try {
                double a = Double.parseDouble(m2.group(1));
                double b = Double.parseDouble(m2.group(2));
                return new double[]{Math.min(a, b), Math.max(a, b)};
            } catch (NumberFormatException ignored) {
            }
        }
        if (comment.toLowerCase(Locale.ROOT).contains("-1 to disable")) {
            return new double[]{-1.0, Double.NaN};
        }
        return null;
    }

    private static boolean hasNonDirectiveProse(List<String> comments) {
        for (String line : comments) {
            if (line == null) continue;
            String t = line.trim();
            if (t.startsWith("#")) t = t.substring(1).trim();
            if (!t.isEmpty() && !t.startsWith("@") && !t.startsWith("---")) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesWorldReference(String leaf, String lowerPath) {
        return "world".equals(leaf) || "worlds".equals(leaf) || leaf.endsWith("-world")
                || leaf.endsWith("worldname") || lowerPath.endsWith(".world");
    }

    private static boolean matchesMinecraftRegistry(String leaf) {
        return "material".equals(leaf) || "block".equals(leaf) || "item".equals(leaf)
                || "biome".equals(leaf) || "sound".equals(leaf) || "particle".equals(leaf)
                || "enchantment".equals(leaf) || "entity-type".equals(leaf) || "entitytype".equals(leaf);
    }
    private static String normalizeRegistryName(String leaf) {
        return "entitytype".equals(leaf) ? "entity-type" : leaf;
    }

    /**
     * Resolves well-known external Bukkit, Minecraft, or JDK enum constants when bytecode
     * invokes {@code EnumType.valueOf(String)} on an API enum outside the plugin JAR.
     */
    public static List<String> resolveKnownExternalEnumConstants(String binaryOrFqnEnumName, String sampleValue) {
        if (binaryOrFqnEnumName == null || binaryOrFqnEnumName.isBlank()) {
            return List.of();
        }
        String norm = binaryOrFqnEnumName.replace('/', '.').trim();
        int lastSep = Math.max(norm.lastIndexOf('.'), norm.lastIndexOf('$'));
        String simple = (lastSep >= 0 ? norm.substring(lastSep + 1) : norm).toLowerCase(Locale.ROOT);
        List<String> upper = switch (simple) {
            case "difficulty" -> List.of("PEACEFUL", "EASY", "NORMAL", "HARD");
            case "gamemode" -> List.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR");
            case "environment" -> List.of("NORMAL", "NETHER", "THE_END", "CUSTOM");
            case "barcolor" -> List.of("PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE");
            case "barstyle" -> List.of("SOLID", "SEGMENTED_6", "SEGMENTED_10", "SEGMENTED_12", "SEGMENTED_20");
            case "eventpriority" -> List.of("LOWEST", "LOW", "NORMAL", "HIGH", "HIGHEST", "MONITOR");
            case "timeunit" -> List.of("MILLISECONDS", "SECONDS", "MINUTES", "HOURS", "DAYS");
            default -> List.of();
        };
        if (upper.isEmpty()) {
            return List.of();
        }
        return adaptEnumCaseToSample(upper, sampleValue);
    }

    /**
     * Resolves built-in vanilla / Bukkit / Paper / proxy engine enumeration options for standard
     * configuration keys (such as {@code difficulty}, {@code gamemode}, {@code environment},
     * {@code level-type}) while preserving lowercase vs uppercase style from {@code rawValue}.
     */
    public static List<String> resolveBuiltinEngineEnumOptions(String keyPath, Object rawValue) {
        if (keyPath == null || keyPath.isBlank()) {
            return List.of();
        }
        String leaf = normalizeCamelToHyphen(extractLeaf(keyPath));
        String valStr = rawValue == null ? "" : String.valueOf(rawValue).trim();

        if ("difficulty".equals(leaf) || "server-difficulty".equals(leaf) || "world-difficulty".equals(leaf)
                || (leaf.endsWith("-difficulty") && matchesAnyIgnoreCase(valStr, List.of("PEACEFUL", "EASY", "NORMAL", "HARD")))) {
            List<String> base = List.of("PEACEFUL", "EASY", "NORMAL", "HARD");
            if (valStr.isEmpty() || matchesAnyIgnoreCase(valStr, base)) {
                return adaptEnumCaseToSample(base, valStr);
            }
        }
        if ("gamemode".equals(leaf) || "game-mode".equals(leaf)
                || "default-gamemode".equals(leaf) || "default-game-mode".equals(leaf)) {
            List<String> base = List.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR");
            if (valStr.isEmpty() || matchesAnyIgnoreCase(valStr, base)) {
                return adaptEnumCaseToSample(base, valStr);
            }
        }
        if ("environment".equals(leaf) || "world-environment".equals(leaf)
                || ("dimension".equals(leaf) && matchesAnyIgnoreCase(valStr, List.of("NORMAL", "NETHER", "THE_END", "CUSTOM")))) {
            List<String> base = List.of("NORMAL", "NETHER", "THE_END", "CUSTOM");
            if (valStr.isEmpty() || matchesAnyIgnoreCase(valStr, base)) {
                return adaptEnumCaseToSample(base, valStr);
            }
        }
        if ("level-type".equals(leaf)) {
            if (valStr.startsWith("minecraft:") || isLowerCaseSample(valStr)) {
                return List.of(
                        "minecraft:normal",
                        "minecraft:flat",
                        "minecraft:large_biomes",
                        "minecraft:amplified",
                        "minecraft:single_biome_surface"
                );
            }
            return List.of("NORMAL", "FLAT", "LARGE_BIOMES", "AMPLIFIED", "SINGLE_BIOME_SURFACE");
        }
        if ("region-file-compression".equals(leaf)) {
            return List.of("deflate", "lz4", "none");
        }
        if ("player-info-forwarding-mode".equals(leaf) || "forwarding-mode".equals(leaf)) {
            return adaptEnumCaseToSample(List.of("NONE", "LEGACY", "BUNGEEGUARD", "MODERN"), valStr);
        }
        return List.of();
    }

    private static boolean matchesAnyIgnoreCase(String val, List<String> candidates) {
        if (val == null || val.isEmpty()) return false;
        for (String c : candidates) {
            if (c.equalsIgnoreCase(val)) return true;
        }
        return false;
    }

    private static boolean isLowerCaseSample(String sampleValue) {
        if (sampleValue == null || sampleValue.isEmpty()) return false;
        boolean hasLower = false;
        for (int i = 0; i < sampleValue.length(); i++) {
            char ch = sampleValue.charAt(i);
            if (Character.isUpperCase(ch)) return false;
            if (Character.isLowerCase(ch)) hasLower = true;
        }
        return hasLower;
    }

    private static List<String> adaptEnumCaseToSample(List<String> upperOptions, String sampleValue) {
        if (isLowerCaseSample(sampleValue)) {
            List<String> lower = new ArrayList<>(upperOptions.size());
            for (String opt : upperOptions) {
                lower.add(opt.toLowerCase(Locale.ROOT));
            }
            return List.copyOf(lower);
        }
        return upperOptions;
    }

    private static boolean isDurationSuffix(String leaf) {
        return leaf.endsWith("-ticks") || leaf.endsWith("ticks") || leaf.contains("-ticks-")
                || leaf.endsWith("-ms") || leaf.endsWith("millis") || leaf.contains("-ms-")
                || leaf.endsWith("-seconds") || leaf.endsWith("seconds") || leaf.contains("-seconds-")
                || leaf.endsWith("-delay") || leaf.endsWith("delay")
                || leaf.endsWith("-cooldown") || leaf.endsWith("cooldown")
                || leaf.endsWith("-interval") || leaf.endsWith("interval")
                || leaf.endsWith("-duration") || leaf.endsWith("duration")
                || leaf.endsWith("-timeout") || leaf.endsWith("-ttl");
    }

    private static String inferDurationUnit(String leaf) {
        if (leaf.endsWith("-ms") || leaf.endsWith("millis") || leaf.endsWith("ms") || leaf.contains("-ms-")) return "ms";
        if (leaf.endsWith("-seconds") || leaf.endsWith("seconds") || leaf.endsWith("sec")
                || leaf.contains("-seconds-") || leaf.endsWith("-ttl")) return "s";
        return "ticks";
    }

    private static boolean isCountOrDistanceSuffix(String leaf) {
        return leaf.endsWith("-radius") || leaf.endsWith("radius") || leaf.contains("-radius-")
                || leaf.endsWith("-distance") || leaf.endsWith("distance") || leaf.contains("-distance-")
                || leaf.endsWith("-chunks") || leaf.endsWith("chunks")
                || leaf.endsWith("-blocks") || leaf.endsWith("blocks")
                || leaf.endsWith("-max") || leaf.endsWith("-limit") || leaf.endsWith("limit")
                || leaf.endsWith("-count") || leaf.endsWith("count")
                || leaf.endsWith("-size") || leaf.endsWith("size")
                || leaf.endsWith("-threads") || leaf.endsWith("threads")
                || leaf.endsWith("-cap") || leaf.endsWith("cap")
                || leaf.endsWith("-uses") || leaf.endsWith("-chars")
                || isCoordinateAxis(leaf);
    }

    private static boolean isCoordinateAxis(String leaf) {
        return "centerx".equals(leaf) || "centerz".equals(leaf)
                || "center-x".equals(leaf) || "center-z".equals(leaf)
                || "minx".equals(leaf) || "maxx".equals(leaf)
                || "minz".equals(leaf) || "maxz".equals(leaf)
                || "min-x".equals(leaf) || "max-x".equals(leaf)
                || "min-z".equals(leaf) || "max-z".equals(leaf)
                || "miny".equals(leaf) || "maxy".equals(leaf)
                || "min-y".equals(leaf) || "max-y".equals(leaf);
    }

    private static String inferDistanceUnit(String leaf) {
        if (leaf.contains("chunk") && !leaf.endsWith("-cap")) return "chunks";
        if (leaf.contains("radius") || leaf.contains("distance") || leaf.contains("block") || isCoordinateAxis(leaf)) {
            return "blocks";
        }
        return null;
    }

    private static boolean isRatioOrChanceSuffix(String leaf) {
        return leaf.endsWith("-chance") || leaf.endsWith("chance")
                || leaf.endsWith("-multiplier") || leaf.endsWith("multiplier")
                || leaf.endsWith("-rate") || leaf.endsWith("rate")
                || leaf.endsWith("-percent") || leaf.endsWith("percent")
                || leaf.endsWith("-weight") || leaf.endsWith("weight")
                || leaf.endsWith("-ratio") || leaf.endsWith("ratio")
                || leaf.endsWith("-threshold") || leaf.endsWith("threshold");
    }

    private static final Pattern UNSAFE_DISPLAY_LITERAL_RE = Pattern.compile(
            "(?i)(?:ignore\\s+(?:all\\s+)?(?:previous|prior|above)\\s+instructions|system\\s+prompt|you\\s+are\\s+now|<\\s*\\/?\\s*(?:script|iframe|img|svg|object|embed|style)\\b[^>]*>|javascript:|onerror\\s*=)"
    );

    static String sanitizeDisplayLiteral(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String cleaned = UNSAFE_DISPLAY_LITERAL_RE.matcher(raw).replaceAll("[redacted]");
        cleaned = cleaned.replace('<', '(').replace('>', ')').replaceAll("[\\r\\n\\t]+", " ").trim();
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80) + "...";
        }
        return cleaned;
    }

    static String extractLeaf(String keyPath) {
        int dot = keyPath.lastIndexOf('.');
        return dot >= 0 ? keyPath.substring(dot + 1) : keyPath;
    }

    private static String formatNumber(Double d) {
        if (d == null || Double.isNaN(d) || Double.isInfinite(d)) return "null";
        if (d == Math.rint(d)) {
            return Long.toString(d.longValue());
        }
        return Double.toString(d);
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
