package io.github.dailystruggle.bytecode.transpile;

import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.BinaryOperator;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptExpr;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptFunction;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptLut;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptModule;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptParam;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptStmt;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptType;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.MathIntrinsicKind;
import io.github.dailystruggle.bytecode.transpile.JavaConceptBackendEmitter.Double3DEvaluator;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Direct JVM {@code .class} bytecode assembler for pure {@link ConceptModule} graphs.
 *
 * <p>Assembles ClassFile v50.0 bytecode directly into memory with zero {@code javac} invocation and
 * zero third-party bytecode dependencies, injecting {@link ConceptLut} arrays directly into
 * {@code public static} fields via {@link MethodHandles.Lookup#findStaticSetter} to eliminate the
 * 64 KB JVM {@code <clinit>} bytecode limit on large multi-noise / spline graphs.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001
 */
public final class JvmBytecodeConceptEmitter {

    private static final Logger LOGGER = Logger.getLogger(JvmBytecodeConceptEmitter.class.getName());
    private static final int CLASSFILE_MAGIC = 0xCAFEBABE;
    private static final int CLASSFILE_MINOR = 0;
    private static final int CLASSFILE_MAJOR = 50; // Java 6: type-inference verifier, full HotSpot C2 JIT
    private static final int MAX_METHOD_CODE_BYTES = 65_535;
    private static final int C2_HUGE_METHOD_WARN_BYTES = 7_500;
    private static final String COLUMN_Y_METHOD_NAME = "evalColumnY";
    private static final String COLUMN_Y_METHOD_DESC = "(IIIII[FI)V";
    private static final String COL2D_CACHE_FIELD_NAME = "__col2dCache";
    private static final String COL2D_CACHE_CLASS_INTERNAL =
            "io/github/dailystruggle/bytecode/transpile/JvmBytecodeConceptEmitter$Col2DCache";

    /**
     * Per-thread 2D {@code (x, z)} memoization table for pure Y-invariant helper functions inside
     * assembled {@link ConceptModule} classes (mirrors vanilla {@code Cache2D} / {@code FlatCache}
     * at arbitrary helper call depth with {@code 0.0} ULP exact parity).
     */
    public static final class Col2DCache {
        public final byte[] valid;
        public final int[] hitX;
        public final int[] hitY;
        public final int[] hitZ;
        public final double[] dvals;

        public Col2DCache(int count) {
            this.valid = new byte[count];
            this.hitX = new int[count];
            this.hitY = new int[count];
            this.hitZ = new int[count];
            this.dvals = new double[count];
        }
    }

    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_FINAL = 0x0010;
    private static final int ACC_SUPER = 0x0020;

    private static final String GENERATED_PACKAGE_INTERNAL =
            "io/github/dailystruggle/bytecode/transpile/gen/";
    private static final String GENERATED_PACKAGE_BINARY =
            "io.github.dailystruggle.bytecode.transpile.gen.";

    private JvmBytecodeConceptEmitter() {
    }

    /**
     * Directly assembles a pure {@link ConceptModule} into a JVM {@code .class}, injects all
     * deduplicated {@link ConceptLut} arrays into its static fields, and binds the entry function
     * as a {@link Double3DEvaluator} ready for immediate HotSpot C2 JIT compilation.
     *
     * @param module pure concept module with 0 or 3 {@code I32} parameters and numeric return type
     * @param uniqueSuffix deterministic or unique suffix appended to the generated class name
     * @return compiled evaluator backed by native JVM bytecode
     */
    public static Double3DEvaluator assembleToDouble3D(ConceptModule module, String uniqueSuffix) {
        Objects.requireNonNull(module, "module");
        ConceptFunction entry = module.entryFunction();
        int paramCount = entry.parameters().size();
        if (paramCount != 0 && paramCount != 3) {
            throw new IllegalArgumentException(
                    "JvmBytecodeConceptEmitter requires 0 or 3 parameters, got " + paramCount);
        }
        for (ConceptParam p : entry.parameters()) {
            if (p.type() != ConceptType.I32) {
                throw new IllegalArgumentException(
                        "JvmBytecodeConceptEmitter requires I32 entry parameters, got " + p.type());
            }
        }
        ConceptType retType = entry.returnType();
        if (retType != ConceptType.F64 && retType != ConceptType.F32 && retType != ConceptType.I32) {
            throw new IllegalArgumentException(
                    "JvmBytecodeConceptEmitter requires F64/F32/I32 return type, got " + retType);
        }

        String safeSuffix = sanitizeClassNameSuffix(uniqueSuffix);
        String simpleName = "IrBc_" + safeSuffix;
        String internalName = GENERATED_PACKAGE_INTERNAL + simpleName;
        String binaryName = GENERATED_PACKAGE_BINARY + simpleName;

        AssembledClass assembled = assembleClass(module, internalName);
        BytecodeClassLoader loader = new BytecodeClassLoader();
        Class<?> defined = loader.define(binaryName, assembled.classBytes());

        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            for (LutFieldBinding binding : assembled.lutBindings()) {
                MethodHandle setter = lookup.findStaticSetter(defined, binding.fieldName(), binding.array().getClass());
                setter.invoke(binding.array());
            }
            if (assembled.memo2dSlotCount() > 0) {
                final int slotCount = assembled.memo2dSlotCount();
                MethodHandle cacheSetter = lookup.findStaticSetter(defined, COL2D_CACHE_FIELD_NAME, ThreadLocal.class);
                cacheSetter.invoke(ThreadLocal.withInitial(() -> new Col2DCache(slotCount)));
            }

            Class<?> retClass = switch (retType) {
                case F64 -> double.class;
                case F32 -> float.class;
                case I32 -> int.class;
                default -> throw new IllegalStateException("Unsupported return type: " + retType);
            };
            MethodType mt = paramCount == 0
                    ? MethodType.methodType(retClass)
                    : MethodType.methodType(retClass, int.class, int.class, int.class);
            MethodHandle mh = lookup.findStatic(defined, assembled.entryMethodName(), mt);

            if (paramCount == 0) {
                if (retType == ConceptType.F64) {
                    return (x, y, z) -> {
                        try {
                            return (double) mh.invokeExact();
                        } catch (Throwable t) {
                            throw new IllegalStateException("Direct bytecode eval failed", t);
                        }
                    };
                }
                if (retType == ConceptType.F32) {
                    return (x, y, z) -> {
                        try {
                            return (float) mh.invokeExact();
                        } catch (Throwable t) {
                            throw new IllegalStateException("Direct bytecode eval failed", t);
                        }
                    };
                }
                return (x, y, z) -> {
                    try {
                        return (int) mh.invokeExact();
                    } catch (Throwable t) {
                        throw new IllegalStateException("Direct bytecode eval failed", t);
                    }
                };
            }

            final MethodHandle colMh = assembled.hasColumnYMethod()
                    ? lookup.findStatic(
                            defined,
                            COLUMN_Y_METHOD_NAME,
                            MethodType.methodType(
                                    void.class,
                                    int.class,
                                    int.class,
                                    int.class,
                                    int.class,
                                    int.class,
                                    float[].class,
                                    int.class))
                    : null;

            if (retType == ConceptType.F64) {
                if (colMh != null) {
                    return new Double3DEvaluator() {
                        @Override
                        public double evaluate(int x, int y, int z) {
                            try {
                                return (double) mh.invokeExact(x, y, z);
                            } catch (Throwable t) {
                                throw new IllegalStateException("Direct bytecode eval failed", t);
                            }
                        }

                        @Override
                        public void evaluateColumnY(
                                int x, int baseY, int stepY, int countY, int z, float[] out, int outOffset) {
                            try {
                                colMh.invokeExact(x, baseY, stepY, countY, z, out, outOffset);
                            } catch (Throwable t) {
                                throw new IllegalStateException("Direct bytecode evalColumnY failed", t);
                            }
                        }
                    };
                }
                return (x, y, z) -> {
                    try {
                        return (double) mh.invokeExact(x, y, z);
                    } catch (Throwable t) {
                        throw new IllegalStateException("Direct bytecode eval failed", t);
                    }
                };
            }
            if (retType == ConceptType.F32) {
                if (colMh != null) {
                    return new Double3DEvaluator() {
                        @Override
                        public double evaluate(int x, int y, int z) {
                            try {
                                return (float) mh.invokeExact(x, y, z);
                            } catch (Throwable t) {
                                throw new IllegalStateException("Direct bytecode eval failed", t);
                            }
                        }

                        @Override
                        public void evaluateColumnY(
                                int x, int baseY, int stepY, int countY, int z, float[] out, int outOffset) {
                            try {
                                colMh.invokeExact(x, baseY, stepY, countY, z, out, outOffset);
                            } catch (Throwable t) {
                                throw new IllegalStateException("Direct bytecode evalColumnY failed", t);
                            }
                        }
                    };
                }
                return (x, y, z) -> {
                    try {
                        return (float) mh.invokeExact(x, y, z);
                    } catch (Throwable t) {
                        throw new IllegalStateException("Direct bytecode eval failed", t);
                    }
                };
            }
            if (colMh != null) {
                return new Double3DEvaluator() {
                    @Override
                    public double evaluate(int x, int y, int z) {
                        try {
                            return (int) mh.invokeExact(x, y, z);
                        } catch (Throwable t) {
                            throw new IllegalStateException("Direct bytecode eval failed", t);
                        }
                    }

                    @Override
                    public void evaluateColumnY(
                            int x, int baseY, int stepY, int countY, int z, float[] out, int outOffset) {
                        try {
                            colMh.invokeExact(x, baseY, stepY, countY, z, out, outOffset);
                        } catch (Throwable t) {
                            throw new IllegalStateException("Direct bytecode evalColumnY failed", t);
                        }
                    }
                };
            }
            return (x, y, z) -> {
                try {
                    return (int) mh.invokeExact(x, y, z);
                } catch (Throwable t) {
                    throw new IllegalStateException("Direct bytecode eval failed", t);
                }
            };
        } catch (Throwable t) {
            throw new IllegalStateException("Failed binding direct JVM bytecode class " + binaryName, t);
        }
    }

    /**
     * Directly assembles an arbitrary pure {@link ConceptModule} into ClassFile v50.0 bytecode with
     * static-setter LUT injection (eliminating the 64 KB {@code <clinit>} limit) and returns a
     * {@link MethodHandle} to its entry method ready for HotSpot C2 JIT compilation.
     */
    static MethodHandle assembleEntryMethodHandle(
            ConceptModule module, String uniqueSuffix, Class<?> retClass, Class<?>[] paramClasses) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(retClass, "retClass");
        Objects.requireNonNull(paramClasses, "paramClasses");
        String safeSuffix = sanitizeClassNameSuffix(uniqueSuffix);
        String simpleName = "IrBc_" + safeSuffix;
        String internalName = GENERATED_PACKAGE_INTERNAL + simpleName;
        String binaryName = GENERATED_PACKAGE_BINARY + simpleName;

        AssembledClass assembled = assembleClass(module, internalName);
        BytecodeClassLoader loader = new BytecodeClassLoader();
        Class<?> defined = loader.define(binaryName, assembled.classBytes());
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            for (LutFieldBinding binding : assembled.lutBindings()) {
                MethodHandle setter = lookup.findStaticSetter(defined, binding.fieldName(), binding.array().getClass());
                setter.invoke(binding.array());
            }
            if (assembled.memo2dSlotCount() > 0) {
                final int slotCount = assembled.memo2dSlotCount();
                MethodHandle cacheSetter = lookup.findStaticSetter(defined, COL2D_CACHE_FIELD_NAME, ThreadLocal.class);
                cacheSetter.invoke(ThreadLocal.withInitial(() -> new Col2DCache(slotCount)));
            }
            return lookup.findStatic(defined, assembled.entryMethodName(), MethodType.methodType(retClass, paramClasses));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed binding direct JVM bytecode entry " + binaryName, t);
        }
    }

    private static void collectReferencedVarsStmt(ConceptStmt s, Set<String> out) {
        if (s == null) {
            return;
        }
        if (s instanceof ConceptStmt.VarDecl vd) {
            out.add(vd.name());
            collectReferencedVarsExpr(vd.initializer(), out);
        } else if (s instanceof ConceptStmt.Assign a) {
            out.add(a.name());
            collectReferencedVarsExpr(a.value(), out);
        } else if (s instanceof ConceptStmt.If iff) {
            collectReferencedVarsExpr(iff.condition(), out);
            for (ConceptStmt t : iff.thenBody()) {
                collectReferencedVarsStmt(t, out);
            }
            for (ConceptStmt el : iff.elseBody()) {
                collectReferencedVarsStmt(el, out);
            }
        } else if (s instanceof ConceptStmt.While wh) {
            collectReferencedVarsExpr(wh.condition(), out);
            for (ConceptStmt b : wh.body()) {
                collectReferencedVarsStmt(b, out);
            }
        } else if (s instanceof ConceptStmt.Return r) {
            collectReferencedVarsExpr(r.value(), out);
        } else if (s instanceof ConceptStmt.StoreBuffer3D st) {
            out.add(st.bufferName());
            collectReferencedVarsExpr(st.linearIndex(), out);
            collectReferencedVarsExpr(st.value(), out);
        }
    }

    private static void collectReferencedVarsExpr(ConceptExpr e, Set<String> out) {
        if (e == null) {
            return;
        }
        if (e instanceof ConceptExpr.Var v) {
            out.add(v.name());
        } else if (e instanceof ConceptExpr.BinaryOp b) {
            collectReferencedVarsExpr(b.left(), out);
            collectReferencedVarsExpr(b.right(), out);
        } else if (e instanceof ConceptExpr.UnaryOp u) {
            collectReferencedVarsExpr(u.operand(), out);
        } else if (e instanceof ConceptExpr.Cast c) {
            collectReferencedVarsExpr(c.operand(), out);
        } else if (e instanceof ConceptExpr.Ternary t) {
            collectReferencedVarsExpr(t.condition(), out);
            collectReferencedVarsExpr(t.thenExpr(), out);
            collectReferencedVarsExpr(t.elseExpr(), out);
        } else if (e instanceof ConceptExpr.LutLoad l) {
            collectReferencedVarsExpr(l.index(), out);
        } else if (e instanceof ConceptExpr.MathIntrinsic m) {
            for (ConceptExpr a : m.arguments()) {
                collectReferencedVarsExpr(a, out);
            }
        } else if (e instanceof ConceptExpr.Call c) {
            for (ConceptExpr a : c.arguments()) {
                collectReferencedVarsExpr(a, out);
            }
        } else if (e instanceof ConceptExpr.CompareTemp ct) {
            collectReferencedVarsExpr(ct.left(), out);
            collectReferencedVarsExpr(ct.right(), out);
        }
    }

    /**
     * Assembles raw ClassFile v50.0 bytes and LUT field bindings for a {@link ConceptModule}.
     */
    static AssembledClass assembleClass(ConceptModule module, String internalClassName) {
        ConstantPool cp = new ConstantPool();
        int thisClassIdx = cp.classInfo(internalClassName);
        int superClassIdx = cp.classInfo("java/lang/Object");
        int codeAttrNameIdx = cp.utf8("Code");

        // 1. Deduplicate LUTs by contentKey and build field descriptors + runtime arrays.
        Map<String, LutFieldBinding> uniqueByContent = new LinkedHashMap<>();
        Map<String, LutFieldBinding> lutBySymbol = new HashMap<>();
        List<LutFieldBinding> lutBindings = new ArrayList<>();
        int lutSeq = 0;
        for (ConceptLut lut : module.lookupTables()) {
            String key = lut.contentKey();
            LutFieldBinding binding = uniqueByContent.get(key);
            if (binding == null) {
                String fieldName = "lut_" + (lutSeq++);
                String fieldDesc = lutArrayDescriptor(lut.elementType());
                Object array = materializeLutArray(lut);
                int nameIdx = cp.utf8(fieldName);
                int descIdx = cp.utf8(fieldDesc);
                int fieldRefIdx = cp.fieldRef(internalClassName, fieldName, fieldDesc);
                binding = new LutFieldBinding(
                        fieldName, fieldDesc, lut.elementType(), array, nameIdx, descIdx, fieldRefIdx);
                uniqueByContent.put(key, binding);
                lutBindings.add(binding);
            }
            lutBySymbol.put(lut.symbolName(), binding);
        }

        // 2. Assign unique JVM method names and descriptors to all functions in the module.
        List<ConceptFunction> allFunctions = new ArrayList<>(module.helperFunctions().size() + 1);
        allFunctions.addAll(module.helperFunctions());
        allFunctions.add(module.entryFunction());

        Map<String, MethodBinding> methodByKey = new HashMap<>();
        Map<String, MethodBinding> methodByName = new HashMap<>();
        List<MethodBinding> methodBindings = new ArrayList<>(allFunctions.size());
        int fnSeq = 0;
        for (ConceptFunction fn : allFunctions) {
            boolean isEntry = (fn == module.entryFunction());
            String jvmName = isEntry ? "evalEntry" : ("fn_" + (fnSeq++));
            String jvmDesc = jvmMethodDescriptor(fn);
            int nameIdx = cp.utf8(jvmName);
            int descIdx = cp.utf8(jvmDesc);
            int methodRefIdx = cp.methodRef(internalClassName, jvmName, jvmDesc);
            MethodBinding mb = new MethodBinding(fn, jvmName, jvmDesc, isEntry, nameIdx, descIdx, methodRefIdx);
            methodBindings.add(mb);
            methodByKey.put(fn.key(), mb);
            methodByName.putIfAbsent(fn.name(), mb);
        }

        // 2b. Compute fixed-point parameter-liveness masks and assign 2D (x, z) memo slots to pure 2D helpers.
        Map<String, boolean[]> usedParamMasks = computeUsedParamMasks(allFunctions, methodByKey, methodByName);
        Map<String, Memo2DSpec> memo2dByFnKey = new HashMap<>();
        int memo2dSlotCount = 0;
        for (MethodBinding mb : methodBindings) {
            if (mb.isEntry()) {
                continue;
            }
            ConceptFunction fn = mb.fn();
            if (fn.returnType() != ConceptType.F64 && fn.returnType() != ConceptType.F32) {
                continue;
            }
            if (!isNonTrivialFor2DMemo(fn.body())) {
                continue;
            }
            boolean[] used = usedParamMasks.get(fn.key());
            List<ConceptParam> ps = fn.parameters();
            if (ps.size() == 3
                    && ps.get(0).type() == ConceptType.I32
                    && ps.get(1).type() == ConceptType.I32
                    && ps.get(2).type() == ConceptType.I32) {
                boolean yUsed = (used == null || used[1]);
                memo2dByFnKey.put(fn.key(), new Memo2DSpec(memo2dSlotCount++, 0, yUsed ? 1 : -1, 2));
            } else if (ps.size() == 2
                    && ps.get(0).type() == ConceptType.I32
                    && ps.get(1).type() == ConceptType.I32) {
                memo2dByFnKey.put(fn.key(), new Memo2DSpec(memo2dSlotCount++, 0, -1, 1));
            }
        }
        int col2dFieldNameIdx = -1;
        int col2dFieldDescIdx = -1;
        Col2DCpRefs col2dRefs = null;
        if (memo2dSlotCount > 0) {
            col2dFieldNameIdx = cp.utf8(COL2D_CACHE_FIELD_NAME);
            col2dFieldDescIdx = cp.utf8("Ljava/lang/ThreadLocal;");
            col2dRefs = new Col2DCpRefs(
                    cp.fieldRef(internalClassName, COL2D_CACHE_FIELD_NAME, "Ljava/lang/ThreadLocal;"),
                    cp.methodRef("java/lang/ThreadLocal", "get", "()Ljava/lang/Object;"),
                    cp.classInfo(COL2D_CACHE_CLASS_INTERNAL),
                    cp.fieldRef(COL2D_CACHE_CLASS_INTERNAL, "valid", "[B"),
                    cp.fieldRef(COL2D_CACHE_CLASS_INTERNAL, "hitX", "[I"),
                    cp.fieldRef(COL2D_CACHE_CLASS_INTERNAL, "hitY", "[I"),
                    cp.fieldRef(COL2D_CACHE_CLASS_INTERNAL, "hitZ", "[I"),
                    cp.fieldRef(COL2D_CACHE_CLASS_INTERNAL, "dvals", "[D"));
        }

        // 3. Emit bytecode for every function.
        List<CompiledMethod> compiledMethods = new ArrayList<>(methodBindings.size() + 1);
        String entryMethodName = "evalEntry";
        MethodBinding entryBinding = null;
        for (MethodBinding mb : methodBindings) {
            if (mb.isEntry()) {
                entryMethodName = mb.jvmName();
                entryBinding = mb;
            }
            Memo2DSpec memoSpec = memo2dByFnKey.get(mb.fn().key());
            MethodEmitter emitter = new MethodEmitter(
                    cp, mb.fn(), lutBySymbol, methodByKey, methodByName, false, memoSpec, col2dRefs);
            CompiledMethod cm = emitter.compile(mb);
            compiledMethods.add(cm);
        }

        // 3b. Emit native evalColumnY with Y-invariant 2D hoisting when the 3-arg entry has hoistable work.
        boolean hasColumnYMethod = false;
        if (entryBinding != null
                && entryBinding.fn().parameters().size() == 3
                && entryBinding.fn().parameters().get(0).type() == ConceptType.I32
                && entryBinding.fn().parameters().get(1).type() == ConceptType.I32
                && entryBinding.fn().parameters().get(2).type() == ConceptType.I32
                && (entryBinding.fn().returnType() == ConceptType.F64
                        || entryBinding.fn().returnType() == ConceptType.F32
                        || entryBinding.fn().returnType() == ConceptType.I32)) {
            ColumnYPartition part =
                    partitionColumnYStatements(entryBinding.fn(), methodByKey, methodByName, usedParamMasks);
            if (!part.hoistedStmts().isEmpty()) {
                int colNameIdx = cp.utf8(COLUMN_Y_METHOD_NAME);
                int colDescIdx = cp.utf8(COLUMN_Y_METHOD_DESC);
                int colMethodRefIdx = cp.methodRef(internalClassName, COLUMN_Y_METHOD_NAME, COLUMN_Y_METHOD_DESC);
                MethodBinding colBinding = new MethodBinding(
                        entryBinding.fn(),
                        COLUMN_Y_METHOD_NAME,
                        COLUMN_Y_METHOD_DESC,
                        false,
                        colNameIdx,
                        colDescIdx,
                        colMethodRefIdx);
                MethodEmitter colEmitter = new MethodEmitter(
                        cp, entryBinding.fn(), lutBySymbol, methodByKey, methodByName, true, null, null);
                CompiledMethod colCm = colEmitter.compileColumnY(colBinding, part);
                if (colCm != null) {
                    compiledMethods.add(colCm);
                    hasColumnYMethod = true;
                }
            }
        }

        // 4. Serialize ClassFile structure.
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
            DataOutputStream out = new DataOutputStream(baos);
            out.writeInt(CLASSFILE_MAGIC);
            out.writeShort(CLASSFILE_MINOR);
            out.writeShort(CLASSFILE_MAJOR);

            cp.writeTo(out);

            out.writeShort(ACC_PUBLIC | ACC_FINAL | ACC_SUPER);
            out.writeShort(thisClassIdx);
            out.writeShort(superClassIdx);
            out.writeShort(0); // interfaces_count

            // Fields (public static LUT arrays + optional ThreadLocal Col2DCache, populated via MethodHandles)
            int totalFields = lutBindings.size() + (memo2dSlotCount > 0 ? 1 : 0);
            out.writeShort(totalFields);
            for (LutFieldBinding lf : lutBindings) {
                out.writeShort(ACC_PUBLIC | ACC_STATIC);
                out.writeShort(lf.nameIdx());
                out.writeShort(lf.descIdx());
                out.writeShort(0); // attributes_count
            }
            if (memo2dSlotCount > 0) {
                out.writeShort(ACC_PUBLIC | ACC_STATIC);
                out.writeShort(col2dFieldNameIdx);
                out.writeShort(col2dFieldDescIdx);
                out.writeShort(0); // attributes_count
            }

            // Methods
            out.writeShort(compiledMethods.size());
            for (CompiledMethod cm : compiledMethods) {
                out.writeShort(ACC_PUBLIC | ACC_STATIC);
                out.writeShort(cm.binding().nameIdx());
                out.writeShort(cm.binding().descIdx());
                out.writeShort(1); // Code attribute
                out.writeShort(codeAttrNameIdx);
                int codeLen = cm.code().length;
                out.writeInt(2 + 2 + 4 + codeLen + 2 + 2);
                out.writeShort(cm.maxStack());
                out.writeShort(cm.maxLocals());
                out.writeInt(codeLen);
                out.write(cm.code());
                out.writeShort(0); // exception_table_length
                out.writeShort(0); // code attributes_count
            }

            out.writeShort(0); // class attributes_count
            out.flush();
            return new AssembledClass(
                    baos.toByteArray(), lutBindings, entryMethodName, hasColumnYMethod, memo2dSlotCount);
        } catch (IOException e) {
            throw new IllegalStateException("Failed serializing JVM ClassFile", e);
        }
    }

    private static String sanitizeClassNameSuffix(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "M0";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static String lutArrayDescriptor(ConceptType elementType) {
        return switch (elementType) {
            case I64 -> "[J";
            case F32 -> "[F";
            case F64 -> "[D";
            default -> "[I";
        };
    }

    private static Object materializeLutArray(ConceptLut lut) {
        List<Number> vals = lut.values();
        int n = vals.size();
        return switch (lut.elementType()) {
            case I64 -> {
                long[] arr = new long[n];
                for (int i = 0; i < n; i++) {
                    arr[i] = vals.get(i).longValue();
                }
                yield arr;
            }
            case F32 -> {
                float[] arr = new float[n];
                for (int i = 0; i < n; i++) {
                    arr[i] = vals.get(i).floatValue();
                }
                yield arr;
            }
            case F64 -> {
                double[] arr = new double[n];
                for (int i = 0; i < n; i++) {
                    arr[i] = vals.get(i).doubleValue();
                }
                yield arr;
            }
            default -> {
                int[] arr = new int[n];
                for (int i = 0; i < n; i++) {
                    arr[i] = vals.get(i).intValue();
                }
                yield arr;
            }
        };
    }

    private static String jvmTypeDescriptor(ConceptType type) {
        return switch (type) {
            case VOID -> "V";
            case BOOL, I32 -> "I";
            case I64 -> "J";
            case F32 -> "F";
            case F64 -> "D";
            case I32_ARRAY, GLOBAL_BUFFER_I32 -> "[I";
            case I64_ARRAY -> "[J";
            case F64_ARRAY, GLOBAL_BUFFER_F64 -> "[D";
            case GLOBAL_BUFFER_F32 -> "[F";
        };
    }

    private static String jvmMethodDescriptor(ConceptFunction fn) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (ConceptParam p : fn.parameters()) {
            sb.append(jvmTypeDescriptor(p.type()));
        }
        sb.append(')').append(jvmTypeDescriptor(fn.returnType()));
        return sb.toString();
    }

    private static int slotSize(ConceptType t) {
        return (t == ConceptType.I64 || t == ConceptType.F64) ? 2 : 1;
    }

    private static ConceptType promoteNumeric(ConceptType a, ConceptType b) {
        if (a == ConceptType.F64 || b == ConceptType.F64) {
            return ConceptType.F64;
        }
        if (a == ConceptType.F32 || b == ConceptType.F32) {
            return ConceptType.F32;
        }
        if (a == ConceptType.I64 || b == ConceptType.I64) {
            return ConceptType.I64;
        }
        return ConceptType.I32;
    }

    record AssembledClass(
            byte[] classBytes,
            List<LutFieldBinding> lutBindings,
            String entryMethodName,
            boolean hasColumnYMethod,
            int memo2dSlotCount) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AssembledClass other)) {
                return false;
            }
            return java.util.Arrays.equals(this.classBytes, other.classBytes)
                    && java.util.Objects.equals(this.lutBindings, other.lutBindings)
                    && java.util.Objects.equals(this.entryMethodName, other.entryMethodName)
                    && this.hasColumnYMethod == other.hasColumnYMethod
                    && this.memo2dSlotCount == other.memo2dSlotCount;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Arrays.hashCode(this.classBytes);
            result = 31 * result + java.util.Objects.hashCode(this.lutBindings);
            result = 31 * result + java.util.Objects.hashCode(this.entryMethodName);
            result = 31 * result + Boolean.hashCode(this.hasColumnYMethod);
            result = 31 * result + this.memo2dSlotCount;
            return result;
        }

        @Override
        public String toString() {
            return "AssembledClass[classBytes=" + java.util.Arrays.toString(this.classBytes)
                    + ", lutBindings=" + this.lutBindings
                    + ", entryMethodName=" + this.entryMethodName
                    + ", hasColumnYMethod=" + this.hasColumnYMethod
                    + ", memo2dSlotCount=" + this.memo2dSlotCount
                    + "]";
        }
    }

    private record Memo2DSpec(int slotId, int xParamSlot, int yParamSlot, int zParamSlot) {
    }

    private record Col2DCpRefs(
            int cacheFieldRefIdx,
            int tlGetMethodRefIdx,
            int cacheClassIdx,
            int validFieldRefIdx,
            int hitXFieldRefIdx,
            int hitYFieldRefIdx,
            int hitZFieldRefIdx,
            int dvalsFieldRefIdx) {
    }

    private static boolean isNonTrivialFor2DMemo(List<ConceptStmt> stmts) {
        if (stmts.size() >= 4) {
            return true;
        }
        for (ConceptStmt s : stmts) {
            if (stmtHasCallWhileOrLut(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean stmtHasCallWhileOrLut(ConceptStmt st) {
        if (st instanceof ConceptStmt.While) {
            return true;
        }
        if (st instanceof ConceptStmt.VarDecl vd) {
            return exprHasCallOrLut(vd.initializer());
        }
        if (st instanceof ConceptStmt.Assign as) {
            return exprHasCallOrLut(as.value());
        }
        if (st instanceof ConceptStmt.Return ret) {
            return exprHasCallOrLut(ret.value());
        }
        if (st instanceof ConceptStmt.If ifStmt) {
            if (exprHasCallOrLut(ifStmt.condition())) {
                return true;
            }
            for (ConceptStmt s : ifStmt.thenBody()) {
                if (stmtHasCallWhileOrLut(s)) {
                    return true;
                }
            }
            for (ConceptStmt s : ifStmt.elseBody()) {
                if (stmtHasCallWhileOrLut(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean exprHasCallOrLut(ConceptExpr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof ConceptExpr.Call || expr instanceof ConceptExpr.LutLoad) {
            return true;
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            return exprHasCallOrLut(u.operand());
        }
        if (expr instanceof ConceptExpr.BinaryOp b) {
            return exprHasCallOrLut(b.left()) || exprHasCallOrLut(b.right());
        }
        if (expr instanceof ConceptExpr.Cast c) {
            return exprHasCallOrLut(c.operand());
        }
        if (expr instanceof ConceptExpr.Ternary t) {
            return exprHasCallOrLut(t.condition())
                    || exprHasCallOrLut(t.thenExpr())
                    || exprHasCallOrLut(t.elseExpr());
        }
        if (expr instanceof ConceptExpr.MathIntrinsic m) {
            for (ConceptExpr a : m.arguments()) {
                if (exprHasCallOrLut(a)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static MethodBinding resolveCalleeBinding(
            ConceptExpr.Call c, Map<String, MethodBinding> methodByKey, Map<String, MethodBinding> methodByName) {
        String key = c.ownerClass() + "#" + c.methodName() + c.descriptor();
        MethodBinding mb = methodByKey.get(key);
        return mb != null ? mb : methodByName.get(c.methodName());
    }

    private static Map<String, boolean[]> computeUsedParamMasks(
            List<ConceptFunction> allFunctions,
            Map<String, MethodBinding> methodByKey,
            Map<String, MethodBinding> methodByName) {
        Map<String, boolean[]> usedByKey = new HashMap<>();
        for (ConceptFunction fn : allFunctions) {
            usedByKey.put(fn.key(), new boolean[fn.parameters().size()]);
        }
        boolean changed = true;
        int iter = 0;
        while (changed && iter++ < allFunctions.size() + 4) {
            changed = false;
            for (ConceptFunction fn : allFunctions) {
                boolean[] mask = usedByKey.get(fn.key());
                Map<String, Integer> paramIdx = new HashMap<>();
                for (int i = 0; i < fn.parameters().size(); i++) {
                    paramIdx.put(fn.parameters().get(i).name(), i);
                }
                // Track local variables that may transitively depend on each param index.
                Map<String, Integer> varParamBits = new HashMap<>();
                for (var e : paramIdx.entrySet()) {
                    if (e.getValue() < 30) {
                        varParamBits.put(e.getKey(), 1 << e.getValue());
                    }
                }
                if (markUsedParamsInStmts(
                        fn.body(), varParamBits, mask, usedByKey, methodByKey, methodByName)) {
                    changed = true;
                }
            }
        }
        return usedByKey;
    }

    private static boolean markUsedParamsInStmts(
            List<ConceptStmt> stmts,
            Map<String, Integer> varParamBits,
            boolean[] mask,
            Map<String, boolean[]> usedByKey,
            Map<String, MethodBinding> methodByKey,
            Map<String, MethodBinding> methodByName) {
        boolean changed = false;
        for (ConceptStmt st : stmts) {
            if (st instanceof ConceptStmt.VarDecl vd) {
                if (vd.initializer() != null) {
                    int bits = evalExprParamBits(
                            vd.initializer(), varParamBits, mask, usedByKey, methodByKey, methodByName);
                    varParamBits.merge(vd.name(), bits, (a, b) -> a | b);
                }
            } else if (st instanceof ConceptStmt.Assign as) {
                int bits = evalExprParamBits(
                        as.value(), varParamBits, mask, usedByKey, methodByKey, methodByName);
                varParamBits.merge(as.name(), bits, (a, b) -> a | b);
            } else if (st instanceof ConceptStmt.Return ret) {
                if (ret.value() != null) {
                    int bits = evalExprParamBits(
                            ret.value(), varParamBits, mask, usedByKey, methodByKey, methodByName);
                    if (markBits(mask, bits)) {
                        changed = true;
                    }
                }
            } else if (st instanceof ConceptStmt.If ifStmt) {
                int cBits = evalExprParamBits(
                        ifStmt.condition(), varParamBits, mask, usedByKey, methodByKey, methodByName);
                if (markBits(mask, cBits)) {
                    changed = true;
                }
                if (markUsedParamsInStmts(
                        ifStmt.thenBody(), varParamBits, mask, usedByKey, methodByKey, methodByName)) {
                    changed = true;
                }
                if (markUsedParamsInStmts(
                        ifStmt.elseBody(), varParamBits, mask, usedByKey, methodByKey, methodByName)) {
                    changed = true;
                }
            } else if (st instanceof ConceptStmt.While w) {
                int cBits = evalExprParamBits(
                        w.condition(), varParamBits, mask, usedByKey, methodByKey, methodByName);
                if (markBits(mask, cBits)) {
                    changed = true;
                }
                if (markUsedParamsInStmts(w.body(), varParamBits, mask, usedByKey, methodByKey, methodByName)) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static int evalExprParamBits(
            ConceptExpr expr,
            Map<String, Integer> varParamBits,
            boolean[] mask,
            Map<String, boolean[]> usedByKey,
            Map<String, MethodBinding> methodByKey,
            Map<String, MethodBinding> methodByName) {
        if (expr == null) {
            return 0;
        }
        if (expr instanceof ConceptExpr.Var v) {
            return varParamBits.getOrDefault(v.name(), 0);
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            return evalExprParamBits(u.operand(), varParamBits, mask, usedByKey, methodByKey, methodByName);
        }
        if (expr instanceof ConceptExpr.BinaryOp b) {
            return evalExprParamBits(b.left(), varParamBits, mask, usedByKey, methodByKey, methodByName)
                    | evalExprParamBits(b.right(), varParamBits, mask, usedByKey, methodByKey, methodByName);
        }
        if (expr instanceof ConceptExpr.Cast c) {
            return evalExprParamBits(c.operand(), varParamBits, mask, usedByKey, methodByKey, methodByName);
        }
        if (expr instanceof ConceptExpr.Ternary t) {
            return evalExprParamBits(t.condition(), varParamBits, mask, usedByKey, methodByKey, methodByName)
                    | evalExprParamBits(t.thenExpr(), varParamBits, mask, usedByKey, methodByKey, methodByName)
                    | evalExprParamBits(t.elseExpr(), varParamBits, mask, usedByKey, methodByKey, methodByName);
        }
        if (expr instanceof ConceptExpr.LutLoad l) {
            return evalExprParamBits(l.index(), varParamBits, mask, usedByKey, methodByKey, methodByName);
        }
        if (expr instanceof ConceptExpr.MathIntrinsic m) {
            int bits = 0;
            for (ConceptExpr a : m.arguments()) {
                bits |= evalExprParamBits(a, varParamBits, mask, usedByKey, methodByKey, methodByName);
            }
            return bits;
        }
        if (expr instanceof ConceptExpr.Call c) {
            MethodBinding callee = resolveCalleeBinding(c, methodByKey, methodByName);
            boolean[] calleeMask = callee != null ? usedByKey.get(callee.fn().key()) : null;
            int bits = 0;
            List<ConceptExpr> args = c.arguments();
            for (int i = 0; i < args.size(); i++) {
                if (calleeMask == null || i >= calleeMask.length || calleeMask[i]) {
                    bits |= evalExprParamBits(args.get(i), varParamBits, mask, usedByKey, methodByKey, methodByName);
                }
            }
            return bits;
        }
        if (expr instanceof ConceptExpr.CompareTemp cmp) {
            return evalExprParamBits(cmp.left(), varParamBits, mask, usedByKey, methodByKey, methodByName)
                    | evalExprParamBits(cmp.right(), varParamBits, mask, usedByKey, methodByKey, methodByName);
        }
        return 0;
    }

    private static boolean markBits(boolean[] mask, int bits) {
        boolean changed = false;
        for (int i = 0; i < mask.length && i < 30; i++) {
            if ((bits & (1 << i)) != 0 && !mask[i]) {
                mask[i] = true;
                changed = true;
            }
        }
        return changed;
    }

    private record ColumnYPartition(
            List<ConceptStmt> hoistedStmts,
            Set<String> hoistedVars,
            List<ConceptStmt> loopStmts,
            Map<String, ConceptType> syntheticLocals) {
    }

    private static ColumnYPartition partitionColumnYStatements(
            ConceptFunction fn,
            Map<String, MethodBinding> methodByKey,
            Map<String, MethodBinding> methodByName,
            Map<String, boolean[]> usedParamMasks) {
        String yParam = fn.parameters().get(1).name();
        List<ConceptStmt> body = fn.body();
        int n = body.size();
        Map<String, Integer> assignCounts = new HashMap<>();
        Set<String> assignedInControlFlow = new HashSet<>();
        for (ConceptStmt st : body) {
            countAssignments(st, false, assignCounts, assignedInControlFlow);
        }

        List<ConceptStmt> hoisted = new ArrayList<>();
        Set<String> hoistedVars = new HashSet<>();
        List<ConceptStmt> loopStmts = new ArrayList<>();
        Set<String> yDepVars = new HashSet<>();
        yDepVars.add(yParam);
        Map<String, ConceptType> syntheticLocals = new LinkedHashMap<>();
        Map<String, String> hoistedCallKeyToVar = new HashMap<>();
        boolean seenControlFlowReturn = false;

        for (int i = 0; i < n; i++) {
            ConceptStmt st = body.get(i);
            if (seenControlFlowReturn) {
                loopStmts.add(st);
                collectAssignedVars(st, yDepVars);
                continue;
            }
            if (st instanceof ConceptStmt.VarDecl vd) {
                if (vd.initializer() == null || isDefaultLiteralExpr(vd.initializer())) {
                    // Pure zero-init VarDecl is already handled by upfront/per-iteration local reset.
                    continue;
                }
                boolean singleTopLevelAssign =
                        !assignedInControlFlow.contains(vd.name()) && assignCounts.getOrDefault(vd.name(), 0) <= 1;
                if (singleTopLevelAssign
                        && !exprDependsOnAny(vd.initializer(), yDepVars, methodByKey, methodByName, usedParamMasks)) {
                    ConceptExpr sanitized = sanitizeUnusedCallArgs(
                            vd.initializer(), yDepVars, methodByKey, methodByName, usedParamMasks);
                    hoisted.add(
                            sanitized == vd.initializer()
                                    ? vd
                                    : new ConceptStmt.VarDecl(vd.name(), vd.type(), sanitized));
                    hoistedVars.add(vd.name());
                } else {
                    ConceptExpr rewritten = hoistInvariantCallsInExpr(
                            vd.initializer(),
                            yDepVars,
                            methodByKey,
                            methodByName,
                            usedParamMasks,
                            hoistedCallKeyToVar,
                            hoisted,
                            hoistedVars,
                            syntheticLocals);
                    loopStmts.add(
                            rewritten == vd.initializer()
                                    ? vd
                                    : new ConceptStmt.VarDecl(vd.name(), vd.type(), rewritten));
                    yDepVars.add(vd.name());
                }
            } else if (st instanceof ConceptStmt.Assign as) {
                boolean singleTopLevelAssign =
                        !assignedInControlFlow.contains(as.name()) && assignCounts.getOrDefault(as.name(), 0) <= 1;
                if (singleTopLevelAssign
                        && !exprDependsOnAny(as.value(), yDepVars, methodByKey, methodByName, usedParamMasks)) {
                    ConceptExpr sanitized =
                            sanitizeUnusedCallArgs(as.value(), yDepVars, methodByKey, methodByName, usedParamMasks);
                    hoisted.add(
                            sanitized == as.value() ? as : new ConceptStmt.Assign(as.name(), as.type(), sanitized));
                    hoistedVars.add(as.name());
                } else {
                    ConceptExpr rewritten = hoistInvariantCallsInExpr(
                            as.value(),
                            yDepVars,
                            methodByKey,
                            methodByName,
                            usedParamMasks,
                            hoistedCallKeyToVar,
                            hoisted,
                            hoistedVars,
                            syntheticLocals);
                    loopStmts.add(
                            rewritten == as.value() ? as : new ConceptStmt.Assign(as.name(), as.type(), rewritten));
                    yDepVars.add(as.name());
                }
            } else if (st instanceof ConceptStmt.Return ret) {
                ConceptExpr rewritten = hoistInvariantCallsInExpr(
                        ret.value(),
                        yDepVars,
                        methodByKey,
                        methodByName,
                        usedParamMasks,
                        hoistedCallKeyToVar,
                        hoisted,
                        hoistedVars,
                        syntheticLocals);
                loopStmts.add(rewritten == ret.value() ? ret : new ConceptStmt.Return(rewritten));
                seenControlFlowReturn = true;
            } else {
                loopStmts.add(st);
                collectAssignedVars(st, yDepVars);
                if (stmtContainsReturn(st)) {
                    seenControlFlowReturn = true;
                }
            }
        }
        if (hoisted.isEmpty()) {
            return new ColumnYPartition(List.of(), Set.of(), body, Map.of());
        }
        return new ColumnYPartition(hoisted, hoistedVars, loopStmts, syntheticLocals);
    }

    private static void countAssignments(
            ConceptStmt st,
            boolean inControlFlow,
            Map<String, Integer> assignCounts,
            Set<String> assignedInControlFlow) {
        if (st instanceof ConceptStmt.Assign as) {
            assignCounts.merge(as.name(), 1, Integer::sum);
            if (inControlFlow) {
                assignedInControlFlow.add(as.name());
            }
        } else if (st instanceof ConceptStmt.VarDecl vd) {
            if (vd.initializer() != null && !isDefaultLiteralExpr(vd.initializer())) {
                assignCounts.merge(vd.name(), 1, Integer::sum);
                if (inControlFlow) {
                    assignedInControlFlow.add(vd.name());
                }
            }
        } else if (st instanceof ConceptStmt.If ifStmt) {
            for (ConceptStmt s : ifStmt.thenBody()) {
                countAssignments(s, true, assignCounts, assignedInControlFlow);
            }
            for (ConceptStmt s : ifStmt.elseBody()) {
                countAssignments(s, true, assignCounts, assignedInControlFlow);
            }
        } else if (st instanceof ConceptStmt.While w) {
            for (ConceptStmt s : w.body()) {
                countAssignments(s, true, assignCounts, assignedInControlFlow);
            }
        }
    }

    private static boolean stmtContainsReturn(ConceptStmt st) {
        if (st instanceof ConceptStmt.Return) {
            return true;
        }
        if (st instanceof ConceptStmt.If ifStmt) {
            for (ConceptStmt s : ifStmt.thenBody()) {
                if (stmtContainsReturn(s)) {
                    return true;
                }
            }
            for (ConceptStmt s : ifStmt.elseBody()) {
                if (stmtContainsReturn(s)) {
                    return true;
                }
            }
        } else if (st instanceof ConceptStmt.While w) {
            for (ConceptStmt s : w.body()) {
                if (stmtContainsReturn(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ConceptExpr sanitizeUnusedCallArgs(
            ConceptExpr expr,
            Set<String> yDepVars,
            Map<String, MethodBinding> methodByKey,
            Map<String, MethodBinding> methodByName,
            Map<String, boolean[]> usedParamMasks) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof ConceptExpr.Call c) {
            MethodBinding callee = resolveCalleeBinding(c, methodByKey, methodByName);
            boolean[] mask = callee != null ? usedParamMasks.get(callee.fn().key()) : null;
            List<ConceptExpr> args = c.arguments();
            List<ConceptExpr> newArgs = null;
            for (int i = 0; i < args.size(); i++) {
                ConceptExpr a = args.get(i);
                ConceptExpr ra;
                if (mask != null && i < mask.length && !mask[i] && exprDependsOnAny(a, yDepVars, methodByKey, methodByName, usedParamMasks)) {
                    ra = defaultExprForType(callee.fn().parameters().get(i).type());
                } else {
                    ra = sanitizeUnusedCallArgs(a, yDepVars, methodByKey, methodByName, usedParamMasks);
                }
                if (ra != a) {
                    if (newArgs == null) {
                        newArgs = new ArrayList<>(args);
                    }
                    newArgs.set(i, ra);
                }
            }
            return newArgs == null
                    ? c
                    : new ConceptExpr.Call(c.ownerClass(), c.methodName(), c.descriptor(), newArgs, c.type());
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            ConceptExpr ro = sanitizeUnusedCallArgs(u.operand(), yDepVars, methodByKey, methodByName, usedParamMasks);
            return ro == u.operand() ? u : new ConceptExpr.UnaryOp(u.op(), ro, u.type());
        }
        if (expr instanceof ConceptExpr.BinaryOp b) {
            ConceptExpr rl = sanitizeUnusedCallArgs(b.left(), yDepVars, methodByKey, methodByName, usedParamMasks);
            ConceptExpr rr = sanitizeUnusedCallArgs(b.right(), yDepVars, methodByKey, methodByName, usedParamMasks);
            return (rl == b.left() && rr == b.right()) ? b : new ConceptExpr.BinaryOp(b.op(), rl, rr, b.type());
        }
        if (expr instanceof ConceptExpr.Cast cast) {
            ConceptExpr ro =
                    sanitizeUnusedCallArgs(cast.operand(), yDepVars, methodByKey, methodByName, usedParamMasks);
            return ro == cast.operand() ? cast : new ConceptExpr.Cast(cast.targetType(), ro);
        }
        if (expr instanceof ConceptExpr.MathIntrinsic m) {
            List<ConceptExpr> args = m.arguments();
            List<ConceptExpr> newArgs = null;
            for (int i = 0; i < args.size(); i++) {
                ConceptExpr a = args.get(i);
                ConceptExpr ra = sanitizeUnusedCallArgs(a, yDepVars, methodByKey, methodByName, usedParamMasks);
                if (ra != a) {
                    if (newArgs == null) {
                        newArgs = new ArrayList<>(args);
                    }
                    newArgs.set(i, ra);
                }
            }
            return newArgs == null ? m : new ConceptExpr.MathIntrinsic(m.kind(), newArgs, m.type());
        }
        if (expr instanceof ConceptExpr.Ternary t) {
            ConceptExpr rc = sanitizeUnusedCallArgs(t.condition(), yDepVars, methodByKey, methodByName, usedParamMasks);
            ConceptExpr rt = sanitizeUnusedCallArgs(t.thenExpr(), yDepVars, methodByKey, methodByName, usedParamMasks);
            ConceptExpr re = sanitizeUnusedCallArgs(t.elseExpr(), yDepVars, methodByKey, methodByName, usedParamMasks);
            return (rc == t.condition() && rt == t.thenExpr() && re == t.elseExpr())
                    ? t
                    : new ConceptExpr.Ternary(rc, rt, re, t.type());
        }
        return expr;
    }

    private static ConceptExpr defaultExprForType(ConceptType t) {
        return switch (t) {
            case F64 -> new ConceptExpr.DoubleConst(0.0);
            case F32 -> new ConceptExpr.FloatConst(0.0f);
            case I64 -> new ConceptExpr.LongConst(0L);
            case BOOL -> new ConceptExpr.BoolConst(false);
            default -> new ConceptExpr.IntConst(0);
        };
    }

    private static ConceptExpr hoistInvariantCallsInExpr(
            ConceptExpr expr,
            Set<String> yDepVars,
            Map<String, MethodBinding> methodByKey,
            Map<String, MethodBinding> methodByName,
            Map<String, boolean[]> usedParamMasks,
            Map<String, String> hoistedCallKeyToVar,
            List<ConceptStmt> hoisted,
            Set<String> hoistedVars,
            Map<String, ConceptType> syntheticLocals) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof ConceptExpr.Call c) {
            if (!exprDependsOnAny(c, yDepVars, methodByKey, methodByName, usedParamMasks)) {
                MethodBinding mb = resolveCalleeBinding(c, methodByKey, methodByName);
                if (mb != null) {
                    ConceptExpr sanitizedCall =
                            sanitizeUnusedCallArgs(c, yDepVars, methodByKey, methodByName, usedParamMasks);
                    String key = sanitizedCall.toString();
                    String existing = hoistedCallKeyToVar.get(key);
                    if (existing != null) {
                        return new ConceptExpr.Var(existing, c.type());
                    }
                    String synthName = "__coly_call_" + syntheticLocals.size();
                    ConceptType retType = c.type();
                    syntheticLocals.put(synthName, retType);
                    hoistedCallKeyToVar.put(key, synthName);
                    hoistedVars.add(synthName);
                    hoisted.add(new ConceptStmt.VarDecl(synthName, retType, sanitizedCall));
                    return new ConceptExpr.Var(synthName, retType);
                }
            }
            List<ConceptExpr> args = c.arguments();
            List<ConceptExpr> newArgs = null;
            for (int i = 0; i < args.size(); i++) {
                ConceptExpr a = args.get(i);
                ConceptExpr ra = hoistInvariantCallsInExpr(
                        a,
                        yDepVars,
                        methodByKey,
                        methodByName,
                        usedParamMasks,
                        hoistedCallKeyToVar,
                        hoisted,
                        hoistedVars,
                        syntheticLocals);
                if (ra != a) {
                    if (newArgs == null) {
                        newArgs = new ArrayList<>(args);
                    }
                    newArgs.set(i, ra);
                }
            }
            return newArgs == null
                    ? c
                    : new ConceptExpr.Call(c.ownerClass(), c.methodName(), c.descriptor(), newArgs, c.type());
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            ConceptExpr ro = hoistInvariantCallsInExpr(
                    u.operand(),
                    yDepVars,
                    methodByKey,
                    methodByName,
                    usedParamMasks,
                    hoistedCallKeyToVar,
                    hoisted,
                    hoistedVars,
                    syntheticLocals);
            return ro == u.operand() ? u : new ConceptExpr.UnaryOp(u.op(), ro, u.type());
        }
        if (expr instanceof ConceptExpr.BinaryOp b) {
            ConceptExpr rl = hoistInvariantCallsInExpr(
                    b.left(),
                    yDepVars,
                    methodByKey,
                    methodByName,
                    usedParamMasks,
                    hoistedCallKeyToVar,
                    hoisted,
                    hoistedVars,
                    syntheticLocals);
            ConceptExpr rr = hoistInvariantCallsInExpr(
                    b.right(),
                    yDepVars,
                    methodByKey,
                    methodByName,
                    usedParamMasks,
                    hoistedCallKeyToVar,
                    hoisted,
                    hoistedVars,
                    syntheticLocals);
            return (rl == b.left() && rr == b.right()) ? b : new ConceptExpr.BinaryOp(b.op(), rl, rr, b.type());
        }
        if (expr instanceof ConceptExpr.Cast cast) {
            ConceptExpr ro = hoistInvariantCallsInExpr(
                    cast.operand(),
                    yDepVars,
                    methodByKey,
                    methodByName,
                    usedParamMasks,
                    hoistedCallKeyToVar,
                    hoisted,
                    hoistedVars,
                    syntheticLocals);
            return ro == cast.operand() ? cast : new ConceptExpr.Cast(cast.targetType(), ro);
        }
        if (expr instanceof ConceptExpr.MathIntrinsic m) {
            List<ConceptExpr> args = m.arguments();
            List<ConceptExpr> newArgs = null;
            for (int i = 0; i < args.size(); i++) {
                ConceptExpr a = args.get(i);
                ConceptExpr ra = hoistInvariantCallsInExpr(
                        a,
                        yDepVars,
                        methodByKey,
                        methodByName,
                        usedParamMasks,
                        hoistedCallKeyToVar,
                        hoisted,
                        hoistedVars,
                        syntheticLocals);
                if (ra != a) {
                    if (newArgs == null) {
                        newArgs = new ArrayList<>(args);
                    }
                    newArgs.set(i, ra);
                }
            }
            return newArgs == null ? m : new ConceptExpr.MathIntrinsic(m.kind(), newArgs, m.type());
        }
        if (expr instanceof ConceptExpr.Ternary t) {
            // Only hoist unconditionally evaluated condition; never speculatively hoist branches.
            ConceptExpr rc = hoistInvariantCallsInExpr(
                    t.condition(),
                    yDepVars,
                    methodByKey,
                    methodByName,
                    usedParamMasks,
                    hoistedCallKeyToVar,
                    hoisted,
                    hoistedVars,
                    syntheticLocals);
            return rc == t.condition() ? t : new ConceptExpr.Ternary(rc, t.thenExpr(), t.elseExpr(), t.type());
        }
        return expr;
    }

    private static boolean isDefaultLiteralExpr(ConceptExpr expr) {
        if (expr instanceof ConceptExpr.IntConst ic) {
            return ic.value() == 0;
        }
        if (expr instanceof ConceptExpr.LongConst lc) {
            return lc.value() == 0L;
        }
        if (expr instanceof ConceptExpr.FloatConst fc) {
            return Float.floatToRawIntBits(fc.value()) == 0;
        }
        if (expr instanceof ConceptExpr.DoubleConst dc) {
            return Double.doubleToRawLongBits(dc.value()) == 0L;
        }
        if (expr instanceof ConceptExpr.BoolConst bc) {
            return !bc.value();
        }
        return false;
    }

    private static void collectAssignedVars(ConceptStmt st, Set<String> out) {
        if (st instanceof ConceptStmt.Assign as) {
            out.add(as.name());
        } else if (st instanceof ConceptStmt.VarDecl vd) {
            if (vd.initializer() != null && !isDefaultLiteralExpr(vd.initializer())) {
                out.add(vd.name());
            }
        } else if (st instanceof ConceptStmt.If ifStmt) {
            for (ConceptStmt s : ifStmt.thenBody()) {
                collectAssignedVars(s, out);
            }
            for (ConceptStmt s : ifStmt.elseBody()) {
                collectAssignedVars(s, out);
            }
        } else if (st instanceof ConceptStmt.While w) {
            for (ConceptStmt s : w.body()) {
                collectAssignedVars(s, out);
            }
        }
    }

    private static boolean exprDependsOnAny(
            ConceptExpr expr,
            Set<String> vars,
            Map<String, MethodBinding> methodByKey,
            Map<String, MethodBinding> methodByName,
            Map<String, boolean[]> usedParamMasks) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof ConceptExpr.Var v) {
            return vars.contains(v.name());
        }
        if (expr instanceof ConceptExpr.UnaryOp u) {
            return exprDependsOnAny(u.operand(), vars, methodByKey, methodByName, usedParamMasks);
        }
        if (expr instanceof ConceptExpr.BinaryOp b) {
            return exprDependsOnAny(b.left(), vars, methodByKey, methodByName, usedParamMasks)
                    || exprDependsOnAny(b.right(), vars, methodByKey, methodByName, usedParamMasks);
        }
        if (expr instanceof ConceptExpr.Cast c) {
            return exprDependsOnAny(c.operand(), vars, methodByKey, methodByName, usedParamMasks);
        }
        if (expr instanceof ConceptExpr.Ternary t) {
            return exprDependsOnAny(t.condition(), vars, methodByKey, methodByName, usedParamMasks)
                    || exprDependsOnAny(t.thenExpr(), vars, methodByKey, methodByName, usedParamMasks)
                    || exprDependsOnAny(t.elseExpr(), vars, methodByKey, methodByName, usedParamMasks);
        }
        if (expr instanceof ConceptExpr.LutLoad l) {
            return exprDependsOnAny(l.index(), vars, methodByKey, methodByName, usedParamMasks);
        }
        if (expr instanceof ConceptExpr.MathIntrinsic m) {
            for (ConceptExpr a : m.arguments()) {
                if (exprDependsOnAny(a, vars, methodByKey, methodByName, usedParamMasks)) {
                    return true;
                }
            }
            return false;
        }
        if (expr instanceof ConceptExpr.Call c) {
            MethodBinding callee = resolveCalleeBinding(c, methodByKey, methodByName);
            boolean[] mask = (callee != null && usedParamMasks != null)
                    ? usedParamMasks.get(callee.fn().key())
                    : null;
            List<ConceptExpr> args = c.arguments();
            for (int i = 0; i < args.size(); i++) {
                if (mask == null || i >= mask.length || mask[i]) {
                    if (exprDependsOnAny(args.get(i), vars, methodByKey, methodByName, usedParamMasks)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (expr instanceof ConceptExpr.CompareTemp cmp) {
            return exprDependsOnAny(cmp.left(), vars, methodByKey, methodByName, usedParamMasks)
                    || exprDependsOnAny(cmp.right(), vars, methodByKey, methodByName, usedParamMasks);
        }
        return false;
    }

    record LutFieldBinding(
            String fieldName,
            String fieldDesc,
            ConceptType elementType,
            Object array,
            int nameIdx,
            int descIdx,
            int fieldRefIdx) {
    }

    private record MethodBinding(
            ConceptFunction fn,
            String jvmName,
            String jvmDesc,
            boolean isEntry,
            int nameIdx,
            int descIdx,
            int methodRefIdx) {
    }

    private record CompiledMethod(MethodBinding binding, byte[] code, int maxStack, int maxLocals) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CompiledMethod other)) {
                return false;
            }
            return java.util.Objects.equals(this.binding, other.binding)
                    && java.util.Arrays.equals(this.code, other.code)
                    && this.maxStack == other.maxStack
                    && this.maxLocals == other.maxLocals;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Objects.hashCode(this.binding);
            result = 31 * result + java.util.Arrays.hashCode(this.code);
            result = 31 * result + this.maxStack;
            result = 31 * result + this.maxLocals;
            return result;
        }

        @Override
        public String toString() {
            return "CompiledMethod[binding=" + this.binding
                    + ", code=" + java.util.Arrays.toString(this.code)
                    + ", maxStack=" + this.maxStack
                    + ", maxLocals=" + this.maxLocals
                    + "]";
        }
    }

    private static final class BytecodeClassLoader extends ClassLoader {
        BytecodeClassLoader() {
            super(JvmBytecodeConceptEmitter.class.getClassLoader());
        }

        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }

    /**
     * Minimal deduplicating JVM Constant Pool writer.
     */
    private static final class ConstantPool {
        private final ByteArrayOutputStream entries = new ByteArrayOutputStream(1024);
        private final DataOutputStream out = new DataOutputStream(entries);
        private final Map<String, Integer> utf8Map = new HashMap<>();
        private final Map<String, Integer> classMap = new HashMap<>();
        private final Map<String, Integer> natMap = new HashMap<>();
        private final Map<String, Integer> fieldRefMap = new HashMap<>();
        private final Map<String, Integer> methodRefMap = new HashMap<>();
        private final Map<Integer, Integer> intMap = new HashMap<>();
        private final Map<Integer, Integer> floatBitsMap = new HashMap<>();
        private final Map<Long, Integer> longMap = new HashMap<>();
        private final Map<Long, Integer> doubleBitsMap = new HashMap<>();
        private int nextIndex = 1;

        int utf8(String s) {
            Integer existing = utf8Map.get(s);
            if (existing != null) {
                return existing;
            }
            int idx = nextIndex++;
            try {
                out.writeByte(1); // CONSTANT_Utf8
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                out.writeShort(bytes.length);
                out.write(bytes);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            utf8Map.put(s, idx);
            return idx;
        }

        int classInfo(String internalName) {
            Integer existing = classMap.get(internalName);
            if (existing != null) {
                return existing;
            }
            int nameIdx = utf8(internalName);
            int idx = nextIndex++;
            try {
                out.writeByte(7); // CONSTANT_Class
                out.writeShort(nameIdx);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            classMap.put(internalName, idx);
            return idx;
        }

        int nameAndType(String name, String desc) {
            String key = name + ":" + desc;
            Integer existing = natMap.get(key);
            if (existing != null) {
                return existing;
            }
            int nameIdx = utf8(name);
            int descIdx = utf8(desc);
            int idx = nextIndex++;
            try {
                out.writeByte(12); // CONSTANT_NameAndType
                out.writeShort(nameIdx);
                out.writeShort(descIdx);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            natMap.put(key, idx);
            return idx;
        }

        int fieldRef(String owner, String name, String desc) {
            String key = owner + "." + name + ":" + desc;
            Integer existing = fieldRefMap.get(key);
            if (existing != null) {
                return existing;
            }
            int clsIdx = classInfo(owner);
            int natIdx = nameAndType(name, desc);
            int idx = nextIndex++;
            try {
                out.writeByte(9); // CONSTANT_Fieldref
                out.writeShort(clsIdx);
                out.writeShort(natIdx);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            fieldRefMap.put(key, idx);
            return idx;
        }

        int methodRef(String owner, String name, String desc) {
            String key = owner + "." + name + desc;
            Integer existing = methodRefMap.get(key);
            if (existing != null) {
                return existing;
            }
            int clsIdx = classInfo(owner);
            int natIdx = nameAndType(name, desc);
            int idx = nextIndex++;
            try {
                out.writeByte(10); // CONSTANT_Methodref
                out.writeShort(clsIdx);
                out.writeShort(natIdx);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            methodRefMap.put(key, idx);
            return idx;
        }

        int intConst(int val) {
            Integer existing = intMap.get(val);
            if (existing != null) {
                return existing;
            }
            int idx = nextIndex++;
            try {
                out.writeByte(3); // CONSTANT_Integer
                out.writeInt(val);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            intMap.put(val, idx);
            return idx;
        }

        int floatConst(float val) {
            int bits = Float.floatToRawIntBits(val);
            Integer existing = floatBitsMap.get(bits);
            if (existing != null) {
                return existing;
            }
            int idx = nextIndex++;
            try {
                out.writeByte(4); // CONSTANT_Float
                out.writeInt(bits);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            floatBitsMap.put(bits, idx);
            return idx;
        }

        int longConst(long val) {
            Integer existing = longMap.get(val);
            if (existing != null) {
                return existing;
            }
            int idx = nextIndex;
            nextIndex += 2; // Long occupies two constant-pool slots
            try {
                out.writeByte(5); // CONSTANT_Long
                out.writeLong(val);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            longMap.put(val, idx);
            return idx;
        }

        int doubleConst(double val) {
            long bits = Double.doubleToRawLongBits(val);
            Integer existing = doubleBitsMap.get(bits);
            if (existing != null) {
                return existing;
            }
            int idx = nextIndex;
            nextIndex += 2; // Double occupies two constant-pool slots
            try {
                out.writeByte(6); // CONSTANT_Double
                out.writeLong(bits);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            doubleBitsMap.put(bits, idx);
            return idx;
        }

        void writeTo(DataOutputStream dest) throws IOException {
            out.flush();
            if (nextIndex > 65535) {
                throw new IllegalStateException("JVM ConstantPool exceeded 65535 entries: " + nextIndex);
            }
            dest.writeShort(nextIndex);
            entries.writeTo(dest);
        }
    }

    /**
     * Per-function bytecode emitter with automatic wide-branch ({@code goto_w}) patching and
     * deterministic local-variable slot allocation.
     */
    private static final class MethodEmitter {
        // JVM opcodes
        private static final int OP_ICONST_M1 = 0x02;
        private static final int OP_ICONST_0 = 0x03;
        private static final int OP_ICONST_1 = 0x04;
        private static final int OP_LCONST_0 = 0x09;
        private static final int OP_LCONST_1 = 0x0A;
        private static final int OP_FCONST_0 = 0x0B;
        private static final int OP_FCONST_1 = 0x0C;
        private static final int OP_FCONST_2 = 0x0D;
        private static final int OP_DCONST_0 = 0x0E;
        private static final int OP_DCONST_1 = 0x0F;
        private static final int OP_BIPUSH = 0x10;
        private static final int OP_SIPUSH = 0x11;
        private static final int OP_LDC = 0x12;
        private static final int OP_LDC_W = 0x13;
        private static final int OP_LDC2_W = 0x14;
        private static final int OP_ILOAD = 0x15;
        private static final int OP_LLOAD = 0x16;
        private static final int OP_FLOAD = 0x17;
        private static final int OP_DLOAD = 0x18;
        private static final int OP_ALOAD = 0x19;
        private static final int OP_IALOAD = 0x2E;
        private static final int OP_LALOAD = 0x2F;
        private static final int OP_FALOAD = 0x30;
        private static final int OP_DALOAD = 0x31;
        private static final int OP_BALOAD = 0x33;
        private static final int OP_ISTORE = 0x36;
        private static final int OP_LSTORE = 0x37;
        private static final int OP_FSTORE = 0x38;
        private static final int OP_DSTORE = 0x39;
        private static final int OP_ASTORE = 0x3A;
        private static final int OP_IASTORE = 0x4F;
        private static final int OP_FASTORE = 0x51;
        private static final int OP_DASTORE = 0x52;
        private static final int OP_BASTORE = 0x54;
        private static final int OP_POP = 0x57;
        private static final int OP_POP2 = 0x58;
        private static final int OP_IADD = 0x60;
        private static final int OP_LADD = 0x61;
        private static final int OP_FADD = 0x62;
        private static final int OP_DADD = 0x63;
        private static final int OP_ISUB = 0x64;
        private static final int OP_LSUB = 0x65;
        private static final int OP_FSUB = 0x66;
        private static final int OP_DSUB = 0x67;
        private static final int OP_IMUL = 0x68;
        private static final int OP_LMUL = 0x69;
        private static final int OP_FMUL = 0x6A;
        private static final int OP_DMUL = 0x6B;
        private static final int OP_IDIV = 0x6C;
        private static final int OP_LDIV = 0x6D;
        private static final int OP_FDIV = 0x6E;
        private static final int OP_DDIV = 0x6F;
        private static final int OP_IREM = 0x70;
        private static final int OP_LREM = 0x71;
        private static final int OP_FREM = 0x72;
        private static final int OP_DREM = 0x73;
        private static final int OP_INEG = 0x74;
        private static final int OP_LNEG = 0x75;
        private static final int OP_FNEG = 0x76;
        private static final int OP_DNEG = 0x77;
        private static final int OP_ISHL = 0x78;
        private static final int OP_LSHL = 0x79;
        private static final int OP_ISHR = 0x7A;
        private static final int OP_LSHR = 0x7B;
        private static final int OP_IUSHR = 0x7C;
        private static final int OP_LUSHR = 0x7D;
        private static final int OP_IAND = 0x7E;
        private static final int OP_LAND = 0x7F;
        private static final int OP_IOR = 0x80;
        private static final int OP_LOR = 0x81;
        private static final int OP_IXOR = 0x82;
        private static final int OP_LXOR = 0x83;
        private static final int OP_IINC = 0x84;
        private static final int OP_I2L = 0x85;
        private static final int OP_I2F = 0x86;
        private static final int OP_I2D = 0x87;
        private static final int OP_L2I = 0x88;
        private static final int OP_L2F = 0x89;
        private static final int OP_L2D = 0x8A;
        private static final int OP_F2I = 0x8B;
        private static final int OP_F2L = 0x8C;
        private static final int OP_F2D = 0x8D;
        private static final int OP_D2I = 0x8E;
        private static final int OP_D2L = 0x8F;
        private static final int OP_D2F = 0x90;
        private static final int OP_LCMP = 0x94;
        private static final int OP_FCMPL = 0x95;
        private static final int OP_FCMPG = 0x96;
        private static final int OP_DCMPL = 0x97;
        private static final int OP_DCMPG = 0x98;
        private static final int OP_IFEQ = 0x99;
        private static final int OP_IFNE = 0x9A;
        private static final int OP_IFLT = 0x9B;
        private static final int OP_IFGE = 0x9C;
        private static final int OP_IFGT = 0x9D;
        private static final int OP_IFLE = 0x9E;
        private static final int OP_IF_ICMPEQ = 0x9F;
        private static final int OP_IF_ICMPNE = 0xA0;
        private static final int OP_IF_ICMPLT = 0xA1;
        private static final int OP_IF_ICMPGE = 0xA2;
        private static final int OP_IF_ICMPGT = 0xA3;
        private static final int OP_IF_ICMPLE = 0xA4;
        private static final int OP_IRETURN = 0xAC;
        private static final int OP_LRETURN = 0xAD;
        private static final int OP_FRETURN = 0xAE;
        private static final int OP_DRETURN = 0xAF;
        private static final int OP_RETURN = 0xB1;
        private static final int OP_GETSTATIC = 0xB2;
        private static final int OP_GETFIELD = 0xB4;
        private static final int OP_INVOKEVIRTUAL = 0xB6;
        private static final int OP_INVOKESTATIC = 0xB8;
        private static final int OP_CHECKCAST = 0xC0;
        private static final int OP_WIDE = 0xC4;
        private static final int OP_GOTO_W = 0xC8;

        private final ConstantPool cp;
        private final ConceptFunction fn;
        private final Map<String, LutFieldBinding> lutBySymbol;
        private final Map<String, MethodBinding> methodByKey;
        private final Map<String, MethodBinding> methodByName;

        private final Map<String, Integer> localSlots = new LinkedHashMap<>();
        private final Map<String, ConceptType> localTypes = new LinkedHashMap<>();
        private final List<String> nonParamLocals = new ArrayList<>();
        private int nextSlot = 0;

        private byte[] buf = new byte[256];
        private int len = 0;
        private int curStack = 0;
        private int maxStack = 4;

        private final boolean columnYMode;
        private List<Integer> columnYReturnGotos;
        private final Memo2DSpec memo2dSpec;
        private final Col2DCpRefs col2dRefs;
        private int memoCacheSlot = -1;
        private int memoRetTmpSlot = -1;

        MethodEmitter(
                ConstantPool cp,
                ConceptFunction fn,
                Map<String, LutFieldBinding> lutBySymbol,
                Map<String, MethodBinding> methodByKey,
                Map<String, MethodBinding> methodByName,
                boolean columnYMode,
                Memo2DSpec memo2dSpec,
                Col2DCpRefs col2dRefs) {
            this.cp = cp;
            this.fn = fn;
            this.lutBySymbol = lutBySymbol;
            this.methodByKey = methodByKey;
            this.methodByName = methodByName;
            this.columnYMode = columnYMode;
            this.memo2dSpec = memo2dSpec;
            this.col2dRefs = col2dRefs;
            allocateLocals();
            if (!columnYMode && memo2dSpec != null && col2dRefs != null) {
                this.memoCacheSlot = nextSlot++;
                this.memoRetTmpSlot = nextSlot;
                nextSlot += slotSize(fn.returnType());
            }
        }

        private void allocateLocals() {
            if (columnYMode) {
                // Descriptor (IIIII[FI)V -> slot 0:x, 1:baseY, 2:stepY, 3:countY, 4:z, 5:out, 6:outOffset, 7:i, 8:y
                localSlots.put(fn.parameters().get(0).name(), 0);
                localTypes.put(fn.parameters().get(0).name(), ConceptType.I32);
                localSlots.put(fn.parameters().get(1).name(), 8);
                localTypes.put(fn.parameters().get(1).name(), ConceptType.I32);
                localSlots.put(fn.parameters().get(2).name(), 4);
                localTypes.put(fn.parameters().get(2).name(), ConceptType.I32);
                nextSlot = 9;
            } else {
                for (ConceptParam p : fn.parameters()) {
                    localSlots.put(p.name(), nextSlot);
                    localTypes.put(p.name(), p.type());
                    nextSlot += slotSize(p.type());
                }
            }
            Set<String> usedInBody = new HashSet<>();
            for (ConceptStmt s : fn.body()) {
                collectReferencedVarsStmt(s, usedInBody);
            }
            for (var entry : fn.localTypes().entrySet()) {
                if (usedInBody.contains(entry.getKey())) {
                    declareLocalIfAbsent(entry.getKey(), entry.getValue());
                }
            }
            collectStmtLocals(fn.body());
        }

        private void declareLocalIfAbsent(String name, ConceptType type) {
            if (!localSlots.containsKey(name)) {
                localSlots.put(name, nextSlot);
                localTypes.put(name, type);
                nonParamLocals.add(name);
                nextSlot += slotSize(type);
            }
        }

        private void collectStmtLocals(List<ConceptStmt> stmts) {
            for (ConceptStmt s : stmts) {
                if (s instanceof ConceptStmt.VarDecl vd) {
                    declareLocalIfAbsent(vd.name(), vd.type());
                } else if (s instanceof ConceptStmt.Assign as) {
                    declareLocalIfAbsent(as.name(), as.type());
                } else if (s instanceof ConceptStmt.If ifStmt) {
                    collectStmtLocals(ifStmt.thenBody());
                    collectStmtLocals(ifStmt.elseBody());
                } else if (s instanceof ConceptStmt.While w) {
                    collectStmtLocals(w.body());
                }
            }
        }

        CompiledMethod compile(MethodBinding binding) {
            // Optional 2D (x, z) per-thread memoization hit check before zero-initializing locals.
            if (!columnYMode && memo2dSpec != null && col2dRefs != null) {
                emitMemo2DPrologue();
            }

            // Zero-initialize all non-parameter locals up front so the JVM verifier always sees
            // definite assignment across arbitrary nested If/While control flow.
            for (String localName : nonParamLocals) {
                ConceptType t = localTypes.get(localName);
                emitDefaultConst(t);
                emitStore(localName, t);
            }

            emitStatements(fn.body());

            // Emit safe fallback return at method tail in case all returns are inside branches.
            if (fn.returnType() == ConceptType.VOID) {
                emitByte(OP_RETURN);
            } else {
                emitDefaultConst(fn.returnType());
                emitReturnOpcode(fn.returnType());
            }

            if (len > MAX_METHOD_CODE_BYTES) {
                throw new IllegalStateException(
                        "Direct JVM bytecode for " + fn.name() + " exceeded 65535 bytes (" + len + ")");
            }
            if (len > C2_HUGE_METHOD_WARN_BYTES) {
                LOGGER.log(
                        Level.WARNING,
                        "[JvmBytecodeConceptEmitter S-004] Method "
                                + fn.name()
                                + " bytecode size is "
                                + len
                                + " bytes (> HotSpot C2 7500B threshold; risk of DontCompileHugeMethods)");
            }
            byte[] trimmed = new byte[len];
            System.arraycopy(buf, 0, trimmed, 0, len);
            return new CompiledMethod(binding, trimmed, Math.min(65535, maxStack + 8), nextSlot);
        }

        CompiledMethod compileColumnY(MethodBinding binding, ColumnYPartition part) {
            this.columnYReturnGotos = new ArrayList<>();
            for (var entry : part.syntheticLocals().entrySet()) {
                declareLocalIfAbsent(entry.getKey(), entry.getValue());
            }
            // 1. Zero-initialize all non-param locals up front.
            for (String localName : nonParamLocals) {
                ConceptType t = localTypes.get(localName);
                emitDefaultConst(t);
                emitStore(localName, t);
            }
            // 2. Emit hoisted Y-invariant statements ONCE before the Y loop.
            emitStatements(part.hoistedStmts());

            // 3. Loop: for (int i = 0; i < countY; i++)
            // slot 0:x, 1:baseY, 2:stepY, 3:countY, 4:z, 5:out, 6:outOffset, 7:i, 8:y
            emitByte(OP_ICONST_0);
            adjustStack(1);
            emitVarOp(OP_ISTORE, 7);
            adjustStack(-1);

            int loopHead = len;
            emitVarOp(OP_ILOAD, 7);
            adjustStack(1);
            emitVarOp(OP_ILOAD, 3);
            adjustStack(1);
            // if (i < countY) skip goto_w to loopEnd
            emitByte(OP_IF_ICMPLT);
            emitShort(8);
            adjustStack(-2);
            int jumpToLoopEnd = emitGotoWPlaceholder();

            // Compute y = baseY + i * stepY -> slot 8
            emitVarOp(OP_ILOAD, 1);
            adjustStack(1);
            emitVarOp(OP_ILOAD, 7);
            adjustStack(1);
            emitVarOp(OP_ILOAD, 2);
            adjustStack(1);
            emitByte(OP_IMUL);
            adjustStack(-1);
            emitByte(OP_IADD);
            adjustStack(-1);
            emitVarOp(OP_ISTORE, 8);
            adjustStack(-1);

            // Reset non-hoisted locals at start of each iteration to guarantee exact 0.0 ULP parity with evalEntry.
            for (String localName : nonParamLocals) {
                if (!part.hoistedVars().contains(localName)) {
                    ConceptType t = localTypes.get(localName);
                    emitDefaultConst(t);
                    emitStore(localName, t);
                }
            }

            // Emit Y-dependent loop body (Return statements write out[outOffset + i] and jump to loopNext).
            emitStatements(part.loopStmts());

            // Fallback store 0.0f if control falls off end without Return.
            emitVarOp(OP_ALOAD, 5);
            adjustStack(1);
            emitVarOp(OP_ILOAD, 6);
            adjustStack(1);
            emitVarOp(OP_ILOAD, 7);
            adjustStack(1);
            emitByte(OP_IADD);
            adjustStack(-1);
            emitByte(OP_FCONST_0);
            adjustStack(1);
            emitByte(OP_FASTORE);
            adjustStack(-3);

            int loopNext = len;
            for (int gotoPos : columnYReturnGotos) {
                patchGotoW(gotoPos, loopNext);
            }
            // i++
            emitByte(OP_IINC);
            emitByte(7);
            emitByte(1);
            int backJump = emitGotoWPlaceholder();
            patchGotoW(backJump, loopHead);

            patchGotoW(jumpToLoopEnd, len);
            emitByte(OP_RETURN);

            if (len > MAX_METHOD_CODE_BYTES) {
                LOGGER.log(
                        Level.WARNING,
                        "[JvmBytecodeConceptEmitter S-004] Skipping evalColumnY for "
                                + fn.name()
                                + " (exceeded 65535 bytes: "
                                + len
                                + ")");
                return null;
            }
            if (len > C2_HUGE_METHOD_WARN_BYTES) {
                LOGGER.log(
                        Level.WARNING,
                        "[JvmBytecodeConceptEmitter S-004] evalColumnY for "
                                + fn.name()
                                + " bytecode size is "
                                + len
                                + " bytes (> HotSpot C2 7500B threshold)");
            }
            byte[] trimmed = new byte[len];
            System.arraycopy(buf, 0, trimmed, 0, len);
            return new CompiledMethod(binding, trimmed, Math.min(65535, maxStack + 8), nextSlot);
        }

        private void emitStatements(List<ConceptStmt> stmts) {
            for (ConceptStmt s : stmts) {
                if (s instanceof ConceptStmt.VarDecl vd) {
                    ConceptType slotType = localTypes.getOrDefault(vd.name(), vd.type());
                    if (vd.initializer() != null) {
                        emitExprCoerced(vd.initializer(), slotType);
                    } else {
                        emitDefaultConst(slotType);
                    }
                    emitStore(vd.name(), slotType);
                } else if (s instanceof ConceptStmt.Assign as) {
                    ConceptType slotType = localTypes.getOrDefault(as.name(), as.type());
                    emitExprCoerced(as.value(), slotType);
                    emitStore(as.name(), slotType);
                } else if (s instanceof ConceptStmt.If ifStmt) {
                    emitIf(ifStmt);
                } else if (s instanceof ConceptStmt.While w) {
                    emitWhile(w);
                } else if (s instanceof ConceptStmt.Return ret) {
                    if (columnYMode && columnYReturnGotos != null) {
                        // Store (float) return value into out[outOffset + i] and jump to loopNext
                        emitVarOp(OP_ALOAD, 5);
                        adjustStack(1);
                        emitVarOp(OP_ILOAD, 6);
                        adjustStack(1);
                        emitVarOp(OP_ILOAD, 7);
                        adjustStack(1);
                        emitByte(OP_IADD);
                        adjustStack(-1);
                        if (ret.value() != null) {
                            emitExprCoerced(ret.value(), ConceptType.F32);
                        } else {
                            emitByte(OP_FCONST_0);
                            adjustStack(1);
                        }
                        emitByte(OP_FASTORE);
                        adjustStack(-3);
                        columnYReturnGotos.add(emitGotoWPlaceholder());
                    } else if (fn.returnType() == ConceptType.VOID) {
                        emitByte(OP_RETURN);
                    } else {
                        if (ret.value() != null) {
                            emitExprCoerced(ret.value(), fn.returnType());
                        } else {
                            emitDefaultConst(fn.returnType());
                        }
                        if (memo2dSpec != null && col2dRefs != null) {
                            emitMemo2DStoreAndReturn();
                        } else {
                            emitReturnOpcode(fn.returnType());
                        }
                    }
                } else if (s instanceof ConceptStmt.StoreBuffer3D) {
                    throw new UnsupportedOperationException(
                            "StoreBuffer3D is not supported in scalar Double3DEvaluator bytecode");
                }
            }
        }

        private void emitMemo2DPrologue() {
            int slotId = memo2dSpec.slotId();
            // Col2DCache c = (Col2DCache) __col2dCache.get();
            emitByte(OP_GETSTATIC);
            emitShort(col2dRefs.cacheFieldRefIdx());
            adjustStack(1);
            emitByte(OP_INVOKEVIRTUAL);
            emitShort(col2dRefs.tlGetMethodRefIdx());
            emitByte(OP_CHECKCAST);
            emitShort(col2dRefs.cacheClassIdx());
            emitVarOp(OP_ASTORE, memoCacheSlot);
            adjustStack(-1);

            // if (c.valid[slotId] == 0) goto miss;
            emitVarOp(OP_ALOAD, memoCacheSlot);
            adjustStack(1);
            emitByte(OP_GETFIELD);
            emitShort(col2dRefs.validFieldRefIdx());
            emitIntConst(slotId);
            emitByte(OP_BALOAD);
            adjustStack(-1);
            int brValidPos = len;
            emitByte(OP_IFEQ);
            emitShort(0);
            adjustStack(-1);

            // if (c.hitX[slotId] != xParam) goto miss;
            emitVarOp(OP_ALOAD, memoCacheSlot);
            adjustStack(1);
            emitByte(OP_GETFIELD);
            emitShort(col2dRefs.hitXFieldRefIdx());
            emitIntConst(slotId);
            emitByte(OP_IALOAD);
            adjustStack(-1);
            emitVarOp(OP_ILOAD, memo2dSpec.xParamSlot());
            adjustStack(1);
            int brXPos = len;
            emitByte(OP_IF_ICMPNE);
            emitShort(0);
            adjustStack(-2);

            // if (yParamSlot >= 0 && c.hitY[slotId] != yParam) goto miss;
            int brYPos = -1;
            if (memo2dSpec.yParamSlot() >= 0) {
                emitVarOp(OP_ALOAD, memoCacheSlot);
                adjustStack(1);
                emitByte(OP_GETFIELD);
                emitShort(col2dRefs.hitYFieldRefIdx());
                emitIntConst(slotId);
                emitByte(OP_IALOAD);
                adjustStack(-1);
                emitVarOp(OP_ILOAD, memo2dSpec.yParamSlot());
                adjustStack(1);
                brYPos = len;
                emitByte(OP_IF_ICMPNE);
                emitShort(0);
                adjustStack(-2);
            }

            // if (c.hitZ[slotId] != zParam) goto miss;
            emitVarOp(OP_ALOAD, memoCacheSlot);
            adjustStack(1);
            emitByte(OP_GETFIELD);
            emitShort(col2dRefs.hitZFieldRefIdx());
            emitIntConst(slotId);
            emitByte(OP_IALOAD);
            adjustStack(-1);
            emitVarOp(OP_ILOAD, memo2dSpec.zParamSlot());
            adjustStack(1);
            int brZPos = len;
            emitByte(OP_IF_ICMPNE);
            emitShort(0);
            adjustStack(-2);

            // return c.dvals[slotId] (cast to float if F32);
            emitVarOp(OP_ALOAD, memoCacheSlot);
            adjustStack(1);
            emitByte(OP_GETFIELD);
            emitShort(col2dRefs.dvalsFieldRefIdx());
            emitIntConst(slotId);
            emitByte(OP_DALOAD);
            // arrayref(1) + index(1) -> double(2): net 0
            if (fn.returnType() == ConceptType.F32) {
                emitByte(OP_D2F);
                adjustStack(-1);
                emitByte(OP_FRETURN);
                adjustStack(-1);
            } else {
                emitByte(OP_DRETURN);
                adjustStack(-2);
            }

            // miss:
            int missPos = len;
            patchShort(brValidPos + 1, missPos - brValidPos);
            patchShort(brXPos + 1, missPos - brXPos);
            if (brYPos >= 0) {
                patchShort(brYPos + 1, missPos - brYPos);
            }
            patchShort(brZPos + 1, missPos - brZPos);
        }

        private void emitMemo2DStoreAndReturn() {
            int slotId = memo2dSpec.slotId();
            if (fn.returnType() == ConceptType.F32) {
                emitVarOp(OP_FSTORE, memoRetTmpSlot);
                adjustStack(-1);
            } else {
                emitVarOp(OP_DSTORE, memoRetTmpSlot);
                adjustStack(-2);
            }
            // c.hitX[slotId] = xParam;
            emitVarOp(OP_ALOAD, memoCacheSlot);
            adjustStack(1);
            emitByte(OP_GETFIELD);
            emitShort(col2dRefs.hitXFieldRefIdx());
            emitIntConst(slotId);
            emitVarOp(OP_ILOAD, memo2dSpec.xParamSlot());
            adjustStack(1);
            emitByte(OP_IASTORE);
            adjustStack(-3);

            // c.hitY[slotId] = yParam (only for 3D helpers);
            if (memo2dSpec.yParamSlot() >= 0) {
                emitVarOp(OP_ALOAD, memoCacheSlot);
                adjustStack(1);
                emitByte(OP_GETFIELD);
                emitShort(col2dRefs.hitYFieldRefIdx());
                emitIntConst(slotId);
                emitVarOp(OP_ILOAD, memo2dSpec.yParamSlot());
                adjustStack(1);
                emitByte(OP_IASTORE);
                adjustStack(-3);
            }

            // c.hitZ[slotId] = zParam;
            emitVarOp(OP_ALOAD, memoCacheSlot);
            adjustStack(1);
            emitByte(OP_GETFIELD);
            emitShort(col2dRefs.hitZFieldRefIdx());
            emitIntConst(slotId);
            emitVarOp(OP_ILOAD, memo2dSpec.zParamSlot());
            adjustStack(1);
            emitByte(OP_IASTORE);
            adjustStack(-3);

            // c.dvals[slotId] = (double) ret;
            emitVarOp(OP_ALOAD, memoCacheSlot);
            adjustStack(1);
            emitByte(OP_GETFIELD);
            emitShort(col2dRefs.dvalsFieldRefIdx());
            emitIntConst(slotId);
            if (fn.returnType() == ConceptType.F32) {
                emitVarOp(OP_FLOAD, memoRetTmpSlot);
                adjustStack(1);
                emitByte(OP_F2D);
                adjustStack(1);
            } else {
                emitVarOp(OP_DLOAD, memoRetTmpSlot);
                adjustStack(2);
            }
            emitByte(OP_DASTORE);
            adjustStack(-4);

            // c.valid[slotId] = 1;
            emitVarOp(OP_ALOAD, memoCacheSlot);
            adjustStack(1);
            emitByte(OP_GETFIELD);
            emitShort(col2dRefs.validFieldRefIdx());
            emitIntConst(slotId);
            emitByte(OP_ICONST_1);
            adjustStack(1);
            emitByte(OP_BASTORE);
            adjustStack(-3);

            // return ret;
            if (fn.returnType() == ConceptType.F32) {
                emitVarOp(OP_FLOAD, memoRetTmpSlot);
                adjustStack(1);
                emitByte(OP_FRETURN);
                adjustStack(-1);
            } else {
                emitVarOp(OP_DLOAD, memoRetTmpSlot);
                adjustStack(2);
                emitByte(OP_DRETURN);
                adjustStack(-2);
            }
        }

        private void patchShort(int pos, int value) {
            buf[pos] = (byte) ((value >>> 8) & 0xFF);
            buf[pos + 1] = (byte) (value & 0xFF);
        }

        private void emitIf(ConceptStmt.If ifStmt) {
            emitExprCoerced(ifStmt.condition(), ConceptType.BOOL);
            // If condition != 0, skip over goto_w (8 bytes: ifne(3) + goto_w(5))
            emitByte(OP_IFNE);
            emitShort(8);
            adjustStack(-1);
            int jumpToElse = emitGotoWPlaceholder();

            emitStatements(ifStmt.thenBody());

            if (ifStmt.elseBody().isEmpty()) {
                patchGotoW(jumpToElse, len);
            } else {
                int jumpToEnd = emitGotoWPlaceholder();
                patchGotoW(jumpToElse, len);
                emitStatements(ifStmt.elseBody());
                patchGotoW(jumpToEnd, len);
            }
        }

        private void emitWhile(ConceptStmt.While w) {
            int loopStart = len;
            emitExprCoerced(w.condition(), ConceptType.BOOL);
            emitByte(OP_IFNE);
            emitShort(8);
            adjustStack(-1);
            int jumpOut = emitGotoWPlaceholder();

            emitStatements(w.body());
            emitGotoWTo(loopStart);
            patchGotoW(jumpOut, len);
        }

        private void emitExprCoerced(ConceptExpr expr, ConceptType target) {
            ConceptType actual = emitExpr(expr);
            emitCoerce(actual, target);
        }

        private ConceptType emitExpr(ConceptExpr expr) {
            if (expr instanceof ConceptExpr.Var v) {
                ConceptType slotType = localTypes.getOrDefault(v.name(), v.type());
                emitLoad(v.name(), slotType);
                return slotType;
            }
            if (expr instanceof ConceptExpr.IntConst ic) {
                emitIntConst(ic.value());
                return ConceptType.I32;
            }
            if (expr instanceof ConceptExpr.LongConst lc) {
                emitLongConst(lc.value());
                return ConceptType.I64;
            }
            if (expr instanceof ConceptExpr.FloatConst fc) {
                emitFloatConst(fc.value());
                return ConceptType.F32;
            }
            if (expr instanceof ConceptExpr.DoubleConst dc) {
                emitDoubleConst(dc.value());
                return ConceptType.F64;
            }
            if (expr instanceof ConceptExpr.BoolConst bc) {
                emitIntConst(bc.value() ? 1 : 0);
                return ConceptType.BOOL;
            }
            if (expr instanceof ConceptExpr.UnaryOp u) {
                return emitUnary(u);
            }
            if (expr instanceof ConceptExpr.BinaryOp b) {
                return emitBinary(b);
            }
            if (expr instanceof ConceptExpr.Cast c) {
                emitExprCoerced(c.operand(), c.targetType());
                return c.targetType();
            }
            if (expr instanceof ConceptExpr.Ternary t) {
                return emitTernary(t);
            }
            if (expr instanceof ConceptExpr.LutLoad l) {
                return emitLutLoad(l);
            }
            if (expr instanceof ConceptExpr.MathIntrinsic m) {
                return emitMathIntrinsic(m);
            }
            if (expr instanceof ConceptExpr.Call c) {
                return emitCall(c);
            }
            if (expr instanceof ConceptExpr.CompareTemp cmp) {
                return emitCompareTemp(cmp);
            }
            throw new UnsupportedOperationException("Unsupported ConceptExpr: " + expr.getClass().getName());
        }

        private ConceptType emitUnary(ConceptExpr.UnaryOp u) {
            switch (u.op()) {
                case NEG -> {
                    ConceptType t = u.type();
                    emitExprCoerced(u.operand(), t);
                    switch (t) {
                        case I64 -> emitByte(OP_LNEG);
                        case F32 -> emitByte(OP_FNEG);
                        case F64 -> emitByte(OP_DNEG);
                        default -> emitByte(OP_INEG);
                    }
                    return t;
                }
                case LOGICAL_NOT -> {
                    emitExprCoerced(u.operand(), ConceptType.BOOL);
                    // ifeq +9 (3), iconst_0 (1), goto_w (5), iconst_1 (1)
                    emitByte(OP_IFEQ);
                    emitShort(9);
                    adjustStack(-1);
                    emitByte(OP_ICONST_0);
                    adjustStack(1);
                    int jumpEnd = emitGotoWPlaceholder();
                    adjustStack(-1); // merge stack height
                    emitByte(OP_ICONST_0 + 1);
                    adjustStack(1);
                    patchGotoW(jumpEnd, len);
                    return ConceptType.BOOL;
                }
                case BIT_NOT -> {
                    ConceptType t = u.type() == ConceptType.I64 ? ConceptType.I64 : ConceptType.I32;
                    emitExprCoerced(u.operand(), t);
                    if (t == ConceptType.I64) {
                        emitLongConst(-1L);
                        emitByte(OP_LXOR);
                        adjustStack(-2);
                    } else {
                        emitByte(OP_ICONST_M1);
                        adjustStack(1);
                        emitByte(OP_IXOR);
                        adjustStack(-1);
                    }
                    return t;
                }
            }
            throw new IllegalStateException("Unhandled unary op: " + u.op());
        }

        private ConceptType emitBinary(ConceptExpr.BinaryOp b) {
            BinaryOperator op = b.op();
            if (op == BinaryOperator.LOGICAL_AND) {
                emitExprCoerced(b.left(), ConceptType.BOOL);
                emitByte(OP_IFNE);
                emitShort(8);
                adjustStack(-1);
                int jumpFalse1 = emitGotoWPlaceholder();

                emitExprCoerced(b.right(), ConceptType.BOOL);
                emitByte(OP_IFNE);
                emitShort(8);
                adjustStack(-1);
                int jumpFalse2 = emitGotoWPlaceholder();

                emitIntConst(1);
                int jumpEnd = emitGotoWPlaceholder();
                adjustStack(-1);

                patchGotoW(jumpFalse1, len);
                patchGotoW(jumpFalse2, len);
                emitIntConst(0);
                patchGotoW(jumpEnd, len);
                return ConceptType.BOOL;
            }
            if (op == BinaryOperator.LOGICAL_OR) {
                emitExprCoerced(b.left(), ConceptType.BOOL);
                emitByte(OP_IFEQ);
                emitShort(8);
                adjustStack(-1);
                int jumpTrue1 = emitGotoWPlaceholder();

                emitExprCoerced(b.right(), ConceptType.BOOL);
                emitByte(OP_IFEQ);
                emitShort(8);
                adjustStack(-1);
                int jumpTrue2 = emitGotoWPlaceholder();

                emitIntConst(0);
                int jumpEnd = emitGotoWPlaceholder();
                adjustStack(-1);

                patchGotoW(jumpTrue1, len);
                patchGotoW(jumpTrue2, len);
                emitIntConst(1);
                patchGotoW(jumpEnd, len);
                return ConceptType.BOOL;
            }
            if (op.isComparison()) {
                return emitComparison(op, b.left(), b.right());
            }
            if (op == BinaryOperator.SHL || op == BinaryOperator.SHR || op == BinaryOperator.USHR) {
                ConceptType t = b.type() == ConceptType.I64 ? ConceptType.I64 : ConceptType.I32;
                emitExprCoerced(b.left(), t);
                emitExprCoerced(b.right(), ConceptType.I32);
                if (t == ConceptType.I64) {
                    emitByte(switch (op) {
                        case SHL -> OP_LSHL;
                        case SHR -> OP_LSHR;
                        case USHR -> OP_LUSHR;
                        default -> throw new IllegalStateException();
                    });
                    adjustStack(-1);
                } else {
                    emitByte(switch (op) {
                        case SHL -> OP_ISHL;
                        case SHR -> OP_ISHR;
                        case USHR -> OP_IUSHR;
                        default -> throw new IllegalStateException();
                    });
                    adjustStack(-1);
                }
                return t;
            }

            ConceptType t = b.type();
            emitExprCoerced(b.left(), t);
            emitExprCoerced(b.right(), t);
            int popSlots = slotSize(t);
            switch (op) {
                case ADD -> emitByte(switch (t) {
                    case I64 -> OP_LADD;
                    case F32 -> OP_FADD;
                    case F64 -> OP_DADD;
                    default -> OP_IADD;
                });
                case SUB -> emitByte(switch (t) {
                    case I64 -> OP_LSUB;
                    case F32 -> OP_FSUB;
                    case F64 -> OP_DSUB;
                    default -> OP_ISUB;
                });
                case MUL -> emitByte(switch (t) {
                    case I64 -> OP_LMUL;
                    case F32 -> OP_FMUL;
                    case F64 -> OP_DMUL;
                    default -> OP_IMUL;
                });
                case DIV -> emitByte(switch (t) {
                    case I64 -> OP_LDIV;
                    case F32 -> OP_FDIV;
                    case F64 -> OP_DDIV;
                    default -> OP_IDIV;
                });
                case REM -> emitByte(switch (t) {
                    case I64 -> OP_LREM;
                    case F32 -> OP_FREM;
                    case F64 -> OP_DREM;
                    default -> OP_IREM;
                });
                case BIT_AND -> emitByte(t == ConceptType.I64 ? OP_LAND : OP_IAND);
                case BIT_OR -> emitByte(t == ConceptType.I64 ? OP_LOR : OP_IOR);
                case BIT_XOR -> emitByte(t == ConceptType.I64 ? OP_LXOR : OP_IXOR);
                default -> throw new IllegalStateException("Unhandled binary op: " + op);
            }
            adjustStack(-popSlots);
            return t;
        }

        private ConceptType emitComparison(BinaryOperator op, ConceptExpr left, ConceptExpr right) {
            ConceptType common = promoteNumeric(left.type(), right.type());
            emitExprCoerced(left, common);
            emitExprCoerced(right, common);

            int trueBranchOp;
            int stackConsume;
            if (common == ConceptType.I32) {
                trueBranchOp = switch (op) {
                    case EQ -> OP_IF_ICMPEQ;
                    case NE -> OP_IF_ICMPNE;
                    case LT -> OP_IF_ICMPLT;
                    case LE -> OP_IF_ICMPLE;
                    case GT -> OP_IF_ICMPGT;
                    case GE -> OP_IF_ICMPGE;
                    default -> throw new IllegalStateException("Not a comparison: " + op);
                };
                stackConsume = 2;
            } else {
                if (common == ConceptType.I64) {
                    emitByte(OP_LCMP);
                    adjustStack(-3); // consumes 4 slots, pushes 1 int
                } else if (common == ConceptType.F32) {
                    // For LT/LE, NaN -> false requires fcmpg (+1); for GT/GE/EQ/NE, fcmpl (-1)
                    emitByte((op == BinaryOperator.LT || op == BinaryOperator.LE) ? OP_FCMPG : OP_FCMPL);
                    adjustStack(-1); // consumes 2, pushes 1
                } else {
                    emitByte((op == BinaryOperator.LT || op == BinaryOperator.LE) ? OP_DCMPG : OP_DCMPL);
                    adjustStack(-3); // consumes 4, pushes 1
                }
                trueBranchOp = switch (op) {
                    case EQ -> OP_IFEQ;
                    case NE -> OP_IFNE;
                    case LT -> OP_IFLT;
                    case LE -> OP_IFLE;
                    case GT -> OP_IFGT;
                    case GE -> OP_IFGE;
                    default -> throw new IllegalStateException("Not a comparison: " + op);
                };
                stackConsume = 1;
            }

            // if<true> -> +9 (skip iconst_0 + goto_w)
            emitByte(trueBranchOp);
            emitShort(9);
            adjustStack(-stackConsume);
            emitByte(OP_ICONST_0);
            adjustStack(1);
            int jumpEnd = emitGotoWPlaceholder();
            adjustStack(-1);
            emitByte(OP_ICONST_0 + 1);
            adjustStack(1);
            patchGotoW(jumpEnd, len);
            return ConceptType.BOOL;
        }

        private ConceptType emitCompareTemp(ConceptExpr.CompareTemp cmp) {
            ConceptType common = promoteNumeric(cmp.left().type(), cmp.right().type());
            emitExprCoerced(cmp.left(), common);
            emitExprCoerced(cmp.right(), common);
            switch (common) {
                case I64 -> {
                    emitByte(OP_LCMP);
                    adjustStack(-3);
                }
                case F32 -> {
                    emitByte(cmp.nanPositive() ? OP_FCMPG : OP_FCMPL);
                    adjustStack(-1);
                }
                case F64 -> {
                    emitByte(cmp.nanPositive() ? OP_DCMPG : OP_DCMPL);
                    adjustStack(-3);
                }
                default -> {
                    int ref = cp.methodRef("java/lang/Integer", "compare", "(II)I");
                    emitInvokeStatic(ref, 2, 1);
                }
            }
            return ConceptType.I32;
        }

        private ConceptType emitTernary(ConceptExpr.Ternary t) {
            ConceptType resType = t.type();
            emitExprCoerced(t.condition(), ConceptType.BOOL);
            emitByte(OP_IFNE);
            emitShort(8);
            adjustStack(-1);
            int jumpElse = emitGotoWPlaceholder();

            emitExprCoerced(t.thenExpr(), resType);
            int jumpEnd = emitGotoWPlaceholder();
            adjustStack(-slotSize(resType));

            patchGotoW(jumpElse, len);
            emitExprCoerced(t.elseExpr(), resType);
            patchGotoW(jumpEnd, len);
            return resType;
        }

        private ConceptType emitLutLoad(ConceptExpr.LutLoad l) {
            LutFieldBinding binding = lutBySymbol.get(l.lutSymbol());
            if (binding == null) {
                throw new IllegalStateException("Unresolved LUT symbol: " + l.lutSymbol());
            }
            emitByte(OP_GETSTATIC);
            emitShort(binding.fieldRefIdx());
            adjustStack(1);
            emitExprCoerced(l.index(), ConceptType.I32);
            switch (binding.elementType()) {
                case I64 -> {
                    emitByte(OP_LALOAD);
                    // consumes ref(1) + idx(1), pushes long(2) -> net 0
                }
                case F32 -> {
                    emitByte(OP_FALOAD);
                    adjustStack(-1);
                }
                case F64 -> {
                    emitByte(OP_DALOAD);
                    // consumes ref(1) + idx(1), pushes double(2) -> net 0
                }
                default -> {
                    emitByte(OP_IALOAD);
                    adjustStack(-1);
                }
            }
            emitCoerce(binding.elementType(), l.elementType());
            return l.elementType();
        }

        private ConceptType emitMathIntrinsic(ConceptExpr.MathIntrinsic m) {
            MathIntrinsicKind kind = m.kind();
            List<ConceptExpr> args = m.arguments();
            ConceptType resType = m.type();
            switch (kind) {
                case FLOOR, CEIL, SQRT, SIN, COS, TAN, EXP, LOG -> {
                    emitExprCoerced(args.get(0), ConceptType.F64);
                    String name = switch (kind) {
                        case FLOOR -> "floor";
                        case CEIL -> "ceil";
                        case SQRT -> "sqrt";
                        case SIN -> "sin";
                        case COS -> "cos";
                        case TAN -> "tan";
                        case EXP -> "exp";
                        case LOG -> "log";
                        default -> throw new IllegalStateException();
                    };
                    emitInvokeStatic(cp.methodRef("java/lang/Math", name, "(D)D"), 2, 2);
                    emitCoerce(ConceptType.F64, resType);
                    return resType;
                }
                case ROUND -> {
                    ConceptExpr a0 = args.get(0);
                    if (a0.type() == ConceptType.F32) {
                        emitExprCoerced(a0, ConceptType.F32);
                        emitInvokeStatic(cp.methodRef("java/lang/Math", "round", "(F)I"), 1, 1);
                        emitCoerce(ConceptType.I32, resType);
                    } else {
                        emitExprCoerced(a0, ConceptType.F64);
                        emitInvokeStatic(cp.methodRef("java/lang/Math", "round", "(D)J"), 2, 2);
                        emitCoerce(ConceptType.I64, resType);
                    }
                    return resType;
                }
                case ABS -> {
                    ConceptType t = resType;
                    emitExprCoerced(args.get(0), t);
                    switch (t) {
                        case I64 -> emitInvokeStatic(cp.methodRef("java/lang/Math", "abs", "(J)J"), 2, 2);
                        case F32 -> emitInvokeStatic(cp.methodRef("java/lang/Math", "abs", "(F)F"), 1, 1);
                        case F64 -> emitInvokeStatic(cp.methodRef("java/lang/Math", "abs", "(D)D"), 2, 2);
                        default -> emitInvokeStatic(cp.methodRef("java/lang/Math", "abs", "(I)I"), 1, 1);
                    }
                    return t;
                }
                case ATAN2, POW -> {
                    emitExprCoerced(args.get(0), ConceptType.F64);
                    emitExprCoerced(args.get(1), ConceptType.F64);
                    String name = kind == MathIntrinsicKind.ATAN2 ? "atan2" : "pow";
                    emitInvokeStatic(cp.methodRef("java/lang/Math", name, "(DD)D"), 4, 2);
                    emitCoerce(ConceptType.F64, resType);
                    return resType;
                }
                case MIN, MAX -> {
                    ConceptType t = resType;
                    emitExprCoerced(args.get(0), t);
                    emitExprCoerced(args.get(1), t);
                    String name = kind == MathIntrinsicKind.MIN ? "min" : "max";
                    switch (t) {
                        case I64 -> emitInvokeStatic(cp.methodRef("java/lang/Math", name, "(JJ)J"), 4, 2);
                        case F32 -> emitInvokeStatic(cp.methodRef("java/lang/Math", name, "(FF)F"), 2, 1);
                        case F64 -> emitInvokeStatic(cp.methodRef("java/lang/Math", name, "(DD)D"), 4, 2);
                        default -> emitInvokeStatic(cp.methodRef("java/lang/Math", name, "(II)I"), 2, 1);
                    }
                    return t;
                }
                case FMA -> {
                    if (resType == ConceptType.F32) {
                        emitExprCoerced(args.get(0), ConceptType.F32);
                        emitExprCoerced(args.get(1), ConceptType.F32);
                        emitExprCoerced(args.get(2), ConceptType.F32);
                        emitInvokeStatic(cp.methodRef("java/lang/Math", "fma", "(FFF)F"), 3, 1);
                    } else {
                        emitExprCoerced(args.get(0), ConceptType.F64);
                        emitExprCoerced(args.get(1), ConceptType.F64);
                        emitExprCoerced(args.get(2), ConceptType.F64);
                        emitInvokeStatic(cp.methodRef("java/lang/Math", "fma", "(DDD)D"), 6, 2);
                        emitCoerce(ConceptType.F64, resType);
                    }
                    return resType;
                }
                case IS_FINITE, IS_NAN -> {
                    ConceptExpr a0 = args.get(0);
                    String name = kind == MathIntrinsicKind.IS_FINITE ? "isFinite" : "isNaN";
                    if (a0.type() == ConceptType.F32) {
                        emitExprCoerced(a0, ConceptType.F32);
                        emitInvokeStatic(cp.methodRef("java/lang/Float", name, "(F)Z"), 1, 1);
                    } else {
                        emitExprCoerced(a0, ConceptType.F64);
                        emitInvokeStatic(cp.methodRef("java/lang/Double", name, "(D)Z"), 2, 1);
                    }
                    return ConceptType.BOOL;
                }
                case POPCOUNT, CLZ, CTZ -> {
                    ConceptExpr a0 = args.get(0);
                    String name = switch (kind) {
                        case POPCOUNT -> "bitCount";
                        case CLZ -> "numberOfLeadingZeros";
                        case CTZ -> "numberOfTrailingZeros";
                        default -> throw new IllegalStateException();
                    };
                    if (a0.type() == ConceptType.I64) {
                        emitExprCoerced(a0, ConceptType.I64);
                        emitInvokeStatic(cp.methodRef("java/lang/Long", name, "(J)I"), 2, 1);
                    } else {
                        emitExprCoerced(a0, ConceptType.I32);
                        emitInvokeStatic(cp.methodRef("java/lang/Integer", name, "(I)I"), 1, 1);
                    }
                    emitCoerce(ConceptType.I32, resType);
                    return resType;
                }
            }
            throw new IllegalStateException("Unhandled MathIntrinsic: " + kind);
        }

        private ConceptType emitCall(ConceptExpr.Call c) {
            String callKey = c.ownerClass() + "#" + c.methodName() + c.descriptor();
            MethodBinding target = methodByKey.get(callKey);
            if (target == null) {
                target = methodByName.get(c.methodName());
            }
            if (target == null) {
                throw new IllegalStateException("Unresolved helper call in ConceptModule: " + callKey);
            }
            List<ConceptParam> calleeParams = target.fn().parameters();
            int argSlots = 0;
            for (int i = 0; i < calleeParams.size(); i++) {
                ConceptType pType = calleeParams.get(i).type();
                if (i < c.arguments().size()) {
                    emitExprCoerced(c.arguments().get(i), pType);
                } else {
                    emitDefaultConst(pType);
                }
                argSlots += slotSize(pType);
            }
            ConceptType calleeRet = target.fn().returnType();
            int retSlots = calleeRet == ConceptType.VOID ? 0 : slotSize(calleeRet);
            emitInvokeStatic(target.methodRefIdx(), argSlots, retSlots);
            if (calleeRet != ConceptType.VOID && c.returnType() == ConceptType.VOID) {
                emitByte(retSlots == 2 ? OP_POP2 : OP_POP);
                adjustStack(-retSlots);
                return ConceptType.VOID;
            }
            if (calleeRet != ConceptType.VOID) {
                emitCoerce(calleeRet, c.returnType());
            }
            return c.returnType();
        }

        private void emitCoerce(ConceptType from, ConceptType to) {
            if (from == to || to == ConceptType.VOID) {
                return;
            }
            if ((from == ConceptType.I32 || from == ConceptType.BOOL) && to == ConceptType.I32) {
                return;
            }
            if (to == ConceptType.BOOL) {
                if (from == ConceptType.BOOL) {
                    return;
                }
                if (from == ConceptType.I64) {
                    emitByte(OP_LCONST_0);
                    adjustStack(2);
                    emitByte(OP_LCMP);
                    adjustStack(-3);
                } else if (from == ConceptType.F32) {
                    emitByte(OP_FCONST_0);
                    adjustStack(1);
                    emitByte(OP_FCMPL);
                    adjustStack(-1);
                } else if (from == ConceptType.F64) {
                    emitByte(OP_DCONST_0);
                    adjustStack(2);
                    emitByte(OP_DCMPL);
                    adjustStack(-3);
                }
                // int on stack -> normalize to 0 or 1
                emitByte(OP_IFEQ);
                emitShort(9);
                adjustStack(-1);
                emitByte(OP_ICONST_0 + 1);
                adjustStack(1);
                int jumpEnd = emitGotoWPlaceholder();
                adjustStack(-1);
                emitByte(OP_ICONST_0);
                adjustStack(1);
                patchGotoW(jumpEnd, len);
                return;
            }
            switch (from) {
                case BOOL, I32 -> {
                    switch (to) {
                        case I64 -> {
                            emitByte(OP_I2L);
                            adjustStack(1);
                        }
                        case F32 -> emitByte(OP_I2F);
                        case F64 -> {
                            emitByte(OP_I2D);
                            adjustStack(1);
                        }
                        default -> {}
                    }
                }
                case I64 -> {
                    switch (to) {
                        case I32 -> {
                            emitByte(OP_L2I);
                            adjustStack(-1);
                        }
                        case F32 -> {
                            emitByte(OP_L2F);
                            adjustStack(-1);
                        }
                        case F64 -> emitByte(OP_L2D);
                        default -> {}
                    }
                }
                case F32 -> {
                    switch (to) {
                        case I32 -> emitByte(OP_F2I);
                        case I64 -> {
                            emitByte(OP_F2L);
                            adjustStack(1);
                        }
                        case F64 -> {
                            emitByte(OP_F2D);
                            adjustStack(1);
                        }
                        default -> {}
                    }
                }
                case F64 -> {
                    switch (to) {
                        case I32 -> {
                            emitByte(OP_D2I);
                            adjustStack(-1);
                        }
                        case I64 -> emitByte(OP_D2L);
                        case F32 -> {
                            emitByte(OP_D2F);
                            adjustStack(-1);
                        }
                        default -> {}
                    }
                }
                default -> {}
            }
        }

        private void emitDefaultConst(ConceptType t) {
            switch (t) {
                case I64 -> {
                    emitByte(OP_LCONST_0);
                    adjustStack(2);
                }
                case F32 -> {
                    emitByte(OP_FCONST_0);
                    adjustStack(1);
                }
                case F64 -> {
                    emitByte(OP_DCONST_0);
                    adjustStack(2);
                }
                default -> {
                    emitByte(OP_ICONST_0);
                    adjustStack(1);
                }
            }
        }

        private void emitIntConst(int v) {
            if (v >= -1 && v <= 5) {
                emitByte(OP_ICONST_0 + v);
            } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
                emitByte(OP_BIPUSH);
                emitByte(v & 0xFF);
            } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
                emitByte(OP_SIPUSH);
                emitShort(v);
            } else {
                emitLdc(cp.intConst(v));
            }
            adjustStack(1);
        }

        private void emitLongConst(long v) {
            if (v == 0L) {
                emitByte(OP_LCONST_0);
            } else if (v == 1L) {
                emitByte(OP_LCONST_1);
            } else {
                emitByte(OP_LDC2_W);
                emitShort(cp.longConst(v));
            }
            adjustStack(2);
        }

        private void emitFloatConst(float v) {
            int bits = Float.floatToRawIntBits(v);
            if (bits == 0) { // +0.0f only (preserve -0.0f via constant pool)
                emitByte(OP_FCONST_0);
            } else if (v == 1.0f) {
                emitByte(OP_FCONST_1);
            } else if (v == 2.0f) {
                emitByte(OP_FCONST_2);
            } else {
                emitLdc(cp.floatConst(v));
            }
            adjustStack(1);
        }

        private void emitDoubleConst(double v) {
            long bits = Double.doubleToRawLongBits(v);
            if (bits == 0L) { // +0.0d only (preserve -0.0d via constant pool)
                emitByte(OP_DCONST_0);
            } else if (v == 1.0d) {
                emitByte(OP_DCONST_1);
            } else {
                emitByte(OP_LDC2_W);
                emitShort(cp.doubleConst(v));
            }
            adjustStack(2);
        }

        private void emitLdc(int idx) {
            if (idx <= 255) {
                emitByte(OP_LDC);
                emitByte(idx);
            } else {
                emitByte(OP_LDC_W);
                emitShort(idx);
            }
        }

        private void emitLoad(String name, ConceptType t) {
            Integer slotObj = localSlots.get(name);
            if (slotObj == null) {
                throw new IllegalStateException("Undeclared local variable in ConceptFunction " + fn.name() + ": " + name);
            }
            int slot = slotObj;
            int op = switch (t) {
                case I64 -> OP_LLOAD;
                case F32 -> OP_FLOAD;
                case F64 -> OP_DLOAD;
                default -> OP_ILOAD;
            };
            emitVarOp(op, slot);
            adjustStack(slotSize(t));
        }

        private void emitStore(String name, ConceptType t) {
            int slot = localSlots.get(name);
            int op = switch (t) {
                case I64 -> OP_LSTORE;
                case F32 -> OP_FSTORE;
                case F64 -> OP_DSTORE;
                default -> OP_ISTORE;
            };
            emitVarOp(op, slot);
            adjustStack(-slotSize(t));
        }

        private void emitVarOp(int op, int slot) {
            if (slot <= 255) {
                emitByte(op);
                emitByte(slot);
            } else {
                emitByte(OP_WIDE);
                emitByte(op);
                emitShort(slot);
            }
        }

        private void emitReturnOpcode(ConceptType t) {
            switch (t) {
                case I64 -> {
                    emitByte(OP_LRETURN);
                    adjustStack(-2);
                }
                case F32 -> {
                    emitByte(OP_FRETURN);
                    adjustStack(-1);
                }
                case F64 -> {
                    emitByte(OP_DRETURN);
                    adjustStack(-2);
                }
                default -> {
                    emitByte(OP_IRETURN);
                    adjustStack(-1);
                }
            }
        }

        private void emitInvokeStatic(int methodRefIdx, int argSlots, int retSlots) {
            emitByte(OP_INVOKESTATIC);
            emitShort(methodRefIdx);
            adjustStack(retSlots - argSlots);
        }

        private int emitGotoWPlaceholder() {
            int opcodePos = len;
            emitByte(OP_GOTO_W);
            emitInt(0);
            return opcodePos;
        }

        private void emitGotoWTo(int targetPc) {
            int opcodePos = len;
            emitByte(OP_GOTO_W);
            emitInt(targetPc - opcodePos);
        }

        private void patchGotoW(int opcodePos, int targetPc) {
            int rel = targetPc - opcodePos;
            buf[opcodePos + 1] = (byte) (rel >>> 24);
            buf[opcodePos + 2] = (byte) (rel >>> 16);
            buf[opcodePos + 3] = (byte) (rel >>> 8);
            buf[opcodePos + 4] = (byte) rel;
        }

        private void adjustStack(int delta) {
            curStack += delta;
            if (curStack < 0) {
                curStack = 0;
            }
            if (curStack > maxStack) {
                maxStack = curStack;
            }
        }

        private void ensureCapacity(int extra) {
            if (len + extra > buf.length) {
                int newLen = Math.max(buf.length * 2, len + extra + 64);
                byte[] next = new byte[newLen];
                System.arraycopy(buf, 0, next, 0, len);
                buf = next;
            }
        }

        private void emitByte(int b) {
            ensureCapacity(1);
            buf[len++] = (byte) b;
        }

        private void emitShort(int s) {
            ensureCapacity(2);
            buf[len++] = (byte) (s >>> 8);
            buf[len++] = (byte) s;
        }

        private void emitInt(int v) {
            ensureCapacity(4);
            buf[len++] = (byte) (v >>> 24);
            buf[len++] = (byte) (v >>> 16);
            buf[len++] = (byte) (v >>> 8);
            buf[len++] = (byte) v;
        }
    }
}
