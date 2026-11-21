package io.github.dailystruggle.bytecode.transpile;

import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptExpr;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptFunction;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptLut;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptModule;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptParam;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptStmt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Structured compiler diagnostic emitted by {@link IrIdiomRewriteRegistry} and worldgen lifters
 * whenever a lifted SSA subgraph is rewritten, remains unoptimized, or fails closed to native
 * delegation.
 *
 * <p>Fingerprints are derived purely from structural SSA opcodes, primitive types, parameter
 * arities, and lookup-table shapes—independent of JVM class names or line numbers—so identical
 * patterns across Minecraft versions and third-party mods map to the same catalog key.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy leafgen-ADR-008
 * @trace REQ-LEAFGEN-IR-002
 * @trace REQ-LEAFGEN-IR-003
 */
public record IrPatternDiagnostic(
        String stageId,
        String symbol,
        String structuralFingerprint,
        Disposition disposition,
        String detail
) {

    /**
     * Compiler disposition for a diagnosed worldgen IR construct.
     */
    public enum Disposition {
        REWRITTEN,
        UNOPTIMIZED_IR,
        NATIVE_DELEGATED
    }

    public IrPatternDiagnostic {
        stageId = (stageId == null || stageId.isBlank()) ? "UNKNOWN_STAGE" : stageId;
        symbol = (symbol == null || symbol.isBlank()) ? "<anonymous>" : symbol;
        structuralFingerprint = (structuralFingerprint == null || structuralFingerprint.isBlank())
                ? "0000000000000000"
                : structuralFingerprint;
        Objects.requireNonNull(disposition, "disposition");
        detail = detail == null ? "" : detail;
    }

    /**
     * Stable deduplication key for once-per-pattern {@code Level.WARNING} logging.
     */
    public String dedupKey() {
        return stageId + "|" + symbol + "|" + structuralFingerprint + "|" + disposition.name();
    }

    /**
     * Formats a concise single-line diagnostic suitable for {@code Level.WARNING} logs and CLI
     * coverage reports.
     */
    public String formatSummary() {
        return "[" + disposition.name() + "] stage=" + stageId
                + " symbol=" + symbol
                + " fp=" + structuralFingerprint
                + (detail.isEmpty() ? "" : " detail=" + detail);
    }

    /**
     * Computes a deterministic 16-hex-character structural fingerprint for a {@link ConceptModule}
     * based solely on SSA control/dataflow shape, types, and LUT geometries (ignoring class and
     * variable names).
     */
    public static String structuralFingerprint(ConceptModule module) {
        if (module == null) {
            return "0000000000000000";
        }
        StringBuilder sb = new StringBuilder(1024);
        sb.append("MOD:luts=").append(module.lookupTables().size()).append(';');
        for (ConceptLut lut : module.lookupTables()) {
            sb.append("LUT(")
                    .append(lut.elementType().name())
                    .append(':')
                    .append(lut.values().size())
                    .append(");");
        }
        sb.append("H=").append(module.helperFunctions().size()).append(';');
        for (ConceptFunction helper : module.helperFunctions()) {
            appendFunctionShape(sb, helper);
        }
        if (module.entryFunction() != null) {
            sb.append("E:");
            appendFunctionShape(sb, module.entryFunction());
        }
        return sha256Prefix16(sb.toString());
    }

    /**
     * Computes a deterministic 16-hex-character structural fingerprint for a single
     * {@link ConceptFunction}.
     */
    public static String structuralFingerprint(ConceptFunction fn) {
        if (fn == null) {
            return "0000000000000000";
        }
        StringBuilder sb = new StringBuilder(512);
        appendFunctionShape(sb, fn);
        return sha256Prefix16(sb.toString());
    }

    /**
     * Computes a deterministic 16-hex-character fingerprint from an arbitrary structural signature
     * string (used when bytecode lifting fails prior to constructing a {@link ConceptModule}).
     */
    public static String fingerprintText(String structuralSignature) {
        if (structuralSignature == null || structuralSignature.isEmpty()) {
            return "0000000000000000";
        }
        return sha256Prefix16(structuralSignature);
    }

    private static void appendFunctionShape(StringBuilder sb, ConceptFunction fn) {
        sb.append("FN(");
        for (ConceptParam p : fn.parameters()) {
            sb.append(p.type().name()).append(',');
        }
        sb.append(")->").append(fn.returnType().name()).append('{');
        appendStmtsShape(sb, fn.body(), 0);
        sb.append("};");
    }

    private static void appendStmtsShape(StringBuilder sb, List<ConceptStmt> stmts, int depth) {
        if (depth > 24 || stmts == null) {
            sb.append("...");
            return;
        }
        for (ConceptStmt s : stmts) {
            if (s instanceof ConceptStmt.VarDecl vd) {
                sb.append("D:").append(vd.type().name()).append(';');
            } else if (s instanceof ConceptStmt.Assign a) {
                sb.append("A:");
                appendExprShape(sb, a.value(), depth + 1);
                sb.append(';');
            } else if (s instanceof ConceptStmt.If iff) {
                sb.append("IF(");
                appendExprShape(sb, iff.condition(), depth + 1);
                sb.append("){");
                appendStmtsShape(sb, iff.thenBody(), depth + 1);
                sb.append("}{");
                appendStmtsShape(sb, iff.elseBody(), depth + 1);
                sb.append("};");
            } else if (s instanceof ConceptStmt.While wh) {
                sb.append("W(");
                appendExprShape(sb, wh.condition(), depth + 1);
                sb.append("){");
                appendStmtsShape(sb, wh.body(), depth + 1);
                sb.append("};");
            } else if (s instanceof ConceptStmt.Return ret) {
                sb.append("R:");
                if (ret.value() != null) {
                    appendExprShape(sb, ret.value(), depth + 1);
                }
                sb.append(';');
            } else if (s instanceof ConceptStmt.StoreBuffer3D st) {
                sb.append("ST:");
                appendExprShape(sb, st.linearIndex(), depth + 1);
                sb.append('=');
                appendExprShape(sb, st.value(), depth + 1);
                sb.append(';');
            }
        }
    }

    private static void appendExprShape(StringBuilder sb, ConceptExpr e, int depth) {
        if (depth > 24 || e == null) {
            sb.append('_');
            return;
        }
        if (e instanceof ConceptExpr.Var v) {
            sb.append("V:").append(v.type().name());
        } else if (e instanceof ConceptExpr.IntConst ic) {
            sb.append("IC:").append(ic.value() == 0 || ic.value() == 1 || ic.value() == 255 ? ic.value() : "N");
        } else if (e instanceof ConceptExpr.LongConst lc) {
            sb.append("LC:").append(lc.value() == 0L || lc.value() == 1L ? lc.value() : "N");
        } else if (e instanceof ConceptExpr.FloatConst fc) {
            sb.append("FC:").append(fc.value() == 0.0f || fc.value() == 1.0f ? fc.value() : "N");
        } else if (e instanceof ConceptExpr.DoubleConst dc) {
            sb.append("DC:").append(dc.value() == 0.0 || dc.value() == 1.0
                    || dc.value() == 6.0 || dc.value() == 15.0 || dc.value() == 10.0 ? dc.value() : "N");
        } else if (e instanceof ConceptExpr.BoolConst bc) {
            sb.append("BC:").append(bc.value() ? '1' : '0');
        } else if (e instanceof ConceptExpr.UnaryOp u) {
            sb.append("U:").append(u.op().name()).append('(');
            appendExprShape(sb, u.operand(), depth + 1);
            sb.append(')');
        } else if (e instanceof ConceptExpr.Cast c) {
            sb.append("C:").append(c.targetType().name()).append('(');
            appendExprShape(sb, c.operand(), depth + 1);
            sb.append(')');
        } else if (e instanceof ConceptExpr.BinaryOp b) {
            sb.append("B:").append(b.op().name()).append(':').append(b.type().name()).append('(');
            appendExprShape(sb, b.left(), depth + 1);
            sb.append(',');
            appendExprShape(sb, b.right(), depth + 1);
            sb.append(')');
        } else if (e instanceof ConceptExpr.Ternary t) {
            sb.append("T(");
            appendExprShape(sb, t.condition(), depth + 1);
            sb.append('?');
            appendExprShape(sb, t.thenExpr(), depth + 1);
            sb.append(':');
            appendExprShape(sb, t.elseExpr(), depth + 1);
            sb.append(')');
        } else if (e instanceof ConceptExpr.LutLoad l) {
            sb.append("L:").append(l.elementType().name()).append('[');
            appendExprShape(sb, l.index(), depth + 1);
            sb.append(']');
        } else if (e instanceof ConceptExpr.MathIntrinsic m) {
            sb.append("M:").append(m.kind().name()).append('(');
            for (ConceptExpr arg : m.arguments()) {
                appendExprShape(sb, arg, depth + 1);
                sb.append(',');
            }
            sb.append(')');
        } else if (e instanceof ConceptExpr.Call c) {
            sb.append("K:").append(c.returnType().name()).append('/').append(c.arguments().size()).append('(');
            for (ConceptExpr arg : c.arguments()) {
                appendExprShape(sb, arg, depth + 1);
                sb.append(',');
            }
            sb.append(')');
        } else if (e instanceof ConceptExpr.CompareTemp ct) {
            sb.append("CMP(");
            appendExprShape(sb, ct.left(), depth + 1);
            sb.append(',');
            appendExprShape(sb, ct.right(), depth + 1);
            sb.append(')');
        }
    }

    private static String sha256Prefix16(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable in JVM", ex);
        }
    }
}
