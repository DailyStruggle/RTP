package io.github.dailystruggle.bytecode.transpile;

import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.BinaryOperator;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptExpr;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptFunction;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptKernel3D;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptLut;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptModule;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptParam;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptStmt;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Idiomatic zero-dependency Rust backend emitter implementing {@link ConceptBackendEmitter}.
 *
 * <p>Demonstrates that adding a secondary target language (such as native Rust for PumpkinMC
 * worldgen or plugin modules) requires only a thin AST visitor over {@link BytecodeConceptIr}
 * without altering bytecode lifting or whole-program optimization passes.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001
 */
public final class RustBackendEmitter implements ConceptBackendEmitter {

    public static final String BACKEND_ID = "rust";

    @Override
    public String backendId() {
        return BACKEND_ID;
    }

    @Override
    public String fileExtension() {
        return ".rs";
    }

    @Override
    public TranspiledArtifact emit(ConceptModule module, EmitOptions options) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(options, "options");

        StringBuilder sb = new StringBuilder(2048);
        sb.append("// Auto-generated Rust module from JVM bytecode module: ")
                .append(module.moduleName())
                .append("\n#![allow(non_snake_case, non_upper_case_globals, unused_parens, unused_variables, unused_assignments, unused_mut, dead_code, clippy::excessive_precision)]\n\n");

        for (ConceptLut lut : module.lookupTables()) {
            emitLut(sb, lut, options);
            sb.append('\n');
        }

        for (ConceptFunction helper : module.helperFunctions()) {
            emitFunction(sb, helper, options, false);
            sb.append('\n');
        }

        emitFunction(sb, module.entryFunction(), options, true);

        String kernelName = null;
        if (options.emit3DKernel() && module.kernel3D() != null) {
            sb.append('\n');
            emitGrid3DFunction(sb, module.kernel3D(), options);
            kernelName = module.kernel3D().kernelName();
        }

