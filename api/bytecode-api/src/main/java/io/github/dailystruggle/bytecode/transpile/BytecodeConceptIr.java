package io.github.dailystruggle.bytecode.transpile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Target-agnostic stackless SSA and structured concept intermediate representation (IR) lifted from
 * JVM {@code .class} bytecode for multi-language transpilation and GPU kernel synthesis.
 *
 * <p>Decouples zero-classloading JVM bytecode stack-frame simulation ({@link BytecodeSsaLifter})
 * and whole-program optimization ({@link ConceptOptimizerPass}) from target code generation
 * ({@link ConceptBackendEmitter}), enabling compile-once lowering to OpenCL C99, Rust, WGSL, or C.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001
 */
public final class BytecodeConceptIr {

    private BytecodeConceptIr() {
    }

    /**
     * Primitive, array, and buffer value types in the concept IR.
     */
    public enum ConceptType {
        VOID,
        BOOL,
        I32,
        I64,
        F32,
        F64,
        I32_ARRAY,
        I64_ARRAY,
        F64_ARRAY,
        GLOBAL_BUFFER_F64,
        GLOBAL_BUFFER_F32,
        GLOBAL_BUFFER_I32;

        public boolean isFloatingPoint() {
            return this == F32 || this == F64;
        }

        public boolean isInteger() {
            return this == I32 || this == I64 || this == BOOL;
        }

        public boolean isWideJvmSlot() {
            return this == I64 || this == F64;
        }

        public static ConceptType fromDescriptorPrefix(String desc) {
            if (desc == null || desc.isEmpty()) {
                return VOID;
            }
            return switch (desc.charAt(0)) {
                case 'V' -> VOID;
                case 'Z' -> BOOL;
                case 'B', 'C', 'S', 'I' -> I32;
                case 'J' -> I64;
                case 'F' -> F32;
                case 'D' -> F64;
                case '[' -> switch (desc.length() > 1 ? desc.charAt(1) : 'I') {
                    case 'J' -> I64_ARRAY;
                    case 'D', 'F' -> F64_ARRAY;
                    default -> I32_ARRAY;
                };
                default -> I64;
            };
        }
    }

    /**
     * Binary operators with explicit separation between signed shift ({@link #SHR}) and JVM
     * zero-extending unsigned shift ({@link #USHR}, {@code >>>}).
     */
    public enum BinaryOperator {
        ADD("+"),
        SUB("-"),
        MUL("*"),
        DIV("/"),
        REM("%"),
        BIT_AND("&"),
        BIT_OR("|"),
        BIT_XOR("^"),
        SHL("<<"),
        SHR(">>"),
        USHR(">>>"),
        EQ("=="),
        NE("!="),
        LT("<"),
        LE("<="),
        GT(">"),
        GE(">="),
        LOGICAL_AND("&&"),
        LOGICAL_OR("||");

        private final String symbol;

        BinaryOperator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        public boolean isComparison() {
            return this == EQ || this == NE || this == LT || this == LE
                    || this == GT || this == GE || this == LOGICAL_AND || this == LOGICAL_OR;
        }

        public BinaryOperator negateComparison() {
            return switch (this) {
                case EQ -> NE;
                case NE -> EQ;
                case LT -> GE;
                case GE -> LT;
                case GT -> LE;
                case LE -> GT;
                default -> throw new IllegalStateException("Not a relational comparison: " + this);
            };
        }
    }

    /**
     * Unary operators in the concept IR.
     */
    public enum UnaryOperator {
        NEG("-"),
        LOGICAL_NOT("!"),
        BIT_NOT("~");

        private final String symbol;

        UnaryOperator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    /**
     * Target-independent mathematical and numeric intrinsics lifted from {@link Math},
     * {@link Double}, {@link Float}, {@link Integer}, and {@link Long}.
     */
    public enum MathIntrinsicKind {
        FLOOR,
        CEIL,
        ROUND,
        SQRT,
        ABS,
        SIN,
        COS,
        TAN,
        ATAN2,
        EXP,
        LOG,
        POW,
        MIN,
        MAX,
        FMA,
        IS_FINITE,
        IS_NAN,
        POPCOUNT,
        CLZ,
        CTZ
    }

    /**
     * Immutable constant lookup table extracted from class static initializers ({@code <clinit>}),
     * such as Perlin permutation tables.
     */
    public record ConceptLut(
            String ownerClass,
            String fieldName,
            String symbolName,
            ConceptType elementType,
            List<Number> values
    ) {
        public ConceptLut {
            Objects.requireNonNull(ownerClass, "ownerClass");
            Objects.requireNonNull(fieldName, "fieldName");
            Objects.requireNonNull(symbolName, "symbolName");
            Objects.requireNonNull(elementType, "elementType");
            values = List.copyOf(values);
        }

        /**
         * Returns a deterministic bit-exact deduplication key over {@link #elementType()} and
         * {@link #values()} so identical constant lookup tables collapse into a single emitted
         * constant array across CPU and GPU backends.
         */
        public String contentKey() {
            StringBuilder sb = new StringBuilder(values.size() * 12 + 32);
            sb.append(elementType.name()).append(':').append(values.size()).append(':');
            for (Number n : values) {
                switch (elementType) {
                    case F64 -> sb.append(Double.doubleToRawLongBits(n.doubleValue())).append(',');
                    case F32 -> sb.append(Float.floatToRawIntBits(n.floatValue())).append(',');
                    case I64 -> sb.append(n.longValue()).append(',');
                    default -> sb.append(n.intValue()).append(',');
                }
            }
            return elementType.name() + ":" + values.size() + ":" + sha256Prefix16(sb.toString());
        }

        /**
         * Converts a floating-point LUT into a 64-bit Q32.32 fixed-point ({@link ConceptType#I64})
         * LUT using {@code round(val * 2^32)} for zero-FP64 coordinate arithmetic.
         */
        public ConceptLut toQ32ScaledLut(String scaledSymbolName) {
            Objects.requireNonNull(scaledSymbolName, "scaledSymbolName");
            List<Number> scaled = new ArrayList<>(values.size());
            for (Number n : values) {
                double d = n.doubleValue();
                long q32 = Math.round(d * 4294967296.0);
                scaled.add(q32);
            }
            return new ConceptLut(ownerClass, fieldName + "_q32", scaledSymbolName, ConceptType.I64, scaled);
        }
    }

