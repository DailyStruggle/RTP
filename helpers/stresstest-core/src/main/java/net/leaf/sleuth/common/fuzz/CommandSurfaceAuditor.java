package net.leaf.sleuth.common.fuzz;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure-Java Brigadier and Bukkit command-surface auditor performing per-tier graph merging,
 * {@code namespace:cmd} alias folding, {@link #DEFAULT_DENYLIST} path and {@code <argkind>}
 * filtering, and {@link LeastPrivilegeOracle} privilege exposure checks across {@code default}
 * versus {@code op} permission tiers ({@code ADR-125}, {@code leafsleuth-ADR-006}).
 *
 * <p>Operates on platform-neutral {@link RawCommandNode} trees extracted by platform adapters
 * (such as {@code PaperCommandTreeInspector}) with zero Bukkit, Paper, or NMS imports
 * ({@code ADR-129}).
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy ADR-129
 * @governedBy leafsleuth-ADR-006
 * @trace REQ-SLEUTH-FUZZ-003
 * @trace S-004
 * @trace S-005
 */
public final class CommandSurfaceAuditor {

    /** Canonical permission tier order ({@code default} before {@code op}). */
    public static final List<String> TIER_ORDER = List.of("default", "op");

    /**
     * Built-in administrative and tick-rate-mutating command denylist from {@code ADR-125} section 2.5.
     */
    public static final List<String> DEFAULT_DENYLIST = List.of(
            "stop",
            "restart",
            "op",
            "deop",
            "ban*",
            "pardon*",
            "whitelist",
            "save-off",
            "kill <entity>",
            "execute",
            "reload",
            "reload confirm",
            "kick",
            "difficulty",
            "gamerule",
            "tick freeze",
            "tick unfreeze",
            "tick rate",
            "tick sprint",
            "tick step"
    );

    /**
     * Administrative or state-mutating literal tokens that should never be executable on the
     * unprivileged {@code default} permission tier without an explicit permission guard.
     */
    public static final Set<String> STATE_MUTATING_LITERALS = Set.of(
            "reload",
            "admin",
            "give",
            "set",
            "delete",
            "remove",
            "clear",
            "reset",
            "purge",
            "import",
            "export",
            "save",
            "load",
            "forcetp",
            "tpall",
            "sudo",
            "bypass",
            "debug",
            "config",
            "setspawn",
            "delwarp",
            "setwarp",
            "eco",
            "grant",
            "revoke"
    );

    private static final int MAX_REDIRECT_DEPTH = 16;

    /**
     * Platform-neutral raw command node extracted from a single permission tier's Brigadier
     * or Bukkit command graph.
     *
     * @param name           node token name (e.g. {@code "rtp"}, {@code "betterrtp:rtp"}, {@code "radius"})
     * @param kind           {@code "root"}, {@code "literal"}, or {@code "argument"}
     * @param parser         Brigadier argument parser identifier (e.g. {@code "brigadier:integer"}, {@code "minecraft:entity"})
     * @param properties     optional parser bounds/properties (e.g. {@code min}, {@code max}, {@code type})
     * @param executable     whether this node is directly executable
     * @param redirectTarget optional canonical redirect target path
     * @param permission     optional explicit permission string declared on the command
     * @param owningPlugin   optional owning plugin ID or JAR name
     * @param children       child nodes in this tier's graph
     */
    public record RawCommandNode(
            String name,
            String kind,
            String parser,
            Map<String, Object> properties,
            boolean executable,
            String redirectTarget,
            String permission,
            String owningPlugin,
            List<RawCommandNode> children
    ) {
        public RawCommandNode {
            name = name == null ? "" : name.trim();
            kind = (kind == null || kind.isBlank()) ? "literal" : kind.trim().toLowerCase(Locale.ROOT);
            parser = (parser == null || parser.isBlank()) ? null : parser.trim();
            properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
            redirectTarget = (redirectTarget == null || redirectTarget.isBlank()) ? null : redirectTarget.trim();
            permission = (permission == null || permission.isBlank()) ? null : permission.trim();
            owningPlugin = (owningPlugin == null || owningPlugin.isBlank()) ? null : owningPlugin.trim();
            children = children == null ? List.of() : List.copyOf(children);
        }

        public static RawCommandNode literal(String name, boolean executable, List<RawCommandNode> children) {
            return new RawCommandNode(name, "literal", null, Map.of(), executable, null, null, null, children);
        }

        public static RawCommandNode literal(
                String name,
                boolean executable,
                String permission,
                String owningPlugin,
                List<RawCommandNode> children
        ) {
            return new RawCommandNode(name, "literal", null, Map.of(), executable, null, permission, owningPlugin, children);
        }

        public static RawCommandNode argument(
                String name,
                String parser,
                Map<String, Object> properties,
                boolean executable,
                List<RawCommandNode> children
        ) {
            return new RawCommandNode(name, "argument", parser, properties, executable, null, null, null, children);
        }
    }

    /**
     * Step in a command path below the root literal.
     *
     * @param kind   {@code "literal"} or {@code "argument"}
     * @param name   token name
     * @param parser normalized or raw parser name for {@code "argument"} steps
     */
    public record SequenceStep(String kind, String name, String parser) {
        public SequenceStep {
            kind = (kind == null || kind.isBlank()) ? "literal" : kind.trim().toLowerCase(Locale.ROOT);
            name = name == null ? "" : name.trim();
            parser = (parser == null || parser.isBlank()) ? null : parser.trim();
        }

        public String token() {
            return "argument".equals(kind) ? "<" + name + ">" : name;
        }
    }

    /**
     * Canonical merged command node in a {@link CommandTree}.
     *
     * @param nodeId         canonical space-separated path (e.g. {@code "rtp"}, {@code "rtp <radius>"})
     * @param rootId         canonical root command literal
     * @param name           node name
     * @param kind           {@code "literal"} or {@code "argument"}
     * @param aliases        folded aliases (canonical first, e.g. {@code ["rtp", "betterrtp:rtp"]})
     * @param parser         Brigadier argument parser if {@code kind == "argument"}
     * @param properties     argument parser properties
     * @param executable     whether this node is executable
     * @param tiers          ordered visible tiers (e.g. {@code ["default", "op"]})
     * @param minTier        minimum reproducing tier ({@code "default"} before {@code "op"})
     * @param children       child canonical {@code nodeId}s
     * @param redirectTarget optional redirect target path
     * @param cycleCut       whether a redirect cycle was cut at this node
     * @param denied         whether this node matches the active {@link Denylist}
     * @param deniedBy       matching denylist rule pattern, or {@code null}
     * @param permission     declared permission node if known
     * @param owningPlugin   attributed plugin or JAR name if known
     * @param steps          path steps below the root node
     */
    public record CommandNode(
            String nodeId,
            String rootId,
            String name,
            String kind,
            List<String> aliases,
            String parser,
            Map<String, Object> properties,
            boolean executable,
            List<String> tiers,
            String minTier,
            List<String> children,
            String redirectTarget,
            boolean cycleCut,
            boolean denied,
            String deniedBy,
            String permission,
            String owningPlugin,
            List<SequenceStep> steps
    ) {
        public CommandNode {
            Objects.requireNonNull(nodeId, "nodeId");
            rootId = (rootId == null || rootId.isBlank()) ? nodeId.split(" ", 2)[0] : rootId;
            name = name == null ? "" : name;
            kind = (kind == null || kind.isBlank()) ? "literal" : kind;
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
            tiers = tiers == null ? List.of() : sortTiers(tiers);
            minTier = (minTier == null || minTier.isBlank()) ? minVisibleTier(tiers) : minTier;
            children = children == null ? List.of() : List.copyOf(children);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        public boolean isDefaultVisible() {
            return tiers.contains("default") || "default".equals(minTier);
        }
    }

    /**
     * Merged multi-tier command tree with folded aliases and evaluated denylist markings.
     *
     * @param tiers           active permission tiers
     * @param nodes           map of {@code nodeId -> CommandNode}
     * @param rootChildren    root canonical command IDs
     * @param aliasMap        mapping from every root alias (e.g. {@code "betterrtp:rtp"}) to its canonical root ID ({@code "rtp"})
     * @param denylistEntries active denylist pattern strings
     */
    public record CommandTree(
            List<String> tiers,
            Map<String, CommandNode> nodes,
            List<String> rootChildren,
            Map<String, String> aliasMap,
            List<String> denylistEntries
    ) {
        public CommandTree {
            tiers = tiers == null ? List.of() : sortTiers(tiers);
            nodes = nodes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
            rootChildren = rootChildren == null ? List.of() : List.copyOf(rootChildren);
            aliasMap = aliasMap == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(aliasMap));
            denylistEntries = denylistEntries == null ? List.of() : List.copyOf(denylistEntries);
        }

        /**
         * Returns all executable, non-denied command nodes eligible for exploration.
         *
         * @return list of executable allowed command nodes
         */
        public List<CommandNode> executableAllowedNodes() {
            List<CommandNode> out = new ArrayList<>();
            for (CommandNode node : nodes.values()) {
                if (node.executable() && !node.denied()) {
                    out.add(node);
                }
            }
            return out;
        }
    }

    /**
     * Parsed denylist entry pattern {@code root [subpath] [<argkind>]} ({@code ADR-125} section 2.5).
     *
     * @param raw      original pattern string
     * @param rootGlob lowercase bare root literal or prefix glob (e.g. {@code "ban*"})
     * @param subpath  lowercase child literal tokens
     * @param argKind  optional trailing {@code <argkind>} parser token
     */
    public record DenylistPattern(
            String raw,
            String rootGlob,
            List<String> subpath,
            String argKind
    ) {
        public DenylistPattern {
            Objects.requireNonNull(raw, "raw");
            Objects.requireNonNull(rootGlob, "rootGlob");
            subpath = subpath == null ? List.of() : List.copyOf(subpath);
        }

        public static DenylistPattern parse(String entry) {
            if (entry == null || entry.isBlank()) {
                throw new IllegalArgumentException("Denylist entry must not be blank");
            }
            String text = entry.trim();
            String[] tokens = text.split("\\s+");
            String rootTok = tokens[0].toLowerCase(Locale.ROOT);
            String bareRoot = splitNamespace(rootTok)[1];
            String rootGlob = bareRoot.isEmpty() ? rootTok : bareRoot;

            List<String> sub = new ArrayList<>();
            String argKind = null;
            for (int i = 1; i < tokens.length; i++) {
                String low = tokens[i].toLowerCase(Locale.ROOT);
                if (low.startsWith("<") && low.endsWith(">") && low.length() > 2) {
                    if (i != tokens.length - 1) {
                        throw new IllegalArgumentException("Denylist <argkind> must be the final token in: " + entry);
                    }
                    argKind = normalizeParserKind(low.substring(1, low.length() - 1));
                } else if (low.indexOf('<') >= 0 || low.indexOf('>') >= 0) {
                    throw new IllegalArgumentException("Malformed argument token in denylist entry: " + entry);
                } else {
                    sub.add(low);
                }
            }
            return new DenylistPattern(text, rootGlob, sub, argKind);
        }
    }

    /**
     * Evaluates {@code ADR-125} section 2.5 command path and {@code <argkind>} denylist rules.
     */
    public static final class Denylist {
        private final List<String> entries;
        private final List<DenylistPattern> patterns;

        public Denylist() {
            this(List.of());
        }

        public Denylist(Collection<String> extraEntries) {
            Set<String> merged = new LinkedHashSet<>(DEFAULT_DENYLIST);
            if (extraEntries != null) {
                for (String raw : extraEntries) {
                    if (raw == null || raw.isBlank()) {
                        continue;
                    }
                    for (String part : raw.split(",")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty()) {
                            merged.add(trimmed);
                        }
                    }
                }
            }
            this.entries = List.copyOf(merged);
            List<DenylistPattern> compiled = new ArrayList<>(this.entries.size());
            for (String e : this.entries) {
                compiled.add(DenylistPattern.parse(e));
            }
            this.patterns = Collections.unmodifiableList(compiled);
        }

        public List<String> entries() {
            return entries;
        }

        public List<DenylistPattern> patterns() {
            return patterns;
        }

        /**
         * Returns the matching denylist pattern string if the node path is denied, or {@link Optional#empty()}.
         *
         * @param rootNames  canonical root name and all aliases
         * @param tokenSteps sequence steps after the root
         * @return matching rule pattern if denied
         */
        public Optional<String> matchNode(Collection<String> rootNames, List<SequenceStep> tokenSteps) {
            Set<String> candidateRoots = new LinkedHashSet<>();
            if (rootNames != null) {
                for (String rn : rootNames) {
                    if (rn == null || rn.isBlank()) {
                        continue;
                    }
                    String low = rn.trim().toLowerCase(Locale.ROOT);
                    candidateRoots.add(low);
                    String bare = splitNamespace(low)[1];
                    if (!bare.isEmpty()) {
                        candidateRoots.add(bare);
                    }
                }
            }
            if (candidateRoots.isEmpty()) {
                return Optional.empty();
            }
            List<SequenceStep> steps = tokenSteps == null ? List.of() : tokenSteps;

            for (DenylistPattern pat : patterns) {
                if (!matchesAnyRoot(candidateRoots, pat.rootGlob())) {
                    continue;
                }
                if (pat.subpath().isEmpty() && pat.argKind() == null) {
                    return Optional.of(pat.raw());
                }
                if (steps.size() < pat.subpath().size()) {
                    continue;
                }
                boolean subOk = true;
                for (int i = 0; i < pat.subpath().size(); i++) {
                    SequenceStep step = steps.get(i);
                    if (!"literal".equals(step.kind())
                            || !step.name().toLowerCase(Locale.ROOT).equals(pat.subpath().get(i))) {
                        subOk = false;
                        break;
                    }
                }
                if (!subOk) {
                    continue;
                }
                List<SequenceStep> rem = steps.subList(pat.subpath().size(), steps.size());
                if (pat.argKind() == null) {
                    return Optional.of(pat.raw());
                }
                if (!rem.isEmpty()) {
                    SequenceStep next = rem.get(0);
                    if ("argument".equals(next.kind()) && argMatches(next.parser(), pat.argKind())) {
                        return Optional.of(pat.raw());
                    }
                }
            }
            return Optional.empty();
        }

        public boolean isDeniedNode(Collection<String> rootNames, List<SequenceStep> tokenSteps) {
            return matchNode(rootNames, tokenSteps).isPresent();
        }

        /**
         * Checks whether a raw command line (e.g. {@code "/minecraft:stop"}, {@code "kill @a"},
         * {@code "/betterrtp:rtp reload"}) is denied, resolving aliases and redirects when {@code tree} is supplied.
         *
         * @param commandLine command line with or without leading slash
         * @param tree        optional merged command tree for alias/redirect resolution
         * @return matching denylist pattern if denied
         */
        public Optional<String> matchCommandLine(String commandLine, CommandTree tree) {
            if (commandLine == null) {
                return Optional.empty();
            }
            String text = commandLine.trim();
            while (text.startsWith("/")) {
                text = text.substring(1).trim();
            }
            if (text.isEmpty()) {
                return Optional.empty();
            }
            String[] rawParts = text.split("\\s+");
            if (rawParts.length == 0) {
                return Optional.empty();
            }
            List<String> initialSeq = List.of(rawParts);
            List<List<String>> expandedSequences = new ArrayList<>();
            expandedSequences.add(initialSeq);

            if (tree != null) {
                Map<String, CommandNode> lowerNodes = new LinkedHashMap<>();
                for (Map.Entry<String, CommandNode> e : tree.nodes().entrySet()) {
                    lowerNodes.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
                }
                Map<String, String> lowerAliases = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : tree.aliasMap().entrySet()) {
                    lowerAliases.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
                }
                Set<List<String>> seenLower = new LinkedHashSet<>();
                seenLower.add(toLowerList(initialSeq));
                Deque<List<String>> queue = new ArrayDeque<>();
                queue.add(initialSeq);

                while (!queue.isEmpty() && seenLower.size() < 64) {
                    List<String> curr = queue.removeFirst();
                    if (curr.isEmpty()) {
                        continue;
                    }
                    String r0 = curr.get(0).toLowerCase(Locale.ROOT);
                    String r0Bare = splitNamespace(r0)[1];
                    String canonR0 = lowerAliases.getOrDefault(r0, lowerAliases.getOrDefault(r0Bare, r0));
                    CommandNode rootNode = lowerNodes.get(canonR0.toLowerCase(Locale.ROOT));
                    if (rootNode == null) {
                        rootNode = lowerNodes.getOrDefault(r0, lowerNodes.get(r0Bare));
                    }
                    List<CommandNode> activeNodes = new ArrayList<>();
                    if (rootNode != null) {
                        activeNodes.add(rootNode);
                    }
                    for (int prefixLen = 1; prefixLen <= curr.size(); prefixLen++) {
                        StringBuilder prefixBuilder = new StringBuilder(canonR0.toLowerCase(Locale.ROOT));
                        for (int i = 1; i < prefixLen; i++) {
                            prefixBuilder.append(' ').append(curr.get(i).toLowerCase(Locale.ROOT));
                        }
                        List<CommandNode> candidates = new ArrayList<>(activeNodes);
                        CommandNode direct = lowerNodes.get(prefixBuilder.toString());
                        if (direct != null && !candidates.contains(direct)) {
                            candidates.add(direct);
                        }
                        for (CommandNode pnode : candidates) {
                            String redir = pnode.redirectTarget();
                            if (redir == null || redir.isBlank() || redir.startsWith("<root>") || redir.startsWith("@")) {
                                continue;
                            }
                            String[] redirParts = redir.trim().split("\\s+");
                            if (redirParts.length == 0) {
                                continue;
                            }
                            List<String> newSeq = new ArrayList<>(redirParts.length + curr.size() - prefixLen);
                            Collections.addAll(newSeq, redirParts);
                            newSeq.addAll(curr.subList(prefixLen, curr.size()));
                            List<String> newKey = toLowerList(newSeq);
                            if (seenLower.add(newKey)) {
                                expandedSequences.add(newSeq);
                                queue.addLast(newSeq);
                            }
                        }
                        if (prefixLen < curr.size() && !activeNodes.isEmpty()) {
                            String nextTok = curr.get(prefixLen).toLowerCase(Locale.ROOT);
                            List<CommandNode> nextActive = new ArrayList<>();
                            for (CommandNode pnode : activeNodes) {
                                for (String cid : pnode.children()) {
                                    CommandNode ch = tree.nodes().get(cid);
                                    if (ch == null) {
                                        continue;
                                    }
                                    if (("literal".equals(ch.kind()) && ch.name().toLowerCase(Locale.ROOT).equals(nextTok))
                                            || "argument".equals(ch.kind())) {
                                        if (!nextActive.contains(ch)) {
                                            nextActive.add(ch);
                                        }
                                    }
                                }
                            }
                            activeNodes = nextActive;
                        }
                    }
                }
            }

            for (List<String> seq : expandedSequences) {
                if (seq.isEmpty()) {
                    continue;
                }
                String rootTok = seq.get(0).toLowerCase(Locale.ROOT);
                String bareRoot = splitNamespace(rootTok)[1];
                Set<String> rootCandidates = new LinkedHashSet<>();
                rootCandidates.add(rootTok);
                rootCandidates.add(bareRoot);

                if (tree != null) {
                    String aliasTarget = tree.aliasMap().get(rootTok);
                    if (aliasTarget == null) {
                        aliasTarget = tree.aliasMap().get(bareRoot);
                    }
                    if (aliasTarget == null) {
                        for (Map.Entry<String, String> e : tree.aliasMap().entrySet()) {
                            String kLow = e.getKey().toLowerCase(Locale.ROOT);
                            if (kLow.equals(rootTok) || kLow.equals(bareRoot)) {
                                aliasTarget = e.getValue();
                                break;
                            }
                        }
                    }
                    if (aliasTarget != null) {
                        String atLow = aliasTarget.toLowerCase(Locale.ROOT);
                        rootCandidates.add(atLow);
                        rootCandidates.add(splitNamespace(atLow)[1]);
                        CommandNode rn = tree.nodes().get(aliasTarget);
                        if (rn != null) {
                            for (String a : rn.aliases()) {
                                String al = a.toLowerCase(Locale.ROOT);
                                rootCandidates.add(al);
                                rootCandidates.add(splitNamespace(al)[1]);
                            }
                        }
                    }
                }

                List<String> rest = seq.subList(1, seq.size());
                for (DenylistPattern pat : patterns) {
                    if (!matchesAnyRoot(rootCandidates, pat.rootGlob())) {
                        continue;
                    }
                    if (pat.subpath().isEmpty() && pat.argKind() == null) {
                        return Optional.of(pat.raw());
                    }
                    if (rest.size() < pat.subpath().size()) {
                        continue;
                    }
                    boolean subOk = true;
                    for (int i = 0; i < pat.subpath().size(); i++) {
                        if (!rest.get(i).toLowerCase(Locale.ROOT).equals(pat.subpath().get(i))) {
                            subOk = false;
                            break;
                        }
                    }
                    if (!subOk) {
                        continue;
                    }
                    List<String> afterSub = rest.subList(pat.subpath().size(), rest.size());
                    if (pat.argKind() == null) {
                        return Optional.of(pat.raw());
                    }
                    if (!afterSub.isEmpty()) {
                        return Optional.of(pat.raw());
                    }
                }
            }
            return Optional.empty();
        }

        public boolean isDeniedCommandLine(String commandLine, CommandTree tree) {
            return matchCommandLine(commandLine, tree).isPresent();
        }

        private static List<String> toLowerList(List<String> items) {
            List<String> out = new ArrayList<>(items.size());
            for (String s : items) {
                out.add(s.toLowerCase(Locale.ROOT));
            }
            return out;
        }

        private static boolean matchesAnyRoot(Set<String> candidates, String rootGlob) {
            for (String r : candidates) {
                if (globMatch(r, rootGlob) || globMatch(splitNamespace(r)[1], rootGlob)) {
                    return true;
                }
            }
            return false;
        }

        static boolean argMatches(String parser, String patternArgKind) {
            String norm = normalizeParserKind(parser);
            if (norm.equals(patternArgKind)) {
                return true;
            }
            return "entity".equals(patternArgKind)
                    && ("entity".equals(norm) || "game_profile".equals(norm) || "score_holder".equals(norm));
        }
    }

    /**
     * Least-privilege finding emitted when an administrative, denylisted, or state-mutating
     * command node is exposed on the unprivileged {@code default} permission tier.
     *
     * @param nodeId             canonical command node ID
     * @param aliases            all discovered aliases for this command node
     * @param findingKind        {@code "DENYLIST_EXPOSED_TO_DEFAULT"}, {@code "STATE_MUTATING_DEFAULT_EXPOSURE"},
     *                           or {@code "MISSING_PERMISSION_GUARD"}
     * @param severity           {@code "CRITICAL"}, {@code "HIGH"}, or {@code "MEDIUM"}
     * @param suspectPluginOrJar attributed plugin/JAR name (or {@code "server"} if vanilla/unknown)
     * @param declaredPermission declared permission node if any (or {@code "<none>"})
     * @param rationale          concise explanation of why the exposure violates least privilege
     * @param remediationCommand recommended LuckPerms or {@code commands.yml} remediation snippet
     */
    public record LeastPrivilegeFinding(
            String nodeId,
            List<String> aliases,
            String findingKind,
            String severity,
            String suspectPluginOrJar,
            String declaredPermission,
            String rationale,
            String remediationCommand
    ) {
        public LeastPrivilegeFinding {
            Objects.requireNonNull(nodeId, "nodeId");
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            findingKind = findingKind == null ? "STATE_MUTATING_DEFAULT_EXPOSURE" : findingKind;
            severity = severity == null ? "HIGH" : severity;
            suspectPluginOrJar = (suspectPluginOrJar == null || suspectPluginOrJar.isBlank()) ? "server" : suspectPluginOrJar;
            declaredPermission = (declaredPermission == null || declaredPermission.isBlank()) ? "<none>" : declaredPermission;
            rationale = rationale == null ? "" : rationale;
            remediationCommand = remediationCommand == null ? "" : remediationCommand;
        }
    }

    /**
     * Summary report produced by {@link #auditCommandSurface(Map, Collection, JarClassOwnershipIndex)}.
     *
     * @param commandTree        merged canonical command tree
     * @param totalNodes         total canonical nodes in the merged tree
     * @param executableNodes    total executable nodes across all tiers
     * @param defaultTierNodes   executable nodes visible to the unprivileged {@code default} tier
     * @param opOnlyNodes        executable nodes restricted to the {@code op} tier
     * @param foldedAliasCount   number of {@code namespace:cmd} aliases folded into canonical roots
     * @param deniedNodeCount    number of nodes blocked by {@link Denylist}
     * @param findings           least-privilege violations ranked by severity
     */
    public record CommandAuditReport(
            CommandTree commandTree,
            int totalNodes,
            int executableNodes,
            int defaultTierNodes,
            int opOnlyNodes,
            int foldedAliasCount,
            int deniedNodeCount,
            List<LeastPrivilegeFinding> findings
    ) {
        public CommandAuditReport {
            Objects.requireNonNull(commandTree, "commandTree");
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }

    /**
     * Evaluates a merged {@link CommandTree} for least-privilege violations across {@code default}
     * vs {@code op} permission tiers.
     */
    public static final class LeastPrivilegeOracle {
        private final Denylist denylist;

        public LeastPrivilegeOracle() {
            this(new Denylist());
        }

        public LeastPrivilegeOracle(Denylist denylist) {
            this.denylist = Objects.requireNonNull(denylist, "denylist");
        }

        /**
         * Audits all nodes in {@code tree} and flags administrative, denylisted, or state-mutating
         * commands reachable by the {@code default} permission tier.
         *
         * @param tree           merged command tree
         * @param ownershipIndex optional JAR ownership index for plugin attribution
         * @return ranked list of least-privilege findings
         */
        public List<LeastPrivilegeFinding> audit(CommandTree tree, JarClassOwnershipIndex ownershipIndex) {
            if (tree == null || tree.nodes().isEmpty()) {
                return List.of();
            }
            List<LeastPrivilegeFinding> findings = new ArrayList<>();
            for (CommandNode node : tree.nodes().values()) {
                if (!node.executable() || !node.isDefaultVisible()) {
                    continue;
                }
                String suspect = resolveOwner(node, ownershipIndex);
                String perm = (node.permission() == null || node.permission().isBlank()) ? "<none>" : node.permission();

                // 1. Denylisted administrative / server-control command exposed to default tier
                Optional<String> denyRule = node.denied()
                        ? Optional.ofNullable(node.deniedBy())
                        : denylist.matchNode(node.aliases().isEmpty() ? List.of(node.rootId()) : node.aliases(), node.steps());
                if (denyRule.isPresent()) {
                    String rule = denyRule.get();
                    String revokeTarget = ! "<none>".equals(perm)
                            ? perm
                            : (node.rootId() + ".*");
                    findings.add(new LeastPrivilegeFinding(
                            node.nodeId(),
                            node.aliases(),
                            "DENYLIST_EXPOSED_TO_DEFAULT",
                            "CRITICAL",
                            suspect,
                            perm,
                            "Denylisted administrative command '" + node.nodeId()
                                    + "' (matching '" + rule + "') is executable by default-tier players.",
                            "lp group default permission set " + revokeTarget + " false"
                    ));
                    continue;
                }

                // 2. State-mutating subcommand or root command exposed on default tier
                Optional<String> mutatingToken = findStateMutatingToken(node);
                if (mutatingToken.isPresent()) {
                    String tok = mutatingToken.get();
                    if (perm.startsWith("<unknown")) {
                        findings.add(new LeastPrivilegeFinding(
                                node.nodeId(),
                                node.aliases(),
                                "UNKNOWN_PERMISSION_GUARD",
                                "MEDIUM",
                                suspect,
                                perm,
                                "State-mutating command token '" + tok + "' on '/" + node.nodeId()
                                        + "' has an opaque Brigadier requirement predicate (" + perm
                                        + ") that could not be evaluated in-JVM.",
                                "lp group default permission set " + node.rootId() + "." + tok + " false"
                        ));
                        continue;
                    }
                    boolean unguarded = "<none>".equals(perm);
                    String kind = unguarded ? "MISSING_PERMISSION_GUARD" : "STATE_MUTATING_DEFAULT_EXPOSURE";
                    String severity = unguarded ? "CRITICAL" : "HIGH";
                    String revokePerm = unguarded ? (node.rootId() + "." + tok) : perm;
                    findings.add(new LeastPrivilegeFinding(
                            node.nodeId(),
                            node.aliases(),
                            kind,
                            severity,
                            suspect,
                            perm,
                            "State-mutating command token '" + tok + "' on '/" + node.nodeId()
                                    + "' is reachable by default-tier players (permission=" + perm + ").",
                            "lp group default permission set " + revokePerm + " false"
                    ));
                }
            }

            findings.sort(Comparator
                    .comparingInt((LeastPrivilegeFinding f) -> severityRank(f.severity()))
                    .thenComparing(LeastPrivilegeFinding::nodeId));
            return findings;
        }

        private static Optional<String> findStateMutatingToken(CommandNode node) {
            String bareRoot = splitNamespace(node.rootId().toLowerCase(Locale.ROOT))[1];
            if (STATE_MUTATING_LITERALS.contains(bareRoot)) {
                return Optional.of(bareRoot);
            }
            for (SequenceStep step : node.steps()) {
                if ("literal".equals(step.kind())) {
                    String low = step.name().toLowerCase(Locale.ROOT);
                    if (STATE_MUTATING_LITERALS.contains(low)) {
                        return Optional.of(low);
                    }
                }
            }
            return Optional.empty();
        }

        private static String resolveOwner(CommandNode node, JarClassOwnershipIndex ownershipIndex) {
            if (node.owningPlugin() != null && !node.owningPlugin().isBlank()) {
                String rawOwner = node.owningPlugin();
                if (ownershipIndex != null) {
                    for (JarClassOwnershipIndex.JarDescriptor desc : ownershipIndex.descriptors().values()) {
                        if (desc.pluginId().equalsIgnoreCase(rawOwner) || desc.jarName().equalsIgnoreCase(rawOwner)) {
                            return desc.jarName();
                        }
                    }
                    Optional<String> byClass = ownershipIndex.ownerJarForClass(rawOwner);
                    if (byClass.isPresent()) {
                        return byClass.get();
                    }
                }
                return rawOwner;
            }
            for (String alias : node.aliases()) {
                String[] parts = splitNamespace(alias.toLowerCase(Locale.ROOT));
                String ns = parts[0];
                if (!ns.isEmpty() && !"minecraft".equals(ns) && !"bukkit".equals(ns) && !"spigot".equals(ns) && !"paper".equals(ns)) {
                    if (ownershipIndex != null) {
                        for (JarClassOwnershipIndex.JarDescriptor desc : ownershipIndex.descriptors().values()) {
                            if (desc.pluginId().equalsIgnoreCase(ns)
                                    || desc.jarName().toLowerCase(Locale.ROOT).startsWith(ns)) {
                                return desc.jarName();
                            }
                        }
                    }
                    return ns;
                }
            }
            return "server";
        }

        private static int severityRank(String severity) {
            if ("CRITICAL".equalsIgnoreCase(severity)) {
                return 0;
            }
            if ("HIGH".equalsIgnoreCase(severity)) {
                return 1;
            }
            return 2;
        }
    }

    /**
     * Merges per-tier raw command roots into a canonical {@link CommandTree}, folding structurally
     * equivalent {@code namespace:cmd} aliases, cutting redirect cycles, and applying {@link Denylist}.
     *
     * @param tierRoots    map from tier name (e.g. {@code "default"}, {@code "op"}) to top-level raw command nodes
     * @param extraDeny    optional additional denylist patterns
     * @return merged command tree
     */
    public static CommandTree mergeCommandTrees(
            Map<String, List<RawCommandNode>> tierRoots,
            Collection<String> extraDeny
    ) {
        Denylist deny = new Denylist(extraDeny);
        if (tierRoots == null || tierRoots.isEmpty()) {
            return new CommandTree(List.of(), Map.of(), List.of(), Map.of(), deny.entries());
        }
        List<String> activeTiers = sortTiers(tierRoots.keySet());
        Map<String, Map<String, ResolvedSubtree>> perTierRoots = new LinkedHashMap<>();
        for (String tier : activeTiers) {
            List<RawCommandNode> rawList = tierRoots.getOrDefault(tier, List.of());
            perTierRoots.put(tier, resolveTierGraph(rawList));
        }

        Set<String> allRootNames = new TreeSet<>();
        for (Map<String, ResolvedSubtree> rmap : perTierRoots.values()) {
            allRootNames.addAll(rmap.keySet());
        }

        // Fold `namespace:bare` into `bare` when structurally equivalent across tiers (ADR-125 2.3)
        Map<String, String> foldToBare = new LinkedHashMap<>();
        for (String rname : allRootNames) {
            String[] nsBare = splitNamespace(rname);
            String ns = nsBare[0];
            String bare = nsBare[1];
            if (ns.isEmpty() || !allRootNames.contains(bare)) {
                continue;
            }
            Map<String, ResolvedSubtree> bareTiers = new LinkedHashMap<>();
            Map<String, ResolvedSubtree> nsTiers = new LinkedHashMap<>();
            List<String> sharedTiers = new ArrayList<>();
            for (String t : activeTiers) {
                Map<String, ResolvedSubtree> tmap = perTierRoots.get(t);
                if (tmap.containsKey(bare)) {
                    bareTiers.put(t, tmap.get(bare));
                }
                if (tmap.containsKey(rname)) {
                    nsTiers.put(t, tmap.get(rname));
                }
                if (bareTiers.containsKey(t) && nsTiers.containsKey(t)) {
                    sharedTiers.add(t);
                }
            }
            if (sharedTiers.isEmpty()) {
                continue;
            }
            boolean conflict = false;
            for (String t : sharedTiers) {
                ResolvedSubtree nsSub = nsTiers.get(t);
                ResolvedSubtree bareSub = bareTiers.get(t);
                boolean redirCompatible = Objects.equals(nsSub.redirectTargetPath, bareSub.redirectTargetPath)
                        || (bare.equals(nsSub.redirectTargetPath) && bareSub.redirectTargetPath == null && !nsSub.hasOwnChildren)
                        || (rname.equals(bareSub.redirectTargetPath) && nsSub.redirectTargetPath == null && !bareSub.hasOwnChildren);
                if (!redirCompatible || !nsSub.structuralSignature().equals(bareSub.structuralSignature())) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                continue;
            }
            Set<String> bareSigs = new LinkedHashSet<>();
            for (ResolvedSubtree sub : bareTiers.values()) {
                bareSigs.add(sub.structuralSignature());
            }
            Set<String> nsSigs = new LinkedHashSet<>();
            for (ResolvedSubtree sub : nsTiers.values()) {
                nsSigs.add(sub.structuralSignature());
            }
            for (Map.Entry<String, ResolvedSubtree> e : nsTiers.entrySet()) {
                if (bareTiers.containsKey(e.getKey())) {
                    continue;
                }
                ResolvedSubtree nsSub = e.getValue();
                if ((bare.equals(nsSub.redirectTargetPath) && nsSub.hasOwnChildren)
                        || !bareSigs.contains(nsSub.structuralSignature())) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                continue;
            }
            for (Map.Entry<String, ResolvedSubtree> e : bareTiers.entrySet()) {
                if (nsTiers.containsKey(e.getKey())) {
                    continue;
                }
                ResolvedSubtree bareSub = e.getValue();
                if ((rname.equals(bareSub.redirectTargetPath) && bareSub.hasOwnChildren)
                        || !nsSigs.contains(bareSub.structuralSignature())) {
                    conflict = true;
                    break;
                }
            }
            if (!conflict) {
                foldToBare.put(rname, bare);
            }
        }

        Map<String, Map<String, ResolvedSubtree>> canonicalRoots = new TreeMap<>();
        Map<String, List<String>> rootAliases = new LinkedHashMap<>();

        for (String tier : activeTiers) {
            Map<String, ResolvedSubtree> rmap = perTierRoots.get(tier);
            for (Map.Entry<String, ResolvedSubtree> entry : new TreeMap<>(rmap).entrySet()) {
                String rname = entry.getKey();
                ResolvedSubtree sub = entry.getValue();
                String canon = foldToBare.getOrDefault(rname, rname);
                List<String> aliases = rootAliases.computeIfAbsent(canon, k -> new ArrayList<>());
                if (!aliases.contains(canon)) {
                    aliases.add(canon);
                }
                if (!aliases.contains(rname)) {
                    aliases.add(rname);
                }
                Map<String, ResolvedSubtree> tierMap = canonicalRoots.computeIfAbsent(canon, k -> new LinkedHashMap<>());
                if (tierMap.containsKey(tier)) {
                    tierMap.put(tier, mergeTwoSubtrees(tierMap.get(tier), sub));
                } else {
                    tierMap.put(tier, sub);
                }
            }
        }

        Map<String, CommandNode> builtNodes = new LinkedHashMap<>();
        List<String> rootChildren = new ArrayList<>();
        Map<String, String> aliasMap = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, ResolvedSubtree>> entry : canonicalRoots.entrySet()) {
            String canon = entry.getKey();
            List<String> rawAliases = rootAliases.getOrDefault(canon, List.of(canon));
            List<String> orderedAliases = new ArrayList<>();
            orderedAliases.add(canon);
            rawAliases.stream().filter(a -> !a.equals(canon)).sorted().forEach(orderedAliases::add);
            for (String a : orderedAliases) {
                aliasMap.put(a, canon);
            }

            Map<String, ResolvedSubtree> tierSubtrees = entry.getValue();
            ResolvedSubtree mergedRoot = null;
            for (String t : activeTiers) {
                ResolvedSubtree ts = tierSubtrees.get(t);
                if (ts != null) {
                    mergedRoot = (mergedRoot == null) ? ts : mergeTwoSubtrees(mergedRoot, ts);
                }
            }
            if (mergedRoot == null) {
                continue;
            }
            flattenSubtree(
                    canon,
                    canon,
                    mergedRoot,
                    tierSubtrees,
                    orderedAliases,
                    List.of(),
                    true,
                    builtNodes
            );
            if (builtNodes.containsKey(canon)) {
                rootChildren.add(canon);
            }
        }

        // Evaluate Denylist across all nodes, including redirect target closures
        Map<String, CommandNode> finalNodes = applyDenylist(builtNodes, aliasMap, deny);
        return new CommandTree(activeTiers, finalNodes, rootChildren, aliasMap, deny.entries());
    }

    /**
     * White-box command schema hint extracted from plugin bytecode ({@code BytecodeCausalGraphScanner})
     * to enrich black-box command graphs with subcommand literals, typed argument parsers, and
     * inline permission guards when {@code onTabComplete} or Brigadier trees are absent.
     *
     * @param rootCommand        canonical root command name (e.g. {@code "wild"}, {@code "rtp"})
     * @param subcommandLiterals literal subcommand branch tokens (e.g. {@code ["confirm", "reload"]})
     * @param argumentParsers    inferred Brigadier parser kinds (e.g. {@code ["brigadier:integer", "minecraft:dimension"]})
     * @param inlinePermissions  inline {@code hasPermission("...")} or {@code @CommandPermission} strings
     * @param owningPlugin       owning plugin or JAR identifier
     */
    public record WhiteBoxCommandHint(
            String rootCommand,
            List<String> subcommandLiterals,
            List<String> argumentParsers,
            List<String> inlinePermissions,
            String owningPlugin
    ) {
        public WhiteBoxCommandHint {
            rootCommand = (rootCommand == null || rootCommand.isBlank())
                    ? ""
                    : splitNamespace(rootCommand.trim().toLowerCase(Locale.ROOT))[1];
            subcommandLiterals = subcommandLiterals == null ? List.of() : List.copyOf(subcommandLiterals);
            argumentParsers = argumentParsers == null ? List.of() : List.copyOf(argumentParsers);
            inlinePermissions = inlinePermissions == null ? List.of() : List.copyOf(inlinePermissions);
            owningPlugin = (owningPlugin == null || owningPlugin.isBlank()) ? null : owningPlugin.trim();
        }
    }

    /**
     * Augments per-tier raw command trees with white-box bytecode hints (subcommand branch literals,
     * typed argument parsers, and inline {@code hasPermission} guards) so Stage 1 audits undocumented
     * subcommand exposure and Stage 2 fuzzes deep branches even when {@code onTabComplete} is missing.
     *
     * @param tierRoots per-tier raw command trees
     * @param hints     white-box command hints extracted from bytecode
     * @return augmented per-tier raw command trees
     */
    public static Map<String, List<RawCommandNode>> augmentTierGraphsWithWhiteBoxHints(
            Map<String, List<RawCommandNode>> tierRoots,
            Collection<WhiteBoxCommandHint> hints
    ) {
        if (tierRoots == null || tierRoots.isEmpty() || hints == null || hints.isEmpty()) {
            return tierRoots == null ? Map.of() : tierRoots;
        }
        Map<String, List<WhiteBoxCommandHint>> hintsByRoot = new LinkedHashMap<>();
        for (WhiteBoxCommandHint h : hints) {
            if (h != null && !h.rootCommand().isEmpty()) {
                hintsByRoot.computeIfAbsent(h.rootCommand(), k -> new ArrayList<>()).add(h);
            }
        }
        if (hintsByRoot.isEmpty()) {
            return tierRoots;
        }

        Map<String, List<RawCommandNode>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<RawCommandNode>> tierEntry : tierRoots.entrySet()) {
            String tier = tierEntry.getKey();
            List<RawCommandNode> rawNodes = tierEntry.getValue();
            if (rawNodes == null || rawNodes.isEmpty()) {
                out.put(tier, List.of());
                continue;
            }
            List<RawCommandNode> augmentedRoots = new ArrayList<>(rawNodes.size());
            for (RawCommandNode r : rawNodes) {
                if (r == null || r.name().isEmpty()) {
                    continue;
                }
                String bareRoot = splitNamespace(r.name().toLowerCase(Locale.ROOT))[1];
                List<WhiteBoxCommandHint> matchedHints = hintsByRoot.get(bareRoot);
                if (matchedHints == null || matchedHints.isEmpty()) {
                    augmentedRoots.add(r);
                    continue;
                }
                augmentedRoots.add(augmentSingleRootNode(r, tier, matchedHints));
            }
            out.put(tier, Collections.unmodifiableList(augmentedRoots));
        }
        return Collections.unmodifiableMap(out);
    }

    private static RawCommandNode augmentSingleRootNode(
            RawCommandNode root,
            String tier,
            List<WhiteBoxCommandHint> matchedHints
    ) {
        List<RawCommandNode> mergedChildren = new ArrayList<>(root.children());
        Set<String> existingChildNames = new LinkedHashSet<>();
        boolean hasTypedArgChild = false;
        for (RawCommandNode c : root.children()) {
            existingChildNames.add(c.name().toLowerCase(Locale.ROOT));
            if ("argument".equals(c.kind()) && c.parser() != null && !c.parser().endsWith("string")) {
                hasTypedArgChild = true;
            }
        }

        String rootPerm = root.permission();
        String rootOwner = root.owningPlugin();

        for (WhiteBoxCommandHint hint : matchedHints) {
            if (rootOwner == null && hint.owningPlugin() != null) {
                rootOwner = hint.owningPlugin();
            }
            boolean hasInlinePerm = !hint.inlinePermissions().isEmpty();
            String effPerm = hasInlinePerm ? hint.inlinePermissions().get(0) : rootPerm;
            if (rootPerm == null && hasInlinePerm && hint.subcommandLiterals().isEmpty()) {
                rootPerm = effPerm;
            }

            List<RawCommandNode> syntheticArgLeaf = List.of();
            if (!hint.argumentParsers().isEmpty()) {
                String parser = hint.argumentParsers().get(0);
                syntheticArgLeaf = List.of(RawCommandNode.argument(
                        "wb_arg0",
                        parser,
                        Map.of(),
                        true,
                        List.of()
                ));
            }

            for (String subLit : hint.subcommandLiterals()) {
                if (subLit == null || subLit.isBlank()) {
                    continue;
                }
                String subClean = subLit.trim().toLowerCase(Locale.ROOT);
                if (existingChildNames.contains(subClean)) {
                    continue;
                }
                // If bytecode explicitly guards this branch with hasPermission(...) or @CommandPermission,
                // do not expose state-mutating literals on the unprivileged default tier
                if ("default".equalsIgnoreCase(tier)
                        && hasInlinePerm
                        && STATE_MUTATING_LITERALS.contains(subClean)) {
                    continue;
                }
                existingChildNames.add(subClean);
                mergedChildren.add(new RawCommandNode(
                        subClean,
                        "literal",
                        null,
                        Map.of(),
                        true,
                        null,
                        effPerm,
                        rootOwner,
                        syntheticArgLeaf
                ));
            }

            if (!hasTypedArgChild && !syntheticArgLeaf.isEmpty() && !existingChildNames.contains("wb_arg0")) {
                existingChildNames.add("wb_arg0");
                hasTypedArgChild = true;
                mergedChildren.add(syntheticArgLeaf.get(0));
            }
        }

        return new RawCommandNode(
                root.name(),
                root.kind(),
                root.parser(),
                root.properties(),
                root.executable(),
                root.redirectTarget(),
                rootPerm,
                rootOwner,
                mergedChildren
        );
    }

    /**
     * Performs a complete command-surface audit (merging per-tier trees, folding aliases, applying
     * {@link Denylist}, and running {@link LeastPrivilegeOracle}).
     *
     * @param tierRoots      per-tier raw command trees
     * @param extraDeny      optional additional denylist rules
     * @param ownershipIndex optional JAR ownership index
     * @return immutable command audit report
     */
    public static CommandAuditReport auditCommandSurface(
            Map<String, List<RawCommandNode>> tierRoots,
            Collection<String> extraDeny,
            JarClassOwnershipIndex ownershipIndex
    ) {
        return auditCommandSurface(tierRoots, extraDeny, ownershipIndex, List.of());
    }

    /**
     * Performs a complete command-surface audit augmented with optional white-box bytecode hints.
     *
     * @param tierRoots      per-tier raw command trees
     * @param extraDeny      optional additional denylist rules
     * @param ownershipIndex optional JAR ownership index
     * @param whiteBoxHints  optional bytecode-extracted command schemas
     * @return immutable command audit report
     */
    public static CommandAuditReport auditCommandSurface(
            Map<String, List<RawCommandNode>> tierRoots,
            Collection<String> extraDeny,
            JarClassOwnershipIndex ownershipIndex,
            Collection<WhiteBoxCommandHint> whiteBoxHints
    ) {
        Map<String, List<RawCommandNode>> augmented = augmentTierGraphsWithWhiteBoxHints(tierRoots, whiteBoxHints);
        CommandTree tree = mergeCommandTrees(augmented, extraDeny);
        LeastPrivilegeOracle oracle = new LeastPrivilegeOracle(new Denylist(extraDeny));
        List<LeastPrivilegeFinding> findings = oracle.audit(tree, ownershipIndex);

        int totalNodes = tree.nodes().size();
        int executableNodes = 0;
        int defaultTierNodes = 0;
        int opOnlyNodes = 0;
        int deniedNodes = 0;
        for (CommandNode node : tree.nodes().values()) {
            if (node.denied()) {
                deniedNodes++;
            }
            if (node.executable()) {
                executableNodes++;
                if (node.isDefaultVisible()) {
                    defaultTierNodes++;
                } else {
                    opOnlyNodes++;
                }
            }
        }
        int foldedAliases = Math.max(0, tree.aliasMap().size() - tree.rootChildren().size());
        return new CommandAuditReport(
                tree,
                totalNodes,
                executableNodes,
                defaultTierNodes,
                opOnlyNodes,
                foldedAliases,
                deniedNodes,
                findings
        );
    }

    private static void flattenSubtree(
            String canonRoot,
            String nodeId,
            ResolvedSubtree mergedSub,
            Map<String, ResolvedSubtree> tierViews,
            List<String> orderedRootAliases,
            List<SequenceStep> steps,
            boolean isRoot,
            Map<String, CommandNode> outNodes
    ) {
        List<String> visibleTiers = sortTiers(tierViews.keySet());
        String minTier = minVisibleTier(visibleTiers);
        List<String> nodeAliases;
        if (isRoot) {
            nodeAliases = List.copyOf(orderedRootAliases);
        } else {
            String suffix = nodeId.contains(" ") ? nodeId.substring(nodeId.indexOf(' ') + 1) : "";
            List<String> computed = new ArrayList<>(orderedRootAliases.size());
            for (String a : orderedRootAliases) {
                computed.add(suffix.isEmpty() ? a : (a + " " + suffix));
            }
            nodeAliases = Collections.unmodifiableList(computed);
        }

        List<String> sortedChildTokens = new ArrayList<>(mergedSub.children.keySet());
        Collections.sort(sortedChildTokens);
        List<String> childIds = new ArrayList<>(sortedChildTokens.size());
        for (String tok : sortedChildTokens) {
            childIds.add(nodeId + " " + tok);
        }

        CommandNode cnode = new CommandNode(
                nodeId,
                canonRoot,
                isRoot ? canonRoot : mergedSub.name,
                mergedSub.kind,
                nodeAliases,
                mergedSub.parser,
                mergedSub.properties,
                mergedSub.executable,
                visibleTiers,
                minTier,
                childIds,
                mergedSub.redirectTargetPath,
                mergedSub.cycleCut,
                false,
                null,
                mergedSub.permission,
                mergedSub.owningPlugin,
                steps
        );
        outNodes.put(nodeId, cnode);

        for (String tok : sortedChildTokens) {
            ResolvedSubtree childSub = mergedSub.children.get(tok);
            String childId = nodeId + " " + tok;
            Map<String, ResolvedSubtree> childTierViews = new LinkedHashMap<>();
            for (Map.Entry<String, ResolvedSubtree> tv : tierViews.entrySet()) {
                for (ResolvedSubtree tvChild : tv.getValue().children.values()) {
                    if (Objects.equals(tvChild.name, childSub.name)
                            && Objects.equals(tvChild.kind, childSub.kind)
                            && Objects.equals(tvChild.parser, childSub.parser)
                            && Objects.equals(tvChild.properties, childSub.properties)) {
                        childTierViews.put(tv.getKey(), tvChild);
                        break;
                    }
                }
            }
            List<SequenceStep> nextSteps = new ArrayList<>(steps.size() + 1);
            nextSteps.addAll(steps);
            nextSteps.add(childSub.step());
            flattenSubtree(
                    canonRoot,
                    childId,
                    childSub,
                    childTierViews,
                    orderedRootAliases,
                    nextSteps,
                    false,
                    outNodes
            );
        }
    }

    private static Map<String, CommandNode> applyDenylist(
            Map<String, CommandNode> nodes,
            Map<String, String> aliasMap,
            Denylist deny
    ) {
        Map<String, String> parentOf = new LinkedHashMap<>();
        for (Map.Entry<String, CommandNode> e : nodes.entrySet()) {
            for (String cid : e.getValue().children()) {
                parentOf.putIfAbsent(cid, e.getKey());
            }
        }

        Map<String, CommandNode> updated = new LinkedHashMap<>();
        for (Map.Entry<String, CommandNode> entry : nodes.entrySet()) {
            String nid = entry.getKey();
            CommandNode node = entry.getValue();
            CommandNode rootNode = nodes.get(node.rootId());
            List<String> initRoots = (rootNode != null && !rootNode.aliases().isEmpty())
                    ? rootNode.aliases()
                    : List.of(node.rootId());

            String matchedRule = deny.matchNode(initRoots, node.steps()).orElse(null);
            if (matchedRule == null) {
                // Check ancestor redirect targets
                List<CommandNode> ancestors = ancestorChain(nid, nodes, parentOf);
                for (CommandNode anc : ancestors) {
                    if (anc.redirectTarget() == null || anc.redirectTarget().isBlank()) {
                        continue;
                    }
                    String[] tParts = anc.redirectTarget().trim().split("\\s+");
                    if (tParts.length == 0) {
                        continue;
                    }
                    String tRoot = tParts[0];
                    String tBare = splitNamespace(tRoot.toLowerCase(Locale.ROOT))[1];
                    String canonRoot = aliasMap.getOrDefault(
                            tRoot,
                            aliasMap.getOrDefault(tRoot.toLowerCase(Locale.ROOT), aliasMap.getOrDefault(tBare, tRoot))
                    );
                    List<String> redirRoots = new ArrayList<>();
                    redirRoots.add(canonRoot);
                    if (!redirRoots.contains(tRoot)) {
                        redirRoots.add(tRoot);
                    }
                    List<SequenceStep> redirSteps = new ArrayList<>();
                    for (int i = 1; i < tParts.length; i++) {
                        String tok = tParts[i];
                        if (tok.startsWith("<") && tok.endsWith(">") && tok.length() > 2) {
                            redirSteps.add(new SequenceStep("argument", tok.substring(1, tok.length() - 1), null));
                        } else {
                            redirSteps.add(new SequenceStep("literal", tok, null));
                        }
                    }
                    if (node.steps().size() > anc.steps().size()) {
                        redirSteps.addAll(node.steps().subList(anc.steps().size(), node.steps().size()));
                    }
                    Optional<String> rMatch = deny.matchNode(redirRoots, redirSteps);
                    if (rMatch.isPresent()) {
                        matchedRule = rMatch.get();
                        break;
                    }
                }
            }

            updated.put(nid, new CommandNode(
                    node.nodeId(),
                    node.rootId(),
                    node.name(),
                    node.kind(),
                    node.aliases(),
                    node.parser(),
                    node.properties(),
                    node.executable(),
                    node.tiers(),
                    node.minTier(),
                    node.children(),
                    node.redirectTarget(),
                    node.cycleCut(),
                    matchedRule != null,
                    matchedRule,
                    node.permission(),
                    node.owningPlugin(),
                    node.steps()
            ));
        }
        return updated;
    }

    private static List<CommandNode> ancestorChain(
            String nid,
            Map<String, CommandNode> nodes,
            Map<String, String> parentOf
    ) {
        List<CommandNode> chain = new ArrayList<>();
        String cur = nid;
        Set<String> seen = new LinkedHashSet<>();
        while (cur != null && nodes.containsKey(cur) && seen.add(cur)) {
            chain.add(nodes.get(cur));
            cur = parentOf.get(cur);
        }
        Collections.reverse(chain);
        return chain;
    }

    private static Map<String, ResolvedSubtree> resolveTierGraph(List<RawCommandNode> rawRoots) {
        if (rawRoots == null || rawRoots.isEmpty()) {
            return Map.of();
        }
        Map<String, RawCommandNode> rootByName = new LinkedHashMap<>();
        for (RawCommandNode r : rawRoots) {
            if (r != null && !r.name().isEmpty()) {
                rootByName.put(r.name(), r);
            }
        }
        Map<String, ResolvedSubtree> resolved = new LinkedHashMap<>();
        for (RawCommandNode r : rawRoots) {
            if (r == null || r.name().isEmpty()) {
                continue;
            }
            Set<String> pathVisited = new LinkedHashSet<>();
            pathVisited.add(r.name());
            ResolvedSubtree sub = buildResolvedSubtree(r, rootByName, pathVisited, 1);
            if (sub != null) {
                resolved.put(sub.name, sub);
            }
        }
        return resolved;
    }

    private static ResolvedSubtree buildResolvedSubtree(
            RawCommandNode raw,
            Map<String, RawCommandNode> rootByName,
            Set<String> visitedRedirects,
            int depth
    ) {
        if (raw == null || depth > MAX_REDIRECT_DEPTH) {
            return null;
        }
        boolean hasOwn = !raw.children().isEmpty();
        List<RawCommandNode> effChildren = new ArrayList<>(raw.children());
        boolean effExec = raw.executable();
        String redirTarget = raw.redirectTarget();
        boolean cycleCut = false;
        String effPerm = raw.permission();
        String effPlugin = raw.owningPlugin();

        if (redirTarget != null && !redirTarget.isEmpty()) {
            if (visitedRedirects.contains(redirTarget)) {
                cycleCut = true;
            } else {
                RawCommandNode targetNode = findTargetNode(redirTarget, rootByName);
                if (targetNode != null) {
                    Set<String> nextVisited = new LinkedHashSet<>(visitedRedirects);
                    nextVisited.add(redirTarget);
                    ResolvedSubtree resolvedTarget = buildResolvedSubtree(targetNode, rootByName, nextVisited, depth + 1);
                    if (resolvedTarget != null) {
                        if (resolvedTarget.cycleCut) {
                            cycleCut = true;
                        }
                        if (!hasOwn && !cycleCut && resolvedTarget.executable) {
                            effExec = true;
                        }
                        if (effPerm == null) {
                            effPerm = resolvedTarget.permission;
                        }
                        if (effPlugin == null) {
                            effPlugin = resolvedTarget.owningPlugin;
                        }
                    }
                    for (RawCommandNode tc : targetNode.children()) {
                        if (!effChildren.contains(tc)) {
                            effChildren.add(tc);
                        }
                    }
                }
            }
        }

        Map<String, ResolvedSubtree> childrenMap = new LinkedHashMap<>();
        for (RawCommandNode ch : effChildren) {
            if (ch == null || ch.name().isEmpty()) {
                continue;
            }
            String childKey = ch.name();
            if (visitedRedirects.contains(childKey) && ch.redirectTarget() != null) {
                cycleCut = true;
                continue;
            }
            Set<String> childVisited = new LinkedHashSet<>(visitedRedirects);
            if (ch.redirectTarget() != null) {
                childVisited.add(ch.name());
            }
            ResolvedSubtree childSub = buildResolvedSubtree(ch, rootByName, childVisited, depth + 1);
            if (childSub == null) {
                continue;
            }
            String tok = childKeyForMerge(childrenMap, childSub);
            if (childrenMap.containsKey(tok)) {
                childrenMap.put(tok, mergeTwoSubtrees(childrenMap.get(tok), childSub));
            } else {
                childrenMap.put(tok, childSub);
            }
        }

        return new ResolvedSubtree(
                raw.kind(),
                raw.name(),
                "argument".equals(raw.kind()) ? raw.parser() : null,
                "argument".equals(raw.kind()) ? raw.properties() : Map.of(),
                effExec,
                redirTarget,
                cycleCut,
                hasOwn,
                effPerm,
                effPlugin,
                childrenMap
        );
    }

    private static RawCommandNode findTargetNode(String targetPath, Map<String, RawCommandNode> rootByName) {
        String[] parts = targetPath.trim().split("\\s+");
        if (parts.length == 0) {
            return null;
        }
        RawCommandNode cur = rootByName.get(parts[0]);
        for (int i = 1; i < parts.length && cur != null; i++) {
            String want = parts[i];
            RawCommandNode found = null;
            for (RawCommandNode ch : cur.children()) {
                String tok = "argument".equals(ch.kind()) ? "<" + ch.name() + ">" : ch.name();
                if (tok.equals(want) || ch.name().equals(want)) {
                    found = ch;
                    break;
                }
            }
            cur = found;
        }
        return cur;
    }

    private static ResolvedSubtree mergeTwoSubtrees(ResolvedSubtree a, ResolvedSubtree b) {
        if (a == b) {
            return a;
        }
        Map<String, ResolvedSubtree> mergedChildren = new LinkedHashMap<>(a.children);
        for (ResolvedSubtree bChild : b.children.values()) {
            String tok = childKeyForMerge(mergedChildren, bChild);
            if (mergedChildren.containsKey(tok)) {
                mergedChildren.put(tok, mergeTwoSubtrees(mergedChildren.get(tok), bChild));
            } else {
                mergedChildren.put(tok, bChild);
            }
        }
        return new ResolvedSubtree(
                a.kind,
                a.name,
                a.parser != null ? a.parser : b.parser,
                !a.properties.isEmpty() ? a.properties : b.properties,
                a.executable || b.executable,
                a.redirectTargetPath != null ? a.redirectTargetPath : b.redirectTargetPath,
                a.cycleCut || b.cycleCut,
                a.hasOwnChildren || b.hasOwnChildren,
                a.permission != null ? a.permission : b.permission,
                a.owningPlugin != null ? a.owningPlugin : b.owningPlugin,
                mergedChildren
        );
    }

    private static String childKeyForMerge(Map<String, ResolvedSubtree> existing, ResolvedSubtree child) {
        String baseTok = child.token();
        ResolvedSubtree cur = existing.get(baseTok);
        if (cur == null) {
            return baseTok;
        }
        if (Objects.equals(cur.kind, child.kind)
                && Objects.equals(cur.parser, child.parser)
                && Objects.equals(cur.properties, child.properties)) {
            return baseTok;
        }
        String pShort = "argument".equals(child.kind) ? normalizeParserKind(child.parser) : child.kind;
        String altTok = "argument".equals(child.kind)
                ? "<" + child.name + ":" + pShort + ">"
                : child.name + ":" + pShort;
        int idx = 2;
        String cand = altTok;
        while (existing.containsKey(cand)) {
            ResolvedSubtree ex = existing.get(cand);
            if (Objects.equals(ex.kind, child.kind)
                    && Objects.equals(ex.parser, child.parser)
                    && Objects.equals(ex.properties, child.properties)) {
                break;
            }
            cand = "argument".equals(child.kind)
                    ? "<" + child.name + ":" + pShort + ":" + idx + ">"
                    : child.name + ":" + pShort + ":" + idx;
            idx++;
        }
        return cand;
    }

    private static final class ResolvedSubtree {
        final String kind;
        final String name;
        final String parser;
        final Map<String, Object> properties;
        final boolean executable;
        final String redirectTargetPath;
        final boolean cycleCut;
        final boolean hasOwnChildren;
        final String permission;
        final String owningPlugin;
        final Map<String, ResolvedSubtree> children;
        private String cachedSignature;

        ResolvedSubtree(
                String kind,
                String name,
                String parser,
                Map<String, Object> properties,
                boolean executable,
                String redirectTargetPath,
                boolean cycleCut,
                boolean hasOwnChildren,
                String permission,
                String owningPlugin,
                Map<String, ResolvedSubtree> children
        ) {
            this.kind = kind;
            this.name = name;
            this.parser = parser;
            this.properties = properties == null ? Map.of() : properties;
            this.executable = executable;
            this.redirectTargetPath = redirectTargetPath;
            this.cycleCut = cycleCut;
            this.hasOwnChildren = hasOwnChildren;
            this.permission = permission;
            this.owningPlugin = owningPlugin;
            this.children = children == null ? Map.of() : children;
        }

        String token() {
            return "argument".equals(kind) ? "<" + name + ">" : name;
        }

        SequenceStep step() {
            return new SequenceStep(kind, name, "argument".equals(kind) ? parser : null);
        }

        String structuralSignature() {
            if (cachedSignature != null) {
                return cachedSignature;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(kind).append('|')
                    .append(parser == null ? "" : parser).append('|')
                    .append(new TreeMap<>(properties)).append('|')
                    .append(executable).append('|')
                    .append(cycleCut).append('{');
            for (Map.Entry<String, ResolvedSubtree> e : new TreeMap<>(children).entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue().fullSignature()).append(';');
            }
            sb.append('}');
            cachedSignature = sb.toString();
            return cachedSignature;
        }

        private String fullSignature() {
            return token() + "@" + (redirectTargetPath == null ? "" : redirectTargetPath) + ":" + structuralSignature();
        }
    }

    /**
     * Splits a command token into {@code [namespace, bareName]} (or {@code ["", token]} if un-namespaced).
     *
     * @param name command token
     * @return 2-element array {@code [namespace, bareName]}
     */
    public static String[] splitNamespace(String name) {
        if (name == null) {
            return new String[]{"", ""};
        }
        String trimmed = name.trim();
        int colon = trimmed.indexOf(':');
        if (colon > 0 && colon < trimmed.length() - 1 && trimmed.indexOf(':', colon + 1) < 0) {
            return new String[]{trimmed.substring(0, colon), trimmed.substring(colon + 1)};
        }
        return new String[]{"", trimmed};
    }

    /**
     * Normalizes a Brigadier parser identifier to its short kind (e.g. {@code "minecraft:entity" -> "entity"}).
     *
     * @param parser raw parser string
     * @return short lowercase parser kind
     */
    public static String normalizeParserKind(String parser) {
        if (parser == null || parser.isBlank()) {
            return "arg";
        }
        String p = parser.trim().toLowerCase(Locale.ROOT);
        int colon = p.indexOf(':');
        if (colon >= 0 && colon + 1 < p.length()) {
            return p.substring(colon + 1);
        }
        return p;
    }

    /**
     * Orders tier names with {@code "default"} before {@code "op"}.
     *
     * @param tiers tier collection
     * @return ordered immutable list of tiers
     */
    public static List<String> sortTiers(Collection<String> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String t : tiers) {
            if (t != null && !t.isBlank()) {
                unique.add(t.trim().toLowerCase(Locale.ROOT));
            }
        }
        List<String> sorted = new ArrayList<>(unique);
        sorted.sort(Comparator.comparingInt(CommandSurfaceAuditor::tierRank).thenComparing(Comparator.naturalOrder()));
        return Collections.unmodifiableList(sorted);
    }

    public static String minVisibleTier(Collection<String> tiers) {
        List<String> ordered = sortTiers(tiers);
        return ordered.isEmpty() ? "op" : ordered.get(0);
    }

    private static int tierRank(String tier) {
        int idx = TIER_ORDER.indexOf(tier);
        return idx >= 0 ? idx : TIER_ORDER.size();
    }

    private static boolean globMatch(String text, String glob) {
        if (text == null || glob == null) {
            return false;
        }
        if (glob.endsWith("*")) {
            String prefix = glob.substring(0, glob.length() - 1);
            return text.startsWith(prefix);
        }
        return text.equals(glob);
    }
}