        return new TranspiledArtifact(
                BACKEND_ID,
                fileExtension(),
                module.moduleName(),
                module.entryFunction().name(),
                kernelName,
                options.fpPrecision(),
                sb.toString(),
                module);
    }

    private static void emitLut(StringBuilder sb, ConceptLut lut, EmitOptions options) {
        sb.append("pub const ")
                .append(lut.symbolName())
                .append(": [")
                .append(mapType(lut.elementType(), options))
                .append("; ")
                .append(lut.values().size())
                .append("] = [\n    ");
        List<Number> vals = lut.values();
        for (int i = 0; i < vals.size(); i++) {
            Number val = vals.get(i);
            if (lut.elementType() == ConceptType.F32
                    || (lut.elementType() == ConceptType.F64
                            && options.fpPrecision() == FloatingPointPrecision.DOWNCAST_FP32)) {
                sb.append(formatFloat(val.floatValue()));
            } else if (lut.elementType() == ConceptType.F64) {
                sb.append(formatDouble(val.doubleValue()));
            } else {
                sb.append(val);
            }
            if (i + 1 < vals.size()) {
                sb.append(", ");
                if ((i + 1) % 16 == 0) {
                    sb.append("\n    ");
                }
            }
        }
        sb.append("\n];\n");
    }

    private static void emitFunction(
            StringBuilder sb,
            ConceptFunction fn,
            EmitOptions options,
            boolean isPublic) {
        sb.append("#[inline]\n");
        if (isPublic) {
            sb.append("pub ");
        }
        sb.append("fn ").append(fn.name()).append('(');
        List<ConceptParam> params = fn.parameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            ConceptParam p = params.get(i);
            sb.append(p.name()).append(": ").append(mapType(p.type(), options));
        }
        sb.append(')');
        if (fn.returnType() != ConceptType.VOID) {
            sb.append(" -> ").append(mapType(fn.returnType(), options));
        }
        sb.append(" {\n");
        emitStatements(sb, fn.body(), options, 1);
        sb.append("}\n");
    }

    private static void emitGrid3DFunction(
            StringBuilder sb,
            ConceptKernel3D kernel,
            EmitOptions options) {
        ConceptFunction target = kernel.targetFunction();
        String fpType = mapType(ConceptType.F64, options);
        String outElemType = mapType(target.returnType(), options);

        sb.append("pub fn ")
                .append(kernel.kernelName())
                .append("(\n")
                .append("    ")
                .append(kernel.outputBufferParam())
                .append(": &mut [")
                .append(outElemType)
                .append("],\n")
                .append("    dim_x: usize,\n")
                .append("    dim_y: usize,\n")
                .append("    dim_z: usize,\n")
                .append("    ")
                .append(kernel.originXParam())
                .append(": ")
                .append(fpType)
                .append(",\n")
                .append("    ")
                .append(kernel.originYParam())
                .append(": ")
                .append(fpType)
                .append(",\n")
                .append("    ")
                .append(kernel.originZParam())
                .append(": ")
                .append(fpType)
                .append(",\n")
                .append("    ")
                .append(kernel.scaleParam())
                .append(": ")
                .append(fpType);

        for (ConceptParam extra : kernel.extraUniformParams()) {
            sb.append(",\n    ")
                    .append(extra.name())
                    .append(": ")
                    .append(mapType(extra.type(), options));
        }
        sb.append("\n) {\n");
        sb.append("    for gy in 0..dim_y {\n");
        sb.append("        for gz in 0..dim_z {\n");
        sb.append("            for gx in 0..dim_x {\n");
        sb.append("                let linear_idx = (gy * dim_z + gz) * dim_x + gx;\n");

        List<String> callArgs = new ArrayList<>();
        List<ConceptParam> targetParams = target.parameters();
        String[] coordExprs = {
            "(" + kernel.originXParam() + " + (gx as " + fpType + ") * " + kernel.scaleParam() + ")",
            "(" + kernel.originYParam() + " + (gy as " + fpType + ") * " + kernel.scaleParam() + ")",
            "(" + kernel.originZParam() + " + (gz as " + fpType + ") * " + kernel.scaleParam() + ")"
        };

        if (targetParams.size() == 2 && targetParams.get(1).type() == ConceptType.I64) {
            String p0Cast = targetParams.get(0).type().isInteger()
                    ? "(" + coordExprs[0] + " as i64)"
                    : coordExprs[0];
            callArgs.add(p0Cast);
            callArgs.add(targetParams.get(1).name());
        } else {
            for (int i = 0; i < targetParams.size(); i++) {
                ConceptParam tp = targetParams.get(i);
                if (i < 3) {
                    String gridVar = i == 0 ? "gx" : i == 1 ? "gy" : "gz";
                    String originVar = i == 0 ? kernel.originXParam()
                            : i == 1 ? kernel.originYParam() : kernel.originZParam();
                    if (tp.type() == ConceptType.I64) {
                        callArgs.add("((" + gridVar + " as i64) + (" + originVar + " as i64))");
                    } else if (tp.type() == ConceptType.I32) {
                        callArgs.add("((" + gridVar + " as i32) + (" + originVar + " as i32))");
                    } else {
                        callArgs.add(coordExprs[i]);
                    }
                } else {
                    callArgs.add(tp.name());
                }
            }
        }

        sb.append("                ")
                .append(kernel.outputBufferParam())
                .append("[linear_idx] = ")
                .append(target.name())
                .append('(')
                .append(String.join(", ", callArgs))
                .append(");\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("}\n");
    }

    private static void emitStatements(
            StringBuilder sb,
            List<ConceptStmt> stmts,
            EmitOptions options,
            int indentLevel) {
        String indent = "    ".repeat(indentLevel);
        for (ConceptStmt stmt : stmts) {
            if (stmt instanceof ConceptStmt.VarDecl decl) {
                sb.append(indent)
                        .append("let mut ")
                        .append(decl.name())
                        .append(": ")
                        .append(mapType(decl.type(), options));
                if (decl.initializer() != null) {
                    sb.append(" = ").append(emitExpr(decl.initializer(), options));
                }
                sb.append(";\n");
            } else if (stmt instanceof ConceptStmt.Assign assign) {
                sb.append(indent)
                        .append(assign.name())
                        .append(" = ")
                        .append(emitExpr(assign.value(), options))
                        .append(";\n");
            } else if (stmt instanceof ConceptStmt.If ifStmt) {
                sb.append(indent)
                        .append("if ")
                        .append(emitExpr(ifStmt.condition(), options))
                        .append(" {\n");
                emitStatements(sb, ifStmt.thenBody(), options, indentLevel + 1);
                if (!ifStmt.elseBody().isEmpty()) {
                    sb.append(indent).append("} else {\n");
                    emitStatements(sb, ifStmt.elseBody(), options, indentLevel + 1);
                }
                sb.append(indent).append("}\n");
            } else if (stmt instanceof ConceptStmt.While whileStmt) {
                sb.append(indent)
                        .append("while ")
                        .append(emitExpr(whileStmt.condition(), options))
                        .append(" {\n");
                emitStatements(sb, whileStmt.body(), options, indentLevel + 1);
                sb.append(indent).append("}\n");
            } else if (stmt instanceof ConceptStmt.Return ret) {
                sb.append(indent).append("return");
                if (ret.value() != null) {
                    sb.append(' ').append(emitExpr(ret.value(), options));
                }
                sb.append(";\n");
            }
        }
    }

    private static String emitExpr(ConceptExpr expr, EmitOptions options) {
        if (expr instanceof ConceptExpr.Var v) {
            return v.name();
        }
        if (expr instanceof ConceptExpr.IntConst c) {
            int val = c.value();
            if (val == Integer.MIN_VALUE) {
                return "i32::MIN";
            }
            return val < 0 ? "(" + val + "i32)" : val + "i32";
        }
        if (expr instanceof ConceptExpr.LongConst c) {
            long val = c.value();
            if (val == Long.MIN_VALUE) {
                return "i64::MIN";
            }
            return val < 0L ? "(" + val + "i64)" : val + "i64";
        }
        if (expr instanceof ConceptExpr.FloatConst c) {
            return formatFloat(c.value());
        }
        if (expr instanceof ConceptExpr.DoubleConst c) {
            if (options.fpPrecision() == FloatingPointPrecision.DOWNCAST_FP32) {
                return formatFloat((float) c.value());
            }
            return formatDouble(c.value());
        }
        if (expr instanceof ConceptExpr.BoolConst c) {
            return Boolean.toString(c.value());
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            String operand = emitExpr(u.operand(), options);
            if (u.op() == BytecodeConceptIr.UnaryOperator.BIT_NOT) {
                return "(!" + operand + ")";
            }
            if (u.op() == BytecodeConceptIr.UnaryOperator.NEG
                    && (u.type() == ConceptType.I32 || u.type() == ConceptType.I64)) {
                return "(" + operand + ").wrapping_neg()";
            }
            return "(" + u.op().symbol() + operand + ")";
        }
        if (expr instanceof ConceptExpr.Cast cast) {
            return "((" + emitExpr(cast.operand(), options) + ") as "
                    + mapType(cast.targetType(), options) + ")";
        }
        if (expr instanceof ConceptExpr.Ternary tern) {
            return "(if " + emitExpr(tern.condition(), options) + " { "
                    + emitExpr(tern.thenExpr(), options) + " } else { "
                    + emitExpr(tern.elseExpr(), options) + " })";
        }
        if (expr instanceof ConceptExpr.LutLoad load) {
            return load.lutSymbol() + "[(" + emitExpr(load.index(), options) + ") as usize]";
        }
        if (expr instanceof ConceptExpr.MathIntrinsic math) {
            List<String> args = new ArrayList<>(math.arguments().size());
            for (ConceptExpr a : math.arguments()) {
                args.add(emitExpr(a, options));
            }
            return switch (math.kind()) {
                case FLOOR -> "(" + args.get(0) + ").floor()";
                case CEIL -> "(" + args.get(0) + ").ceil()";
                case ROUND -> "(" + args.get(0) + ").round()";
                case SQRT -> "(" + args.get(0) + ").sqrt()";
                case ABS -> math.type().isFloatingPoint()
                        ? "(" + args.get(0) + ").abs()"
                        : "(" + args.get(0) + ").wrapping_abs()";
                case SIN -> "(" + args.get(0) + ").sin()";
                case COS -> "(" + args.get(0) + ").cos()";
                case TAN -> "(" + args.get(0) + ").tan()";
                case ATAN2 -> "(" + args.get(0) + ").atan2(" + args.get(1) + ")";
                case EXP -> "(" + args.get(0) + ").exp()";
                case LOG -> "(" + args.get(0) + ").ln()";
                case POW -> "(" + args.get(0) + ").powf(" + args.get(1) + ")";
                case MIN -> "(" + args.get(0) + ").min(" + args.get(1) + ")";
                case MAX -> "(" + args.get(0) + ").max(" + args.get(1) + ")";
                case FMA -> "(" + args.get(0) + ").mul_add(" + args.get(1) + ", " + args.get(2) + ")";
                case IS_FINITE -> "(" + args.get(0) + ").is_finite()";
                case IS_NAN -> "(" + args.get(0) + ").is_nan()";
                case POPCOUNT -> "((" + args.get(0) + ") as u64).count_ones() as i32";
                case CLZ -> "((" + args.get(0) + ") as u64).leading_zeros() as i32";
                case CTZ -> "((" + args.get(0) + ") as u64).trailing_zeros() as i32";
            };
        }
        if (expr instanceof ConceptExpr.Call call) {
            List<String> args = new ArrayList<>(call.arguments().size());
            for (ConceptExpr a : call.arguments()) {
                args.add(emitExpr(a, options));
            }
            return call.methodName() + "(" + String.join(", ", args) + ")";
        }
        if (expr instanceof ConceptExpr.BinaryOp bin) {
            String l = emitExpr(bin.left(), options);
            String r = emitExpr(bin.right(), options);
            if (bin.op() == BinaryOperator.USHR) {
                if (bin.type() == ConceptType.I64) {
                    return "(((((" + l + ") as u64) >> (((" + r + ") as u32) & 63u32))) as i64)";
                }
                return "(((((" + l + ") as u32) >> (((" + r + ") as u32) & 31u32))) as i32)";
            }
            if (bin.op() == BinaryOperator.SHL || bin.op() == BinaryOperator.SHR) {
                String mask = bin.type() == ConceptType.I64 ? "63u32" : "31u32";
                return "((" + l + ") " + bin.op().symbol() + " (((" + r + ") as u32) & " + mask + "))";
            }
            if (bin.type() == ConceptType.I32 || bin.type() == ConceptType.I64) {
                String intType = mapType(bin.type(), options);
                String lhs = bin.left().type() == bin.type() ? l : "((" + l + ") as " + intType + ")";
                String rhs = bin.right().type() == bin.type() ? r : "((" + r + ") as " + intType + ")";
                switch (bin.op()) {
                    case ADD -> {
                        return "(" + lhs + ").wrapping_add(" + rhs + ")";
                    }
                    case SUB -> {
                        return "(" + lhs + ").wrapping_sub(" + rhs + ")";
                    }
                    case MUL -> {
                        return "(" + lhs + ").wrapping_mul(" + rhs + ")";
                    }
                    case DIV -> {
                        return "(" + lhs + ").wrapping_div(" + rhs + ")";
                    }
                    case REM -> {
                        return "(" + lhs + ").wrapping_rem(" + rhs + ")";
                    }
                    default -> {
                    }
                }
            }
            return "(" + l + " " + bin.op().symbol() + " " + r + ")";
        }
        throw new IllegalStateException("Unsupported ConceptExpr in Rust emitter: " + expr);
    }

    private static String mapType(ConceptType type, EmitOptions options) {
        return switch (type) {
            case VOID -> "()";
            case BOOL -> "bool";
            case I32 -> "i32";
            case I64 -> "i64";
            case F32 -> "f32";
            case F64 -> options.fpPrecision() == FloatingPointPrecision.DOWNCAST_FP32
                    ? "f32"
                    : "f64";
            default -> "i64";
        };
    }

    private static String formatDouble(double d) {
        if (Double.isNaN(d)) {
            return "f64::NAN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "f64::INFINITY" : "f64::NEG_INFINITY";
        }
        String s = String.format(Locale.ROOT, "%s", d);
        if (!s.contains(".") && !s.contains("E") && !s.contains("e")) {
            s = s + ".0";
        }
        String lit = s + "f64";
        return (d < 0.0 || 1.0 / d < 0.0) ? "(" + lit + ")" : lit;
    }

    private static String formatFloat(float f) {
        if (Float.isNaN(f)) {
            return "f32::NAN";
        }
        if (Float.isInfinite(f)) {
            return f > 0 ? "f32::INFINITY" : "f32::NEG_INFINITY";
        }
        String s = String.format(Locale.ROOT, "%s", f);
        if (!s.contains(".") && !s.contains("E") && !s.contains("e")) {
            s = s + ".0";
        }
        String lit = s + "f32";
        return (f < 0.0f || 1.0f / f < 0.0f) ? "(" + lit + ")" : lit;
    }
}