    /**
     * Typed parameter declaration for a {@link ConceptFunction} or {@link ConceptKernel3D}.
     */
    public record ConceptParam(String name, ConceptType type, int jvmSlot) {
        public ConceptParam {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * Sealed expression hierarchy representing stackless SSA value computations.
     */
    public sealed interface ConceptExpr permits
            ConceptExpr.Var,
            ConceptExpr.IntConst,
            ConceptExpr.LongConst,
            ConceptExpr.FloatConst,
            ConceptExpr.DoubleConst,
            ConceptExpr.BoolConst,
            ConceptExpr.BinaryOp,
            ConceptExpr.UnaryOp,
            ConceptExpr.Cast,
            ConceptExpr.Ternary,
            ConceptExpr.MathIntrinsic,
            ConceptExpr.LutLoad,
            ConceptExpr.Call,
            ConceptExpr.CompareTemp {

        ConceptType type();

        record Var(String name, ConceptType type) implements ConceptExpr {
            public Var {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(type, "type");
            }
        }

        record IntConst(int value) implements ConceptExpr {
            @Override
            public ConceptType type() {
                return ConceptType.I32;
            }
        }

        record LongConst(long value) implements ConceptExpr {
            @Override
            public ConceptType type() {
                return ConceptType.I64;
            }
        }

        record FloatConst(float value) implements ConceptExpr {
            @Override
            public ConceptType type() {
                return ConceptType.F32;
            }
        }

        record DoubleConst(double value) implements ConceptExpr {
            @Override
            public ConceptType type() {
                return ConceptType.F64;
            }
        }

        record BoolConst(boolean value) implements ConceptExpr {
            @Override
            public ConceptType type() {
                return ConceptType.BOOL;
            }
        }

        record BinaryOp(
                BinaryOperator op,
                ConceptExpr left,
                ConceptExpr right,
                ConceptType type
        ) implements ConceptExpr {
            public BinaryOp {
                Objects.requireNonNull(op, "op");
                Objects.requireNonNull(left, "left");
                Objects.requireNonNull(right, "right");
                Objects.requireNonNull(type, "type");
            }
        }

        record UnaryOp(
                UnaryOperator op,
                ConceptExpr operand,
                ConceptType type
        ) implements ConceptExpr {
            public UnaryOp {
                Objects.requireNonNull(op, "op");
                Objects.requireNonNull(operand, "operand");
                Objects.requireNonNull(type, "type");
            }
        }

        record Cast(
                ConceptType targetType,
                ConceptExpr operand
        ) implements ConceptExpr {
            public Cast {
                Objects.requireNonNull(targetType, "targetType");
                Objects.requireNonNull(operand, "operand");
            }

            @Override
            public ConceptType type() {
                return targetType;
            }
        }

        record Ternary(
                ConceptExpr condition,
                ConceptExpr thenExpr,
                ConceptExpr elseExpr,
                ConceptType type
        ) implements ConceptExpr {
            public Ternary {
                Objects.requireNonNull(condition, "condition");
                Objects.requireNonNull(thenExpr, "thenExpr");
                Objects.requireNonNull(elseExpr, "elseExpr");
                Objects.requireNonNull(type, "type");
            }
        }

        record MathIntrinsic(
                MathIntrinsicKind kind,
                List<ConceptExpr> arguments,
                ConceptType type
        ) implements ConceptExpr {
            public MathIntrinsic {
                Objects.requireNonNull(kind, "kind");
                arguments = List.copyOf(arguments);
                Objects.requireNonNull(type, "type");
            }
        }

        record LutLoad(
                String lutSymbol,
                ConceptExpr index,
                ConceptType elementType
        ) implements ConceptExpr {
            public LutLoad {
                Objects.requireNonNull(lutSymbol, "lutSymbol");
                Objects.requireNonNull(index, "index");
                Objects.requireNonNull(elementType, "elementType");
            }

            @Override
            public ConceptType type() {
                return elementType;
            }
        }

        record Call(
                String ownerClass,
                String methodName,
                String descriptor,
                List<ConceptExpr> arguments,
                ConceptType returnType
        ) implements ConceptExpr {
            public Call {
                Objects.requireNonNull(ownerClass, "ownerClass");
                Objects.requireNonNull(methodName, "methodName");
                Objects.requireNonNull(descriptor, "descriptor");
                arguments = List.copyOf(arguments);
                Objects.requireNonNull(returnType, "returnType");
            }

            @Override
            public ConceptType type() {
                return returnType;
            }

            public String targetKey() {
                return ownerClass + "#" + methodName + descriptor;
            }
        }

        /**
         * Ephemeral stack token created by {@code lcmp}, {@code fcmpl}, {@code fcmpg},
         * {@code dcmpl}, and {@code dcmpg} before consumption by a conditional branch opcode.
         */
        record CompareTemp(
                ConceptExpr left,
                ConceptExpr right,
                boolean nanPositive
        ) implements ConceptExpr {
            @Override
            public ConceptType type() {
                return ConceptType.I32;
            }
        }
    }

    /**
     * Sealed structured statement hierarchy (zero {@code goto} statements).
     */
    public sealed interface ConceptStmt permits
            ConceptStmt.VarDecl,
            ConceptStmt.Assign,
            ConceptStmt.If,
            ConceptStmt.While,
            ConceptStmt.Return,
            ConceptStmt.StoreBuffer3D {

        record VarDecl(
                String name,
                ConceptType type,
                ConceptExpr initializer
        ) implements ConceptStmt {
            public VarDecl {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(type, "type");
            }
        }

        record Assign(
                String name,
                ConceptType type,
                ConceptExpr value
        ) implements ConceptStmt {
            public Assign {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(type, "type");
                Objects.requireNonNull(value, "value");
            }
        }

        record If(
                ConceptExpr condition,
                List<ConceptStmt> thenBody,
                List<ConceptStmt> elseBody
        ) implements ConceptStmt {
            public If {
                Objects.requireNonNull(condition, "condition");
                thenBody = List.copyOf(thenBody);
                elseBody = List.copyOf(elseBody);
            }
        }

        record While(
                ConceptExpr condition,
                List<ConceptStmt> body
        ) implements ConceptStmt {
            public While {
                Objects.requireNonNull(condition, "condition");
                body = List.copyOf(body);
            }
        }

        record Return(ConceptExpr value) implements ConceptStmt {
        }

        record StoreBuffer3D(
                String bufferName,
                ConceptExpr linearIndex,
                ConceptExpr value
        ) implements ConceptStmt {
            public StoreBuffer3D {
                Objects.requireNonNull(bufferName, "bufferName");
                Objects.requireNonNull(linearIndex, "linearIndex");
                Objects.requireNonNull(value, "value");
            }
        }
    }

    /**
     * Lifted stackless function unit with declared parameters, local variable types, and
     * structured statement body.
     */
    public record ConceptFunction(
            String ownerClass,
            String name,
            String descriptor,
            List<ConceptParam> parameters,
            ConceptType returnType,
            Map<String, ConceptType> localTypes,
            List<ConceptStmt> body,
            boolean pureCompute
    ) {
        public ConceptFunction {
            Objects.requireNonNull(ownerClass, "ownerClass");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(returnType, "returnType");
            localTypes = Collections.unmodifiableMap(new LinkedHashMap<>(localTypes));
            body = List.copyOf(body);
        }

        public String key() {
            return ownerClass + "#" + name + descriptor;
        }
    }

    /**
     * Data-parallel 3D voxel/grid kernel descriptor mapping work-item coordinates
     * {@code (gx, gy, gz)} into an output section buffer.
     */
    public record ConceptKernel3D(
            String kernelName,
            ConceptFunction targetFunction,
            String outputBufferParam,
            String originXParam,
            String originYParam,
            String originZParam,
            String scaleParam,
            List<ConceptParam> extraUniformParams
    ) {
        public ConceptKernel3D {
            Objects.requireNonNull(kernelName, "kernelName");
            Objects.requireNonNull(targetFunction, "targetFunction");
            Objects.requireNonNull(outputBufferParam, "outputBufferParam");
            extraUniformParams = List.copyOf(extraUniformParams);
        }
    }

    /**
     * Target-independent batched 3D section/corner evaluation descriptor derived from a
     * {@link ConceptModule} and {@link ConceptBackendEmitter.BatchLayout} so CPU
     * ({@link JavaConceptBackendEmitter}) and GPU ({@link OpenClBackendEmitter}) backends lower
     * identical batch coordinate geometries and output buffer layouts.
     */
    public record ConceptBatchSpec(
            ConceptBackendEmitter.BatchLayout layout,
            String kernelName,
            ConceptFunction targetFunction,
            int stepX,
            int stepY,
            int stepZ,
            int sectionElements,
            List<ConceptParam> extraUniformParams
    ) {
        public ConceptBatchSpec {
            Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(kernelName, "kernelName");
            Objects.requireNonNull(targetFunction, "targetFunction");
            if (stepX <= 0 || stepY <= 0 || stepZ <= 0) {
                throw new IllegalArgumentException(
                        "Batch strides must be > 0: (" + stepX + ", " + stepY + ", " + stepZ + ")");
            }
            if (sectionElements < 0) {
                throw new IllegalArgumentException("sectionElements must be >= 0: " + sectionElements);
            }
            extraUniformParams = List.copyOf(extraUniformParams);
        }
    }

    /**
     * Workgroup-local scratch memory buffer declaration ({@code __local} SRAM on OpenCL GPUs),
     * bounded to {@code < 8 KiB} per workgroup to preserve multi-workgroup SM occupancy.
     */
    public record ConceptLocalBuffer(
            String name,
            ConceptType elementType,
            int length,
            int elementByteWidth
    ) {
        public ConceptLocalBuffer {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(elementType, "elementType");
            if (length <= 0) {
                throw new IllegalArgumentException("length must be > 0: " + length);
            }
            if (elementByteWidth <= 0) {
                throw new IllegalArgumentException("elementByteWidth must be > 0: " + elementByteWidth);
            }
        }

        public int byteSize() {
            return length * elementByteWidth;
        }
    }

    /**
     * Multi-stage GPU workgroup pipeline descriptor ({@code Stages K1 -> K2 -> K3}) lowered with
     * {@code barrier(CLK_LOCAL_MEM_FENCE)} synchronization between stages and bounded
     * {@code < 8 KiB} {@code __local} SRAM buffers.
     */
    public record ConceptWorkgroupPipeline(
            String kernelName,
            int workgroupSize,
            String cornerEvalFunctionName,
            List<ConceptLocalBuffer> localBuffers,
            List<ConceptFunction> stageFunctions
    ) {
        public static final int MAX_WORKGROUP_LOCAL_SRAM_BYTES = 8192;

        public ConceptWorkgroupPipeline {
            Objects.requireNonNull(kernelName, "kernelName");
            Objects.requireNonNull(cornerEvalFunctionName, "cornerEvalFunctionName");
            localBuffers = List.copyOf(localBuffers);
            stageFunctions = List.copyOf(stageFunctions);
            int totalBytes = 0;
            for (ConceptLocalBuffer buf : localBuffers) {
                totalBytes += buf.byteSize();
            }
            if (totalBytes > MAX_WORKGROUP_LOCAL_SRAM_BYTES) {
                throw new IllegalArgumentException(
                        "Workgroup __local SRAM budget exceeded: " + totalBytes + " B > " + MAX_WORKGROUP_LOCAL_SRAM_BYTES + " B");
            }
        }

        public int totalLocalSramBytes() {
            int total = 0;
            for (ConceptLocalBuffer buf : localBuffers) {
                total += buf.byteSize();
            }
            return total;
        }
    }

    /**
     * Self-contained transpilation unit holding extracted constant LUTs, helper functions,
     * root entry function, optional 3D data-parallel GPU kernel wrapper, and optional
     * multi-stage workgroup pipeline.
     */
    public record ConceptModule(
            String moduleName,
            List<ConceptLut> lookupTables,
            List<ConceptFunction> helperFunctions,
            ConceptFunction entryFunction,
            ConceptKernel3D kernel3D,
            ConceptWorkgroupPipeline workgroupPipeline,
            List<BytecodeSsaLifter.UnmappedReason> unmappedReasons,
            Set<String> liftedMethodSymbols,
            Set<String> liftedFieldSymbols
    ) {
        public ConceptModule(
                String moduleName,
                List<ConceptLut> lookupTables,
                List<ConceptFunction> helperFunctions,
                ConceptFunction entryFunction,
                ConceptKernel3D kernel3D) {
            this(moduleName, lookupTables, helperFunctions, entryFunction, kernel3D, null, List.of(), Set.of(), Set.of());
        }

        public ConceptModule(
                String moduleName,
                List<ConceptLut> lookupTables,
                List<ConceptFunction> helperFunctions,
                ConceptFunction entryFunction,
                ConceptKernel3D kernel3D,
                ConceptWorkgroupPipeline workgroupPipeline) {
            this(
                    moduleName,
                    lookupTables,
                    helperFunctions,
                    entryFunction,
                    kernel3D,
                    workgroupPipeline,
                    List.of(),
                    Set.of(),
                    Set.of());
        }

        public ConceptModule(
                String moduleName,
                List<ConceptLut> lookupTables,
                List<ConceptFunction> helperFunctions,
                ConceptFunction entryFunction,
                ConceptKernel3D kernel3D,
                ConceptWorkgroupPipeline workgroupPipeline,
                List<BytecodeSsaLifter.UnmappedReason> unmappedReasons) {
            this(
                    moduleName,
                    lookupTables,
                    helperFunctions,
                    entryFunction,
                    kernel3D,
                    workgroupPipeline,
                    unmappedReasons,
                    Set.of(),
                    Set.of());
        }

        public ConceptModule {
            Objects.requireNonNull(moduleName, "moduleName");
            lookupTables = List.copyOf(lookupTables);
            helperFunctions = List.copyOf(helperFunctions);
            Objects.requireNonNull(entryFunction, "entryFunction");
            unmappedReasons = unmappedReasons == null ? List.of() : List.copyOf(unmappedReasons);
            liftedMethodSymbols = liftedMethodSymbols == null ? Set.of() : Set.copyOf(liftedMethodSymbols);
            liftedFieldSymbols = liftedFieldSymbols == null ? Set.of() : Set.copyOf(liftedFieldSymbols);
        }

        public boolean isPureCompute() {
            if (!entryFunction.pureCompute() || !unmappedReasons.isEmpty()) {
                return false;
            }
            for (ConceptFunction helper : helperFunctions) {
                if (!helper.pureCompute()) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Deterministic interpreter for {@link ConceptModule} functions to verify numerical parity
     * against JVM execution before and after optimization passes.
     */
    public static final class ConceptIrEvaluator {

        private ConceptIrEvaluator() {
        }

        public static Object evaluateEntry(ConceptModule module, Object... args) {
            return evaluateFunction(module, module.entryFunction(), args);
        }

        public static Object evaluateFunction(
                ConceptModule module,
                ConceptFunction fn,
                Object... args) {
            Map<String, List<Number>> luts = new HashMap<>();
            for (ConceptLut lut : module.lookupTables()) {
                luts.put(lut.symbolName(), lut.values());
            }
            Map<String, ConceptFunction> fnMap = new HashMap<>();
            for (ConceptFunction helper : module.helperFunctions()) {
                fnMap.put(helper.key(), helper);
                fnMap.put(helper.name(), helper);
            }
            fnMap.put(fn.key(), fn);
            fnMap.put(fn.name(), fn);
            return executeFunction(fn, args, luts, fnMap);
        }

        private static Object executeFunction(
                ConceptFunction fn,
                Object[] args,
                Map<String, List<Number>> luts,
                Map<String, ConceptFunction> fnMap) {
            Map<String, Object> env = new HashMap<>();
            for (Map.Entry<String, ConceptType> entry : fn.localTypes().entrySet()) {
                env.put(entry.getKey(), defaultValue(entry.getValue()));
            }
            List<ConceptParam> params = fn.parameters();
            for (int i = 0; i < params.size() && i < args.length; i++) {
                env.put(params.get(i).name(), coerceType(args[i], params.get(i).type()));
            }
            ExecSignal signal = executeBlock(fn.body(), env, luts, fnMap);
            return signal.returned ? coerceType(signal.value, fn.returnType()) : defaultValue(fn.returnType());
        }

        private record ExecSignal(boolean returned, Object value) {
            static final ExecSignal CONTINUE = new ExecSignal(false, null);
        }

        private static ExecSignal executeBlock(
                List<ConceptStmt> stmts,
                Map<String, Object> env,
                Map<String, List<Number>> luts,
                Map<String, ConceptFunction> fnMap) {
            for (ConceptStmt stmt : stmts) {
                if (stmt instanceof ConceptStmt.VarDecl decl) {
                    Object val = decl.initializer() != null
                            ? evalExpr(decl.initializer(), env, luts, fnMap)
                            : defaultValue(decl.type());
                    env.put(decl.name(), coerceType(val, decl.type()));
                } else if (stmt instanceof ConceptStmt.Assign assign) {
                    Object val = evalExpr(assign.value(), env, luts, fnMap);
                    env.put(assign.name(), coerceType(val, assign.type()));
                } else if (stmt instanceof ConceptStmt.If ifStmt) {
                    boolean cond = asBoolean(evalExpr(ifStmt.condition(), env, luts, fnMap));
                    List<ConceptStmt> branch = cond ? ifStmt.thenBody() : ifStmt.elseBody();
                    ExecSignal sig = executeBlock(branch, env, luts, fnMap);
                    if (sig.returned) {
                        return sig;
                    }
                } else if (stmt instanceof ConceptStmt.While whileStmt) {
                    int guard = 0;
                    while (asBoolean(evalExpr(whileStmt.condition(), env, luts, fnMap))) {
                        if (++guard > 1_000_000) {
                            throw new IllegalStateException("ConceptIrEvaluator loop guard exceeded");
                        }
                        ExecSignal sig = executeBlock(whileStmt.body(), env, luts, fnMap);
                        if (sig.returned) {
                            return sig;
                        }
                    }
                } else if (stmt instanceof ConceptStmt.Return ret) {
                    Object val = ret.value() != null ? evalExpr(ret.value(), env, luts, fnMap) : null;
                    return new ExecSignal(true, val);
                }
            }
            return ExecSignal.CONTINUE;
        }

        private static Object evalExpr(
                ConceptExpr expr,
                Map<String, Object> env,
                Map<String, List<Number>> luts,
                Map<String, ConceptFunction> fnMap) {
            if (expr instanceof ConceptExpr.Var v) {
                return env.getOrDefault(v.name(), defaultValue(v.type()));
            }
            if (expr instanceof ConceptExpr.IntConst c) {
                return c.value();
            }
            if (expr instanceof ConceptExpr.LongConst c) {
                return c.value();
            }
            if (expr instanceof ConceptExpr.FloatConst c) {
                return c.value();
            }
            if (expr instanceof ConceptExpr.DoubleConst c) {
                return c.value();
            }
            if (expr instanceof ConceptExpr.BoolConst c) {
                return c.value();
            }
            if (expr instanceof ConceptExpr.UnaryOp u) {
                Object val = evalExpr(u.operand(), env, luts, fnMap);
                return switch (u.op()) {
                    case NEG -> switch (u.type()) {
                        case I32 -> -asInt(val);
                        case I64 -> -asLong(val);
                        case F32 -> -asFloat(val);
                        default -> -asDouble(val);
                    };
                    case LOGICAL_NOT -> !asBoolean(val);
                    case BIT_NOT -> u.type() == ConceptType.I64 ? ~asLong(val) : ~asInt(val);
                };
            }
            if (expr instanceof ConceptExpr.Cast cast) {
                Object val = evalExpr(cast.operand(), env, luts, fnMap);
                return coerceType(val, cast.targetType());
            }
            if (expr instanceof ConceptExpr.Ternary tern) {
                boolean cond = asBoolean(evalExpr(tern.condition(), env, luts, fnMap));
                Object chosen = cond
                        ? evalExpr(tern.thenExpr(), env, luts, fnMap)
                        : evalExpr(tern.elseExpr(), env, luts, fnMap);
                return coerceType(chosen, tern.type());
            }
            if (expr instanceof ConceptExpr.LutLoad load) {
                List<Number> table = luts.get(load.lutSymbol());
                if (table == null) {
                    throw new IllegalStateException("Missing LUT: " + load.lutSymbol());
                }
                int idx = asInt(evalExpr(load.index(), env, luts, fnMap));
                return coerceType(table.get(idx), load.elementType());
            }
            if (expr instanceof ConceptExpr.MathIntrinsic math) {
                List<Object> evalArgs = new ArrayList<>(math.arguments().size());
                for (ConceptExpr arg : math.arguments()) {
                    evalArgs.add(evalExpr(arg, env, luts, fnMap));
                }
                return evalMathIntrinsic(math.kind(), evalArgs, math.type());
            }
            if (expr instanceof ConceptExpr.Call call) {
                ConceptFunction target = fnMap.get(call.targetKey());
                if (target == null) {
                    target = fnMap.get(call.methodName());
                }
                if (target == null) {
                    throw new IllegalStateException("Unresolved call target in IR evaluator: " + call.targetKey());
                }
                Object[] callArgs = new Object[call.arguments().size()];
                for (int i = 0; i < callArgs.length; i++) {
                    callArgs[i] = evalExpr(call.arguments().get(i), env, luts, fnMap);
                }
                return executeFunction(target, callArgs, luts, fnMap);
            }
            if (expr instanceof ConceptExpr.BinaryOp bin) {
                if (bin.op() == BinaryOperator.LOGICAL_AND) {
                    return asBoolean(evalExpr(bin.left(), env, luts, fnMap))
                            && asBoolean(evalExpr(bin.right(), env, luts, fnMap));
                }
                if (bin.op() == BinaryOperator.LOGICAL_OR) {
                    return asBoolean(evalExpr(bin.left(), env, luts, fnMap))
                            || asBoolean(evalExpr(bin.right(), env, luts, fnMap));
                }
                Object l = evalExpr(bin.left(), env, luts, fnMap);
                Object r = evalExpr(bin.right(), env, luts, fnMap);
                return evalBinary(bin.op(), l, r, bin.left().type(), bin.type());
            }
            throw new IllegalStateException("Unsupported ConceptExpr: " + expr);
        }

        private static Object evalMathIntrinsic(
                MathIntrinsicKind kind,
                List<Object> args,
                ConceptType returnType) {
            return switch (kind) {
                case FLOOR -> Math.floor(asDouble(args.get(0)));
                case CEIL -> Math.ceil(asDouble(args.get(0)));
                case ROUND -> (returnType == ConceptType.I32 || args.get(0) instanceof Float)
                        ? Math.round(asFloat(args.get(0)))
                        : Math.round(asDouble(args.get(0)));
                case SQRT -> Math.sqrt(asDouble(args.get(0)));
                case SIN -> Math.sin(asDouble(args.get(0)));
                case COS -> Math.cos(asDouble(args.get(0)));
                case TAN -> Math.tan(asDouble(args.get(0)));
                case ATAN2 -> Math.atan2(asDouble(args.get(0)), asDouble(args.get(1)));
                case EXP -> Math.exp(asDouble(args.get(0)));
                case LOG -> Math.log(asDouble(args.get(0)));
                case POW -> Math.pow(asDouble(args.get(0)), asDouble(args.get(1)));
                case FMA -> returnType == ConceptType.F32
                        ? Math.fma(asFloat(args.get(0)), asFloat(args.get(1)), asFloat(args.get(2)))
                        : Math.fma(asDouble(args.get(0)), asDouble(args.get(1)), asDouble(args.get(2)));
                case ABS -> switch (returnType) {
                    case I32 -> Math.abs(asInt(args.get(0)));
                    case I64 -> Math.abs(asLong(args.get(0)));
                    case F32 -> Math.abs(asFloat(args.get(0)));
                    default -> Math.abs(asDouble(args.get(0)));
                };
                case MIN -> switch (returnType) {
                    case I32 -> Math.min(asInt(args.get(0)), asInt(args.get(1)));
                    case I64 -> Math.min(asLong(args.get(0)), asLong(args.get(1)));
                    case F32 -> Math.min(asFloat(args.get(0)), asFloat(args.get(1)));
                    default -> Math.min(asDouble(args.get(0)), asDouble(args.get(1)));
                };
                case MAX -> switch (returnType) {
                    case I32 -> Math.max(asInt(args.get(0)), asInt(args.get(1)));
                    case I64 -> Math.max(asLong(args.get(0)), asLong(args.get(1)));
                    case F32 -> Math.max(asFloat(args.get(0)), asFloat(args.get(1)));
                    default -> Math.max(asDouble(args.get(0)), asDouble(args.get(1)));
                };
                case IS_FINITE -> args.get(0) instanceof Float f
                        ? Float.isFinite(f)
                        : Double.isFinite(asDouble(args.get(0)));
                case IS_NAN -> args.get(0) instanceof Float f
                        ? Float.isNaN(f)
                        : Double.isNaN(asDouble(args.get(0)));
                case POPCOUNT -> args.get(0) instanceof Long l
                        ? Long.bitCount(l)
                        : Integer.bitCount(asInt(args.get(0)));
                case CLZ -> args.get(0) instanceof Long l
                        ? Long.numberOfLeadingZeros(l)
                        : Integer.numberOfLeadingZeros(asInt(args.get(0)));
                case CTZ -> args.get(0) instanceof Long l
                        ? Long.numberOfTrailingZeros(l)
                        : Integer.numberOfTrailingZeros(asInt(args.get(0)));
            };
        }

        private static Object evalBinary(
                BinaryOperator op,
                Object l,
                Object r,
                ConceptType operandType,
                ConceptType resultType) {
            if (op.isComparison()) {
                if (operandType.isFloatingPoint() || l instanceof Double || r instanceof Double
                        || l instanceof Float || r instanceof Float) {
                    double dl = asDouble(l);
                    double dr = asDouble(r);
                    return switch (op) {
                        case EQ -> dl == dr;
                        case NE -> dl != dr;
                        case LT -> dl < dr;
                        case LE -> dl <= dr;
                        case GT -> dl > dr;
                        case GE -> dl >= dr;
                        default -> false;
                    };
                }
                long ll = asLong(l);
                long lr = asLong(r);
                return switch (op) {
                    case EQ -> ll == lr;
                    case NE -> ll != lr;
                    case LT -> ll < lr;
                    case LE -> ll <= lr;
                    case GT -> ll > lr;
                    case GE -> ll >= lr;
                    default -> false;
                };
            }
            if (resultType == ConceptType.F64) {
                double dl = asDouble(l);
                double dr = asDouble(r);
                return switch (op) {
                    case ADD -> dl + dr;
                    case SUB -> dl - dr;
                    case MUL -> dl * dr;
                    case DIV -> dl / dr;
                    case REM -> dl % dr;
                    default -> throw new IllegalStateException("Invalid FP64 op: " + op);
                };
            }
            if (resultType == ConceptType.F32) {
                float fl = asFloat(l);
                float fr = asFloat(r);
                return switch (op) {
                    case ADD -> fl + fr;
                    case SUB -> fl - fr;
                    case MUL -> fl * fr;
                    case DIV -> fl / fr;
                    case REM -> fl % fr;
                    default -> throw new IllegalStateException("Invalid FP32 op: " + op);
                };
            }
            if (resultType == ConceptType.I64) {
                long ll = asLong(l);
                return switch (op) {
                    case ADD -> ll + asLong(r);
                    case SUB -> ll - asLong(r);
                    case MUL -> ll * asLong(r);
                    case DIV -> ll / asLong(r);
                    case REM -> ll % asLong(r);
                    case BIT_AND -> ll & asLong(r);
                    case BIT_OR -> ll | asLong(r);
                    case BIT_XOR -> ll ^ asLong(r);
                    case SHL -> ll << asInt(r);
                    case SHR -> ll >> asInt(r);
                    case USHR -> ll >>> asInt(r);
                    default -> throw new IllegalStateException("Invalid I64 op: " + op);
                };
            }
            int il = asInt(l);
            int ir = asInt(r);
            return switch (op) {
                case ADD -> il + ir;
                case SUB -> il - ir;
                case MUL -> il * ir;
                case DIV -> il / ir;
                case REM -> il % ir;
                case BIT_AND -> il & ir;
                case BIT_OR -> il | ir;
                case BIT_XOR -> il ^ ir;
                case SHL -> il << ir;
                case SHR -> il >> ir;
                case USHR -> il >>> ir;
                default -> throw new IllegalStateException("Invalid I32 op: " + op);
            };
        }

        private static Object defaultValue(ConceptType type) {
            return switch (type) {
                case BOOL -> Boolean.FALSE;
                case I32 -> 0;
                case I64 -> 0L;
                case F32 -> 0.0f;
                case F64 -> 0.0d;
                default -> null;
            };
        }

        private static Object coerceType(Object val, ConceptType target) {
            if (val == null) {
                return defaultValue(target);
            }
            return switch (target) {
                case BOOL -> asBoolean(val);
                case I32 -> asInt(val);
                case I64 -> asLong(val);
                case F32 -> asFloat(val);
                case F64 -> asDouble(val);
                default -> val;
            };
        }

        private static boolean asBoolean(Object val) {
            if (val instanceof Boolean b) {
                return b;
            }
            if (val instanceof Number n) {
                return n.intValue() != 0;
            }
            return false;
        }

        private static int asInt(Object val) {
            if (val instanceof Number n) {
                return n.intValue();
            }
            if (val instanceof Boolean b) {
                return b ? 1 : 0;
            }
            throw new IllegalArgumentException("Cannot coerce to int: " + val);
        }

        private static long asLong(Object val) {
            if (val instanceof Number n) {
                return n.longValue();
            }
            if (val instanceof Boolean b) {
                return b ? 1L : 0L;
            }
            throw new IllegalArgumentException("Cannot coerce to long: " + val);
        }

        private static float asFloat(Object val) {
            if (val instanceof Number n) {
                return n.floatValue();
            }
            throw new IllegalArgumentException("Cannot coerce to float: " + val);
        }

        private static double asDouble(Object val) {
            if (val instanceof Number n) {
                return n.doubleValue();
            }
            throw new IllegalArgumentException("Cannot coerce to double: " + val);
        }
    }

    /**
     * Concrete symbolic counterexample discovered when a candidate {@link ConceptModule} diverges
     * from a reference specification.
     */
    public record Counterexample(
            List<Object> inputArguments,
            Object expectedOutput,
            Object actualOutput,
            String detail
    ) {
        public Counterexample {
            inputArguments = List.copyOf(inputArguments);
            Objects.requireNonNull(detail, "detail");
        }
    }

    /**
     * Result of neuro-symbolic canonical equivalence and bounded CEGIS verification between two
     * {@link ConceptModule} implementations.
     */
    public record EquivalenceReport(
            boolean equivalent,
            boolean structurallyIdentical,
            String referenceCanonicalHash,
            String candidateCanonicalHash,
            int vectorsTested,
            Counterexample counterexample
    ) {
        public EquivalenceReport {
            Objects.requireNonNull(referenceCanonicalHash, "referenceCanonicalHash");
            Objects.requireNonNull(candidateCanonicalHash, "candidateCanonicalHash");
        }

        /**
         * Formats a compact 1-line CEGIS prompt diagnostic suitable for feeding directly back into
         * an LLM repair turn when equivalence fails.
         */
        public String toCegisFeedbackPrompt() {
            if (equivalent) {
                return "CEGIS_EQUIVALENT(structural=" + structurallyIdentical
                        + ", hash=" + candidateCanonicalHash
                        + ", vectors=" + vectorsTested + ")";
            }
            if (counterexample == null) {
                return "CEGIS_MISMATCH(refHash=" + referenceCanonicalHash
                        + ", candHash=" + candidateCanonicalHash + ")";
            }
            return "CEGIS_COUNTEREXAMPLE: inputs=" + counterexample.inputArguments()
                    + " -> expected=" + counterexample.expectedOutput()
                    + " != actual=" + counterexample.actualOutput()
                    + " (" + counterexample.detail() + ")";
        }
    }

    /**
     * Computes a deterministic 16-hex-character SHA-256 structural hash of a {@link ConceptFunction}
     * modulo parameter/variable renaming (alpha-equivalence), commutative operand ordering, and
     * relational comparison direction.
     */
    public static String canonicalHash(ConceptFunction fn) {
        Objects.requireNonNull(fn, "fn");
        return sha256Prefix16(canonicalFunctionForm(fn));
    }

    /**
     * Computes a deterministic 16-hex-character SHA-256 structural hash of an entire
     * {@link ConceptModule} (optimized entry function + referenced lookup tables).
     */
    public static String canonicalModuleHash(ConceptModule module) {
        Objects.requireNonNull(module, "module");
        ConceptModule opt = ConceptOptimizerPass.optimize(module);
        StringBuilder sb = new StringBuilder(512);
        for (ConceptLut lut : opt.lookupTables()) {
            sb.append("LUT:")
                    .append(lut.elementType())
                    .append(':')
                    .append(lut.values())
                    .append('\n');
        }
        sb.append(canonicalFunctionForm(opt.entryFunction()));
        return sha256Prefix16(sb.toString());
    }

    /**
     * Returns the human-readable alpha-normalized canonical representation of {@code fn}.
     */
    public static String canonicalFunctionForm(ConceptFunction fn) {
        Objects.requireNonNull(fn, "fn");
        Map<String, String> alphaMap = new HashMap<>();
        StringBuilder sb = new StringBuilder(256);
        sb.append("fn(");
        List<ConceptParam> params = fn.parameters();
        for (int i = 0; i < params.size(); i++) {
            ConceptParam p = params.get(i);
            String canonicalParam = "$p" + i;
            alphaMap.put(p.name(), canonicalParam);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(p.type().name()).append(':').append(canonicalParam);
        }
        sb.append(")->").append(fn.returnType().name()).append('{');
        int[] nextLocalIdx = {0};
        ConceptExpr symRet = trySymbolicReturnExpr(fn.body(), new HashMap<>(), 0, new int[] {256});
        if (symRet != null) {
            sb.append("ret:").append(canonicalExprForm(symRet, alphaMap, nextLocalIdx)).append(';');
        } else {
            for (ConceptStmt stmt : fn.body()) {
                appendCanonicalStmt(sb, stmt, alphaMap, nextLocalIdx);
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Symbolically evaluates a loop-free statement list into a single canonical return
     * {@link ConceptExpr} by substituting local variable assignments and folding early-return
     * guards and diamond branches into {@link ConceptExpr.Ternary} nodes. Returns {@code null} if
     * the block contains loops, buffer stores, or exceeds the symbolic depth bound.
     */
    private static ConceptExpr trySymbolicReturnExpr(
            List<ConceptStmt> stmts,
            Map<String, ConceptExpr> env,
            int depth,
            int[] nodeBudget) {
        if (depth > 8 || nodeBudget[0] <= 0) {
            return null;
        }
        for (int i = 0; i < stmts.size(); i++) {
            if (--nodeBudget[0] <= 0) {
                return null;
            }
            ConceptStmt stmt = stmts.get(i);
            if (stmt instanceof ConceptStmt.VarDecl decl) {
                if (decl.initializer() != null) {
                    ConceptExpr sub = substituteSymbolicExpr(decl.initializer(), env, 0, nodeBudget);
                    if (sub == null) {
                        return null;
                    }
                    env.put(decl.name(), sub);
                }
            } else if (stmt instanceof ConceptStmt.Assign assign) {
                ConceptExpr sub = substituteSymbolicExpr(assign.value(), env, 0, nodeBudget);
                if (sub == null) {
                    return null;
                }
                env.put(assign.name(), sub);
            } else if (stmt instanceof ConceptStmt.Return ret) {
                if (ret.value() == null) {
                    return new ConceptExpr.IntConst(0);
                }
                return substituteSymbolicExpr(ret.value(), env, 0, nodeBudget);
            } else if (stmt instanceof ConceptStmt.If ifStmt) {
                ConceptExpr condSub = substituteSymbolicExpr(ifStmt.condition(), env, 0, nodeBudget);
                if (condSub == null) {
                    return null;
                }
                List<ConceptStmt> rest = stmts.subList(i + 1, stmts.size());
                List<ConceptStmt> thenContinuation = new ArrayList<>(ifStmt.thenBody().size() + rest.size());
                thenContinuation.addAll(ifStmt.thenBody());
                thenContinuation.addAll(rest);

                List<ConceptStmt> elseContinuation = new ArrayList<>(ifStmt.elseBody().size() + rest.size());
                elseContinuation.addAll(ifStmt.elseBody());
                elseContinuation.addAll(rest);

                ConceptExpr thenRet = trySymbolicReturnExpr(thenContinuation, new HashMap<>(env), depth + 1, nodeBudget);
                ConceptExpr elseRet = trySymbolicReturnExpr(elseContinuation, new HashMap<>(env), depth + 1, nodeBudget);
                if (thenRet == null || elseRet == null) {
                    return null;
                }
                if (thenRet.equals(elseRet)) {
                    return thenRet;
                }
                return new ConceptExpr.Ternary(condSub, thenRet, elseRet, thenRet.type());
            } else {
                // While loops or StoreBuffer3D fall back to statement-level canonical form
                return null;
            }
        }
        return null;
    }

    private static ConceptExpr substituteSymbolicExpr(
            ConceptExpr expr,
            Map<String, ConceptExpr> env,
            int depth,
            int[] nodeBudget) {
        if (depth > 32 || --nodeBudget[0] <= 0) {
            return null;
        }
        if (expr instanceof ConceptExpr.Var v) {
            return env.getOrDefault(v.name(), v);
        }
        if (expr instanceof ConceptExpr.IntConst
                || expr instanceof ConceptExpr.LongConst
                || expr instanceof ConceptExpr.FloatConst
                || expr instanceof ConceptExpr.DoubleConst
                || expr instanceof ConceptExpr.BoolConst) {
            return expr;
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            ConceptExpr sub = substituteSymbolicExpr(u.operand(), env, depth + 1, nodeBudget);
            return sub == null ? null : new ConceptExpr.UnaryOp(u.op(), sub, u.type());
        }
        if (expr instanceof ConceptExpr.BinaryOp b) {
            ConceptExpr l = substituteSymbolicExpr(b.left(), env, depth + 1, nodeBudget);
            ConceptExpr r = substituteSymbolicExpr(b.right(), env, depth + 1, nodeBudget);
            return (l == null || r == null) ? null : new ConceptExpr.BinaryOp(b.op(), l, r, b.type());
        }
        if (expr instanceof ConceptExpr.Cast c) {
            ConceptExpr sub = substituteSymbolicExpr(c.operand(), env, depth + 1, nodeBudget);
            return sub == null ? null : new ConceptExpr.Cast(c.targetType(), sub);
        }
        if (expr instanceof ConceptExpr.Ternary t) {
            ConceptExpr cond = substituteSymbolicExpr(t.condition(), env, depth + 1, nodeBudget);
            ConceptExpr th = substituteSymbolicExpr(t.thenExpr(), env, depth + 1, nodeBudget);
            ConceptExpr el = substituteSymbolicExpr(t.elseExpr(), env, depth + 1, nodeBudget);
            return (cond == null || th == null || el == null)
                    ? null
                    : new ConceptExpr.Ternary(cond, th, el, t.type());
        }
        if (expr instanceof ConceptExpr.LutLoad lut) {
            ConceptExpr idx = substituteSymbolicExpr(lut.index(), env, depth + 1, nodeBudget);
            return idx == null ? null : new ConceptExpr.LutLoad(lut.lutSymbol(), idx, lut.elementType());
        }
        if (expr instanceof ConceptExpr.MathIntrinsic m) {
            List<ConceptExpr> subArgs = new ArrayList<>(m.arguments().size());
            for (ConceptExpr a : m.arguments()) {
                ConceptExpr sa = substituteSymbolicExpr(a, env, depth + 1, nodeBudget);
                if (sa == null) {
                    return null;
                }
                subArgs.add(sa);
            }
            return new ConceptExpr.MathIntrinsic(m.kind(), subArgs, m.type());
        }
        if (expr instanceof ConceptExpr.Call call) {
            List<ConceptExpr> subArgs = new ArrayList<>(call.arguments().size());
            for (ConceptExpr a : call.arguments()) {
                ConceptExpr sa = substituteSymbolicExpr(a, env, depth + 1, nodeBudget);
                if (sa == null) {
                    return null;
                }
                subArgs.add(sa);
            }
            return new ConceptExpr.Call(
                    call.ownerClass(),
                    call.methodName(),
                    call.descriptor(),
                    subArgs,
                    call.returnType());
        }
        if (expr instanceof ConceptExpr.CompareTemp cmp) {
            ConceptExpr l = substituteSymbolicExpr(cmp.left(), env, depth + 1, nodeBudget);
            ConceptExpr r = substituteSymbolicExpr(cmp.right(), env, depth + 1, nodeBudget);
            return (l == null || r == null) ? null : new ConceptExpr.CompareTemp(l, r, cmp.nanPositive());
        }
        return expr;
    }

    /**
     * Verifies functional equivalence between {@code reference} and {@code candidate} by first
     * canonicalizing both modules through {@link ConceptOptimizerPass} (checking structural DAG
     * identity) and then sweeping deterministic boundary, bit-pattern, and seeded property vectors
     * through {@link ConceptIrEvaluator}.
     */
    public static EquivalenceReport verifyEquivalence(
            ConceptModule reference,
            ConceptModule candidate) {
        return verifyEquivalence(reference, candidate, 1e-9);
    }

    /**
     * Verifies functional equivalence between {@code reference} and {@code candidate} within
     * {@code fpTolerance} for floating-point outputs.
     */
    public static EquivalenceReport verifyEquivalence(
            ConceptModule reference,
            ConceptModule candidate,
            double fpTolerance) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(candidate, "candidate");

        ConceptModule refOpt = ConceptOptimizerPass.optimize(reference);
        ConceptModule candOpt = ConceptOptimizerPass.optimize(candidate);
        ConceptFunction refFn = refOpt.entryFunction();
        ConceptFunction candFn = candOpt.entryFunction();

        String refHash = canonicalHash(refFn);
        String candHash = canonicalHash(candFn);

        if (refFn.parameters().size() != candFn.parameters().size()
                || refFn.returnType() != candFn.returnType()) {
            return new EquivalenceReport(
                    false,
                    false,
                    refHash,
                    candHash,
                    0,
                    new Counterexample(
                            List.of(),
                            refFn.parameters().size() + " params -> " + refFn.returnType(),
                            candFn.parameters().size() + " params -> " + candFn.returnType(),
                            "Signature arity or return type mismatch"));
        }
        for (int i = 0; i < refFn.parameters().size(); i++) {
            ConceptType rt = refFn.parameters().get(i).type();
            ConceptType ct = candFn.parameters().get(i).type();
            if (rt != ct) {
                return new EquivalenceReport(
                        false,
                        false,
                        refHash,
                        candHash,
                        0,
                        new Counterexample(
                                List.of(),
                                "param[" + i + "]=" + rt,
                                "param[" + i + "]=" + ct,
                                "Parameter type mismatch at index " + i));
            }
        }

        boolean lutsIdentical = Objects.equals(refOpt.lookupTables(), candOpt.lookupTables());
        boolean structurallyIdentical = refHash.equals(candHash) && lutsIdentical;

        List<Object[]> probeVectors = buildCegisProbeVectors(refFn.parameters());
        int tested = 0;
        for (Object[] vec : probeVectors) {
            tested++;
            Object expectedVal = ConceptIrEvaluator.evaluateEntry(refOpt, vec);
            Object actualVal = ConceptIrEvaluator.evaluateEntry(candOpt, vec);
            if (!outputsMatch(expectedVal, actualVal, fpTolerance)) {
                List<String> namedInputs = new ArrayList<>(vec.length);
                for (int i = 0; i < vec.length; i++) {
                    namedInputs.add(refFn.parameters().get(i).name() + "=" + vec[i]);
                }
                return new EquivalenceReport(
                        false,
                        false,
                        refHash,
                        candHash,
                        tested,
                        new Counterexample(
                                List.of(vec),
                                expectedVal,
                                actualVal,
                                "Divergence at (" + String.join(", ", namedInputs) + ")"));
            }
        }

        return new EquivalenceReport(
                true,
                structurallyIdentical,
                refHash,
                candHash,
                tested,
                null);
    }

    private static List<Object[]> buildCegisProbeVectors(List<ConceptParam> params) {
        int arity = params.size();
        if (arity == 0) {
            return Collections.<Object[]>singletonList(new Object[0]);
        }
        long[] intAnchors = {
            0L, 1L, -1L, 2L, -2L, 7L, -7L, 16L, -16L, 63L, 64L, 255L, 256L,
            -256L, 1024L, -1024L, (long) Integer.MAX_VALUE, (long) Integer.MIN_VALUE,
            0x9E3779B97F4A7C15L, -0x517CC1B727220A95L
        };
        double[] fpAnchors = {
            0.0, 1.0, -1.0, 0.5, -0.5, 0.25, 0.75, 1.5, -1.5, 2.25, -3.75,
            15.875, -16.125, 64.0, -64.0, 255.5, 1e-6, -1e-6, 1e6, -1e6
        };

        List<Object[]> vectors = new ArrayList<>(48);
        // 1. Axis-aligned & diagonal sweeps across anchor tables
        int anchorCount = Math.max(intAnchors.length, fpAnchors.length);
        for (int idx = 0; idx < anchorCount; idx++) {
            Object[] row = new Object[arity];
            for (int p = 0; p < arity; p++) {
                int shifted = (idx + p * 3) % anchorCount;
                row[p] = coerceProbeScalar(params.get(p).type(), intAnchors[shifted % intAnchors.length], fpAnchors[shifted % fpAnchors.length]);
            }
            vectors.add(row);
        }
        // 2. Single-axis perturbations (one parameter varied while others are 0 or 1)
        for (int p = 0; p < arity; p++) {
            for (int k = 0; k < 4; k++) {
                Object[] row = new Object[arity];
                for (int j = 0; j < arity; j++) {
                    int anchorIdx = (j == p) ? (k + 1) * 3 : (k % 2);
                    row[j] = coerceProbeScalar(
                            params.get(j).type(),
                            intAnchors[anchorIdx % intAnchors.length],
                            fpAnchors[anchorIdx % fpAnchors.length]);
                }
                vectors.add(row);
            }
        }
        // 3. Deterministic SplitMix64 pseudo-random property vectors
        long state = 0x6A09E667F3BCC908L;
        for (int s = 0; s < 16; s++) {
            Object[] row = new Object[arity];
            for (int p = 0; p < arity; p++) {
                state += 0x9E3779B97F4A7C15L;
                long z = state;
                z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
                z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
                z = z ^ (z >>> 31);
                double unit = ((z >>> 11) * 0x1.0p-53) * 200.0 - 100.0;
                row[p] = coerceProbeScalar(params.get(p).type(), z, unit);
            }
            vectors.add(row);
        }
        return vectors;
    }

    private static Object coerceProbeScalar(ConceptType type, long intVal, double fpVal) {
        return switch (type) {
            case BOOL -> (intVal & 1L) != 0L;
            case I32 -> (int) intVal;
            case I64 -> intVal;
            case F32 -> (float) fpVal;
            case F64 -> fpVal;
            default -> 0;
        };
    }

    private static boolean outputsMatch(Object expected, Object actual, double fpTolerance) {
        if (Objects.equals(expected, actual)) {
            return true;
        }
        if (expected instanceof Number en && actual instanceof Number an) {
            double ed = en.doubleValue();
            double ad = an.doubleValue();
            if (Double.isNaN(ed) && Double.isNaN(ad)) {
                return true;
            }
            if (Double.isInfinite(ed) || Double.isInfinite(ad)) {
                return Double.compare(ed, ad) == 0;
            }
            if (expected instanceof Float || expected instanceof Double
                    || actual instanceof Float || actual instanceof Double) {
                return Math.abs(ed - ad) <= fpTolerance;
            }
            return en.longValue() == an.longValue();
        }
        return false;
    }

    private static void appendCanonicalStmt(
            StringBuilder sb,
            ConceptStmt stmt,
            Map<String, String> alphaMap,
            int[] nextLocalIdx) {
        if (stmt instanceof ConceptStmt.VarDecl decl) {
            String cname = alphaMap.computeIfAbsent(decl.name(), k -> "$v" + (nextLocalIdx[0]++));
            sb.append("var:").append(decl.type().name()).append(':').append(cname);
            if (decl.initializer() != null) {
                sb.append('=').append(canonicalExprForm(decl.initializer(), alphaMap, nextLocalIdx));
            }
            sb.append(';');
        } else if (stmt instanceof ConceptStmt.Assign assign) {
            String valForm = canonicalExprForm(assign.value(), alphaMap, nextLocalIdx);
            String cname = alphaMap.computeIfAbsent(assign.name(), k -> "$v" + (nextLocalIdx[0]++));
            sb.append(cname).append('=').append(valForm).append(';');
        } else if (stmt instanceof ConceptStmt.If ifStmt) {
            sb.append("if(")
                    .append(canonicalExprForm(ifStmt.condition(), alphaMap, nextLocalIdx))
                    .append("){");
            for (ConceptStmt t : ifStmt.thenBody()) {
                appendCanonicalStmt(sb, t, alphaMap, nextLocalIdx);
            }
            sb.append('}');
            if (!ifStmt.elseBody().isEmpty()) {
                sb.append("else{");
                for (ConceptStmt e : ifStmt.elseBody()) {
                    appendCanonicalStmt(sb, e, alphaMap, nextLocalIdx);
                }
                sb.append('}');
            }
        } else if (stmt instanceof ConceptStmt.While whileStmt) {
            sb.append("while(")
                    .append(canonicalExprForm(whileStmt.condition(), alphaMap, nextLocalIdx))
                    .append("){");
            for (ConceptStmt b : whileStmt.body()) {
                appendCanonicalStmt(sb, b, alphaMap, nextLocalIdx);
            }
            sb.append('}');
        } else if (stmt instanceof ConceptStmt.Return ret) {
            sb.append("ret");
            if (ret.value() != null) {
                sb.append(':').append(canonicalExprForm(ret.value(), alphaMap, nextLocalIdx));
            }
            sb.append(';');
        } else if (stmt instanceof ConceptStmt.StoreBuffer3D store) {
            sb.append("store3d:")
                    .append(store.bufferName())
                    .append('[')
                    .append(canonicalExprForm(store.linearIndex(), alphaMap, nextLocalIdx))
                    .append("]=")
                    .append(canonicalExprForm(store.value(), alphaMap, nextLocalIdx))
                    .append(';');
        }
    }

    private static String canonicalExprForm(
            ConceptExpr expr,
            Map<String, String> alphaMap,
            int[] nextLocalIdx) {
        if (expr instanceof ConceptExpr.Var v) {
            return alphaMap.computeIfAbsent(v.name(), k -> "$v" + (nextLocalIdx[0]++));
        }
        if (expr instanceof ConceptExpr.IntConst ic) {
            return "i32:" + ic.value();
        }
        if (expr instanceof ConceptExpr.LongConst lc) {
            return "i64:" + lc.value();
        }
        if (expr instanceof ConceptExpr.FloatConst fc) {
            float norm = fc.value() == -0.0f ? 0.0f : fc.value();
            return "f32:" + Float.floatToIntBits(norm);
        }
        if (expr instanceof ConceptExpr.DoubleConst dc) {
            double norm = dc.value() == -0.0d ? 0.0d : dc.value();
            return "f64:" + Double.doubleToLongBits(norm);
        }
        if (expr instanceof ConceptExpr.BoolConst bc) {
            return "bool:" + bc.value();
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            return u.op().name() + "(" + canonicalExprForm(u.operand(), alphaMap, nextLocalIdx) + ")";
        }
        if (expr instanceof ConceptExpr.BinaryOp b) {
            BinaryOperator op = b.op();
            String left = canonicalExprForm(b.left(), alphaMap, nextLocalIdx);
            String right = canonicalExprForm(b.right(), alphaMap, nextLocalIdx);
            // Canonicalize relational direction: a > b -> b < a, a >= b -> b <= a
            if (op == BinaryOperator.GT) {
                return BinaryOperator.LT.name() + "(" + right + "," + left + ")";
            }
            if (op == BinaryOperator.GE) {
                return BinaryOperator.LE.name() + "(" + right + "," + left + ")";
            }
            // Canonicalize commutative operators by sorting operand representations
            if (isCommutative(op) && left.compareTo(right) > 0) {
                String tmp = left;
                left = right;
                right = tmp;
            }
            return op.name() + "(" + left + "," + right + ")";
        }
        if (expr instanceof ConceptExpr.Cast c) {
            if (c.targetType() == c.operand().type()) {
                return canonicalExprForm(c.operand(), alphaMap, nextLocalIdx);
            }
            return "cast:" + c.targetType().name() + "(" + canonicalExprForm(c.operand(), alphaMap, nextLocalIdx) + ")";
        }
        if (expr instanceof ConceptExpr.Ternary t) {
            ConceptExpr cond = t.condition();
            ConceptExpr thenE = t.thenExpr();
            ConceptExpr elseE = t.elseExpr();
            if (cond instanceof ConceptExpr.UnaryOp u && u.op() == UnaryOperator.LOGICAL_NOT) {
                cond = u.operand();
                ConceptExpr swap = thenE;
                thenE = elseE;
                elseE = swap;
            } else if (cond instanceof ConceptExpr.BinaryOp b
                    && (b.op() == BinaryOperator.NE || b.op() == BinaryOperator.GT || b.op() == BinaryOperator.GE)) {
                cond = new ConceptExpr.BinaryOp(b.op().negateComparison(), b.left(), b.right(), b.type());
                ConceptExpr swap = thenE;
                thenE = elseE;
                elseE = swap;
            }
            return "?:("
                    + canonicalExprForm(cond, alphaMap, nextLocalIdx)
                    + ","
                    + canonicalExprForm(thenE, alphaMap, nextLocalIdx)
                    + ","
                    + canonicalExprForm(elseE, alphaMap, nextLocalIdx)
                    + ")";
        }
        if (expr instanceof ConceptExpr.LutLoad lut) {
            return "lut:" + lut.lutSymbol() + "[" + canonicalExprForm(lut.index(), alphaMap, nextLocalIdx) + "]";
        }
        if (expr instanceof ConceptExpr.MathIntrinsic m) {
            List<String> cArgs = new ArrayList<>(m.arguments().size());
            for (ConceptExpr a : m.arguments()) {
                cArgs.add(canonicalExprForm(a, alphaMap, nextLocalIdx));
            }
            if ((m.kind() == MathIntrinsicKind.MIN || m.kind() == MathIntrinsicKind.MAX) && cArgs.size() == 2) {
                if (cArgs.get(0).compareTo(cArgs.get(1)) > 0) {
                    Collections.swap(cArgs, 0, 1);
                }
            }
            return "math:" + m.kind().name() + "(" + String.join(",", cArgs) + ")";
        }
        if (expr instanceof ConceptExpr.Call call) {
            List<String> cArgs = new ArrayList<>(call.arguments().size());
            for (ConceptExpr a : call.arguments()) {
                cArgs.add(canonicalExprForm(a, alphaMap, nextLocalIdx));
            }
            return "call:" + call.methodName() + "(" + String.join(",", cArgs) + ")";
        }
        if (expr instanceof ConceptExpr.CompareTemp cmp) {
            return "cmp("
                    + canonicalExprForm(cmp.left(), alphaMap, nextLocalIdx)
                    + ","
                    + canonicalExprForm(cmp.right(), alphaMap, nextLocalIdx)
                    + ")";
        }
        return expr.toString();
    }

    private static boolean isCommutative(BinaryOperator op) {
        return switch (op) {
            case ADD, MUL, BIT_AND, BIT_OR, BIT_XOR, EQ, NE -> true;
            default -> false;
        };
    }

    private static String sha256Prefix16(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
