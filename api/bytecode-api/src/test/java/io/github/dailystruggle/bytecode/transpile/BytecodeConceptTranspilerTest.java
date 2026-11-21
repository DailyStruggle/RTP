package io.github.dailystruggle.bytecode.transpile;

import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptFunction;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptIrEvaluator;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptLut;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptModule;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.BatchLayout;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.EmitOptions;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.GpuExactArithmeticMode;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.TranspiledArtifact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification suite for {@link BytecodeSsaLifter}, {@link ConceptOptimizerPass},
 * {@link ConceptBackendEmitter}, {@link OpenClBackendEmitter}, and {@link RustBackendEmitter}.
 *
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy corpus-atlas-ADR-002
 * @trace REQ-CFG-ATLAS-001
 */
class BytecodeConceptTranspilerTest {

    /**
     * Sample procedural voxel/noise fixture compiled in this test module mirroring
     * {@code ProceduralGenerator#hashNoise}, {@code ProceduralGenerator#valueNoise1D},
     * {@code PerlinNoise#noise}, and {@code UnitCurve#smootherstep}/{@code linear}.
     */
    public static final class SampleProceduralNoise {

        private static final int[] P = new int[512];

        static {
            int[] permutation = {
                151, 160, 137, 91, 90, 15, 131, 13, 201, 95, 96, 53, 194, 233, 7, 225,
                140, 36, 103, 30, 69, 142, 8, 99, 37, 240, 21, 10, 23, 190, 6, 148,
                247, 120, 234, 75, 0, 26, 197, 62, 94, 252, 219, 203, 117, 35, 11, 32,
                57, 177, 33, 88, 237, 149, 56, 87, 174, 20, 125, 136, 171, 168, 68, 175,
                74, 165, 71, 134, 139, 48, 27, 166, 77, 146, 158, 231, 83, 111, 229, 122,
                60, 211, 133, 230, 220, 105, 92, 41, 55, 46, 245, 40, 244, 102, 143, 54,
                65, 25, 63, 161, 1, 216, 80, 73, 209, 76, 132, 187, 208, 89, 18, 169,
                200, 196, 135, 130, 116, 188, 159, 86, 164, 100, 109, 198, 173, 186, 3, 64,
                52, 217, 226, 250, 124, 123, 5, 202, 38, 147, 118, 126, 255, 82, 85, 212,
                207, 206, 59, 227, 47, 16, 58, 17, 182, 189, 28, 42, 223, 183, 170, 213,
                119, 248, 152, 2, 44, 154, 163, 70, 221, 153, 101, 155, 167, 43, 172, 9,
                129, 22, 39, 253, 19, 98, 108, 110, 79, 113, 224, 232, 178, 185, 112, 104,
                218, 246, 97, 228, 251, 34, 242, 193, 238, 210, 144, 12, 191, 179, 162, 241,
                81, 51, 145, 235, 249, 14, 239, 107, 49, 192, 214, 31, 181, 199, 106, 157,
                184, 84, 204, 176, 115, 121, 50, 45, 127, 4, 150, 254, 138, 236, 205, 93,
                222, 114, 67, 29, 24, 72, 243, 141, 128, 195, 78, 66, 215, 61, 156, 180
            };
            for (int i = 0; i < 256; i++) {
                P[256 + i] = P[i] = permutation[i];
            }
        }

        public static double hashNoise(long x, long y, long z, long seed) {
            long h = seed ^ (x * 374761393L) ^ (y * 668265263L) ^ (z * 2147483647L);
            h = (h ^ (h >>> 13)) * 1274126177L;
            h = h ^ (h >>> 16);
            return (h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL;
        }

        public static double valueNoise1D(double x, long seed) {
            long xi = (long) Math.floor(x);
            double xf = x - xi;
            double u = xf * xf * (3.0 - 2.0 * xf);
            double a = hashNoise(xi, 0L, 0L, seed);
            double b = hashNoise(xi + 1L, 0L, 0L, seed);
            return a + u * (b - a);
        }

        public static double clamp01(double t) {
            if (Double.isNaN(t) || t <= 0.0) {
                return 0.0;
            }
            return Math.min(1.0, t);
        }

        public static double fade(double t) {
            double u = clamp01(t);
            return u * u * u * (u * (u * 6.0 - 15.0) + 10.0);
        }

        public static double lerp(double t, double a, double b) {
            double u = clamp01(t);
            return a + (b - a) * u;
        }

        public static double grad(int hash, double x, double y, double z) {
            int h = hash & 15;
            double u = h < 8 ? x : y;
            double v;
            if (h < 4) {
                v = y;
            } else if (h == 12 || h == 14) {
                v = x;
            } else {
                v = z;
            }
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }

        public static double perlin(double x, double y, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                return 0.0;
            }
            double fx = Math.floor(x);
            double fy = Math.floor(y);
            double fz = Math.floor(z);
            int ix = ((int) fx) & 255;
            int iy = ((int) fy) & 255;
            int iz = ((int) fz) & 255;
            double rx = x - fx;
            double ry = y - fy;
            double rz = z - fz;
            double u = fade(rx);
            double v = fade(ry);
            double w = fade(rz);
            int a = P[ix] + iy;
            int aa = P[a] + iz;
            int ab = P[a + 1] + iz;
            int b = P[ix + 1] + iy;
            int ba = P[b] + iz;
            int bb = P[b + 1] + iz;
            return lerp(
                    w,
                    lerp(
                            v,
                            lerp(u, grad(P[aa], rx, ry, rz), grad(P[ba], rx - 1.0, ry, rz)),
                            lerp(u, grad(P[ab], rx, ry - 1.0, rz), grad(P[bb], rx - 1.0, ry - 1.0, rz))),
                    lerp(
                            v,
                            lerp(u, grad(P[aa + 1], rx, ry, rz - 1.0), grad(P[ba + 1], rx - 1.0, ry, rz - 1.0)),
                            lerp(u, grad(P[ab + 1], rx, ry - 1.0, rz - 1.0), grad(P[bb + 1], rx - 1.0, ry - 1.0, rz - 1.0))));
        }

        public static long columnMixReference(long x, long z, long seed) {
            if (x == 0L && z == 0L) {
                return seed;
            }
            long mixed = (x * 374761393L) + (z * 668265263L) + seed;
            mixed = (mixed ^ (mixed >>> 13)) * 1274126177L;
            return mixed ^ (mixed >>> 16);
        }

        public static long columnMixSyntacticVariant(long colX, long colZ, long worldSeed) {
            long result;
            if (colX == 0L && colZ == 0L) {
                result = worldSeed;
            } else {
                long termZ = 668265263L * colZ;
                long termX = colX * 374761393L;
                long acc = (termX + termZ) + worldSeed;
                long step1 = 1274126177L * ((acc >>> 13) ^ acc);
                result = (step1 >>> 16) ^ step1;
            }
            return result;
        }

        public static long columnMixSignedShiftBug(long x, long z, long seed) {
            if (x == 0L && z == 0L) {
                return seed;
            }
            long mixed = (x * 374761393L) + (z * 668265263L) + seed;
            // Bug: signed shift >> 13 instead of unsigned shift >>> 13
            mixed = (mixed ^ (mixed >> 13)) * 1274126177L;
            return mixed ^ (mixed >>> 16);
        }
    }

    private static byte[] loadClassBytes(Class<?> clazz) throws IOException {
        String resourcePath = "/" + clazz.getName().replace('.', '/') + ".class";
        try (InputStream in = clazz.getResourceAsStream(resourcePath)) {
            Objects.requireNonNull(in, "Missing class resource: " + resourcePath);
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("Lifts hashNoise and valueNoise1D bytecode, inlines helper calls, and matches Java bit-for-bit")
    void liftsAndOptimizesValueNoiseWithExactParity() throws IOException {
        byte[] classBytes = loadClassBytes(SampleProceduralNoise.class);

        TranspiledArtifact openclFp64 = ConceptBackendEmitter.transpile(
                List.of(classBytes),
                SampleProceduralNoise.class.getName(),
                "valueNoise1D",
                OpenClBackendEmitter.BACKEND_ID,
                EmitOptions.gpuFp64());

        assertEquals(".cl", openclFp64.fileExtension());
        assertEquals("evaluate_valueNoise1D_grid3d", openclFp64.kernel3DName());

        // Verify all helper calls were inlined into valueNoise1D by ConceptOptimizerPass
        assertTrue(openclFp64.optimizedModule().helperFunctions().isEmpty(),
                "Expected hashNoise helper to be fully inlined into valueNoise1D");

        String clSource = openclFp64.sourceCode();
        assertTrue(clSource.contains("#pragma OPENCL EXTENSION cl_khr_fp64 : enable"));
        assertTrue(clSource.contains("inline double valueNoise1D("));
        assertTrue(clSource.contains("__kernel void evaluate_valueNoise1D_grid3d("));
        assertTrue(clSource.contains("((long)(((ulong)("), "Expected unsigned shift >>> lowered to ulong >>");
        assertTrue(clSource.contains("get_global_id(0)"));

        // Verify numerical parity across positive, negative, and fractional coordinates
        double[] coords = {-19.75, -1.25, 0.0, 0.375, 4.8125, 128.625};
        long[] seeds = {0L, 42L, -987654321012345L, 0xCAFEBABE12345678L};
        for (long seed : seeds) {
            for (double x : coords) {
                double expected = SampleProceduralNoise.valueNoise1D(x, seed);
                double actual = (Double) ConceptIrEvaluator.evaluateEntry(
                        openclFp64.optimizedModule(), x, seed);
                assertEquals(expected, actual, 1e-12,
                        "Parity mismatch at x=" + x + ", seed=" + seed);
            }
        }
    }

    @Test
    @DisplayName("Lifts 3D Perlin noise with <clinit> 512-entry LUT, ternary gradients, and inlined curve helpers")
    void liftsAndOptimizes3DPerlinNoiseWithClinitLutAndParity() throws IOException {
        byte[] classBytes = loadClassBytes(SampleProceduralNoise.class);

        TranspiledArtifact openclArtifact = ConceptBackendEmitter.transpile(
                List.of(classBytes),
                SampleProceduralNoise.class.getName(),
                "perlin",
                OpenClBackendEmitter.BACKEND_ID,
                EmitOptions.gpuFp64());

        ConceptModule optModule = openclArtifact.optimizedModule();
        assertEquals(1, optModule.lookupTables().size(), "Expected <clinit> to extract P[512] LUT");
        ConceptLut lut = optModule.lookupTables().get(0);
        assertEquals(512, lut.values().size());
        assertEquals(151, lut.values().get(0).intValue());
        assertEquals(180, lut.values().get(255).intValue());
        assertEquals(151, lut.values().get(256).intValue());

        assertTrue(optModule.helperFunctions().isEmpty(),
                "Expected fade, lerp, grad, and clamp01 to be fully inlined into perlin");

        String clCode = openclArtifact.sourceCode();
        assertTrue(clCode.contains("__constant uchar " + lut.symbolName() + "[512]"));
        assertTrue(clCode.contains("__kernel void evaluate_perlin_grid3d("));

        // Verify numerical parity against Java execution on 3D sample lattice and non-finite edge cases
        double[][] testPoints = {
            {0.0, 0.0, 0.0},
            {1.35, 2.75, -0.65},
            {-14.2, 8.9, 42.125},
            {0.5, 0.5, 0.5},
            {123.456, -78.901, 0.003},
            {Double.NaN, 1.0, 2.0},
            {1.0, Double.POSITIVE_INFINITY, 2.0}
        };
        for (double[] pt : testPoints) {
            double expected = SampleProceduralNoise.perlin(pt[0], pt[1], pt[2]);
            double actual = (Double) ConceptIrEvaluator.evaluateEntry(
                    optModule, pt[0], pt[1], pt[2]);
            assertEquals(expected, actual, 1e-12,
                    "3D Perlin parity mismatch at (" + pt[0] + ", " + pt[1] + ", " + pt[2] + ")");
        }
    }

    @Test
    @DisplayName("Supports FP32 consumer GPU downcast mode and pluggable Rust + custom backend emitters")
    void supportsFp32DowncastAndTriviallyExtensibleBackendSpi() throws Exception {
        byte[] classBytes = loadClassBytes(SampleProceduralNoise.class);

        // 1. OpenCL FP32 downcast mode
        TranspiledArtifact openclFp32 = ConceptBackendEmitter.transpile(
                List.of(classBytes),
                SampleProceduralNoise.class.getName(),
                "perlin",
                OpenClBackendEmitter.BACKEND_ID,
                EmitOptions.gpuFp32());
        assertFalse(openclFp32.sourceCode().contains("cl_khr_fp64"));
        assertTrue(openclFp32.sourceCode().contains("inline float perlin(float x, float y, float z)"));
        assertTrue(openclFp32.sourceCode().contains("__global float* out_buffer"));

        // 2. Rust backend emitter via the same ConceptBackendEmitter SPI (both FP64 and INT64 + FP32)
        TranspiledArtifact rustArtifact = ConceptBackendEmitter.transpile(
                List.of(classBytes),
                SampleProceduralNoise.class.getName(),
                "valueNoise1D",
                RustBackendEmitter.BACKEND_ID,
                EmitOptions.gpuFp64());
        assertEquals(".rs", rustArtifact.fileExtension());
        assertTrue(rustArtifact.sourceCode().contains("pub fn valueNoise1D(x: f64, seed: i64) -> f64"));
        assertTrue(rustArtifact.sourceCode().contains("pub fn evaluate_valueNoise1D_grid3d("));
        assertTrue(rustArtifact.sourceCode().contains("as u64) >> (("));
        assertTrue(rustArtifact.sourceCode().contains("& 63u32"));
        assertTrue(rustArtifact.sourceCode().contains(".wrapping_mul("));
        assertTrue(rustArtifact.sourceCode().contains(".wrapping_add("));

        TranspiledArtifact rustFp32Int64 = ConceptBackendEmitter.transpile(
                List.of(classBytes),
                SampleProceduralNoise.class.getName(),
                "valueNoise1D",
                RustBackendEmitter.BACKEND_ID,
                EmitOptions.gpuFp32());
        assertTrue(rustFp32Int64.sourceCode().contains("pub fn valueNoise1D(x: f32, seed: i64) -> f32"));
        assertTrue(rustFp32Int64.sourceCode().contains(".wrapping_mul("));

        // If rustc is available, compile & run in debug mode (overflow-checks enabled) to verify 0 panics & parity
        Path rustcBin = null;
        for (String candidate : List.of(
                System.getProperty("user.home", "") + "/.cargo/bin/rustc",
                "/usr/bin/rustc")) {
            Path p = Path.of(candidate);
            if (Files.isExecutable(p)) {
                rustcBin = p;
                break;
            }
        }
        if (rustcBin != null) {
            Path tempDir = Files.createTempDirectory("rust-emitter-verify");
            try {
                double expectedFp64 = SampleProceduralNoise.valueNoise1D(-19.75, 0xCAFEBABE12345678L);
                String harness = rustArtifact.sourceCode()
                        + "\nfn main() {\n"
                        + "    let v = valueNoise1D(-19.75f64, 0xCAFEBABE12345678u64 as i64);\n"
                        + "    let exp = " + expectedFp64 + "f64;\n"
                        + "    assert!((v - exp).abs() < 1e-12, \"mismatch: {} vs {}\", v, exp);\n"
                        + "}\n";
                Path rsFile = tempDir.resolve("verify_fp64.rs");
                Path binFile = tempDir.resolve("verify_fp64_bin");
                Files.writeString(rsFile, harness);
                ProcessBuilder pb = new ProcessBuilder(
                        rustcBin.toString(), rsFile.toString(), "-o", binFile.toString());
                pb.redirectErrorStream(true);
                Process compileProc = pb.start();
                String compileOut = new String(compileProc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(0, compileProc.waitFor(), "rustc debug compilation of FP64 kernel failed:\n" + compileOut);
                Process runProc = new ProcessBuilder(binFile.toString()).redirectErrorStream(true).start();
                String runOut = new String(runProc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(0, runProc.waitFor(), "Compiled Rust FP64 kernel panicked or failed parity in debug mode:\n" + runOut);

                String harnessFp32 = rustFp32Int64.sourceCode()
                        + "\nfn main() {\n"
                        + "    let v = valueNoise1D(-19.75f32, 0xCAFEBABE12345678u64 as i64);\n"
                        + "    let exp = " + ((float) expectedFp64) + "f32;\n"
                        + "    assert!((v - exp).abs() < 1e-5, \"fp32 mismatch: {} vs {}\", v, exp);\n"
                        + "}\n";
                Path rsFile32 = tempDir.resolve("verify_fp32.rs");
                Path binFile32 = tempDir.resolve("verify_fp32_bin");
                Files.writeString(rsFile32, harnessFp32);
                ProcessBuilder pb32 = new ProcessBuilder(
                        rustcBin.toString(), rsFile32.toString(), "-o", binFile32.toString());
                pb32.redirectErrorStream(true);
                Process compileProc32 = pb32.start();
                String compileOut32 = new String(compileProc32.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(0, compileProc32.waitFor(), "rustc debug compilation of INT64+FP32 kernel failed:\n" + compileOut32);
                Process runProc32 = new ProcessBuilder(binFile32.toString()).redirectErrorStream(true).start();
                String runOut32 = new String(runProc32.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(0, runProc32.waitFor(),
                        "Compiled Rust INT64+FP32 kernel panicked or failed parity in debug mode:\n" + runOut32);
            } finally {
                try (var stream = Files.walk(tempDir)) {
                    stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        }

        // 3. Registering a new custom language backend (e.g. WGSL stub) takes a single interface implementation
        ConceptBackendEmitter wgslStub = new ConceptBackendEmitter() {
            @Override
            public String backendId() {
                return "wgsl-poc";
            }

            @Override
            public String fileExtension() {
                return ".wgsl";
            }

            @Override
            public TranspiledArtifact emit(ConceptModule module, EmitOptions options) {
                ConceptFunction fn = module.entryFunction();
                String wgsl = "@compute @workgroup_size(4, 4, 4)\nfn "
                        + module.kernel3D().kernelName()
                        + "(@builtin(global_invocation_id) gid: vec3<u32>) { /* lowered "
                        + fn.name() + " with " + module.lookupTables().size() + " LUTs */ }\n";
                return new TranspiledArtifact(
                        backendId(),
                        fileExtension(),
                        module.moduleName(),
                        fn.name(),
                        module.kernel3D().kernelName(),
                        options.fpPrecision(),
                        wgsl,
                        module);
            }
        };
        ConceptBackendEmitter.registerBackend(wgslStub);
        assertTrue(ConceptBackendEmitter.registeredBackends().contains("wgsl-poc"));

        TranspiledArtifact wgslArtifact = ConceptBackendEmitter.transpile(
                List.of(classBytes),
                SampleProceduralNoise.class.getName(),
                "perlin",
                "wgsl-poc",
                EmitOptions.gpuFp32());
        assertEquals(".wgsl", wgslArtifact.fileExtension());
        assertTrue(wgslArtifact.sourceCode().contains("@compute @workgroup_size(4, 4, 4)"));
    }
    @DisplayName("Lifts real compiled ProceduralGenerator and PerlinNoise class files when present on disk")
    void liftsRealProjectClassesWhenCompiledOnDisk() throws IOException {
        Path proceduralClassPath = Path.of(
                "../../resource/LeafTreeGen/treegen-common/build/classes/java/main/net/leaf/treegen/common/ProceduralGenerator.class");
        Path perlinClassPath = Path.of(
                "../predict-api/build/classes/java/main/io/github/dailystruggle/rtp/common/predict/PerlinNoise.class");
        Path unitCurveClassPath = Path.of(
                "../predict-api/build/classes/java/main/io/github/dailystruggle/rtp/common/predict/UnitCurve.class");

        if (Files.isRegularFile(proceduralClassPath)
                && Files.isRegularFile(perlinClassPath)
                && Files.isRegularFile(unitCurveClassPath)) {
            List<byte[]> classes = List.of(
                    Files.readAllBytes(proceduralClassPath),
                    Files.readAllBytes(perlinClassPath),
                    Files.readAllBytes(unitCurveClassPath));
            TranspiledArtifact artifact = ConceptBackendEmitter.transpile(
                    classes,
                    "net.leaf.treegen.common.ProceduralGenerator",
                    "perlin",
                    OpenClBackendEmitter.BACKEND_ID,
                    EmitOptions.gpuFp64());
            assertNotNull(artifact);
            assertTrue(artifact.sourceCode().contains("__constant int PerlinNoise_P[512]"));
            assertTrue(artifact.sourceCode().contains("__kernel void evaluate_perlin_grid3d"));
        }
    }

    @Test
    @DisplayName("Proves canonical DAG equivalence across syntactic variants, catches signed-shift bugs via CEGIS, and emits Java/Jminor")
    void provesCanonicalEquivalenceFindsCegisCounterexampleAndEmitsJavaJminor() throws IOException {
        byte[] classBytes = loadClassBytes(SampleProceduralNoise.class);

        ConceptModule refMod = BytecodeSsaLifter.liftModule(
                List.of(classBytes),
                SampleProceduralNoise.class.getName(),
                "columnMixReference");
        ConceptModule variantMod = BytecodeSsaLifter.liftModule(
                List.of(classBytes),
                SampleProceduralNoise.class.getName(),
                "columnMixSyntacticVariant");
        ConceptModule buggyMod = BytecodeSsaLifter.liftModule(
                List.of(classBytes),
                SampleProceduralNoise.class.getName(),
                "columnMixSignedShiftBug");

        // 1. Syntactically distinct implementations (early return vs if/else + renamed locals + commuted ops)
        // collapse to the exact same canonical hash and pass CEGIS verification.
        BytecodeConceptIr.EquivalenceReport eqReport = BytecodeConceptIr.verifyEquivalence(refMod, variantMod);
        assertTrue(eqReport.equivalent(), "Expected syntactic variant to be functionally equivalent");
        assertTrue(eqReport.structurallyIdentical(),
                "Expected canonical SSA + commutative normalization to yield identical DAG hashes");
        assertEquals(eqReport.referenceCanonicalHash(), eqReport.candidateCanonicalHash());
        assertTrue(eqReport.vectorsTested() >= 30);
        assertTrue(eqReport.toCegisFeedbackPrompt().startsWith("CEGIS_EQUIVALENT("));

        // 2. Signed-shift bug (>> 13 instead of >>> 13) is caught immediately with a concrete counterexample
        BytecodeConceptIr.EquivalenceReport bugReport = BytecodeConceptIr.verifyEquivalence(refMod, buggyMod);
        assertFalse(bugReport.equivalent(), "Expected signed-shift bug to fail equivalence");
        assertFalse(bugReport.structurallyIdentical());
        assertNotNull(bugReport.counterexample());
        assertFalse(bugReport.counterexample().inputArguments().isEmpty());
        assertTrue(bugReport.toCegisFeedbackPrompt().startsWith("CEGIS_COUNTEREXAMPLE: inputs="));

        // 3. Java 17 & Jminor v2 backend emitters registered on ConceptBackendEmitter
        TranspiledArtifact javaArtifact = ConceptBackendEmitter.transpile(
                List.of(classBytes),
                SampleProceduralNoise.class.getName(),
                "columnMixSyntacticVariant",
                JavaConceptBackendEmitter.BACKEND_ID_JAVA,
                EmitOptions.gpuFp64());
        assertEquals(".java", javaArtifact.fileExtension());
        assertTrue(javaArtifact.sourceCode().contains("public static long columnMixSyntacticVariant("));
        assertTrue(javaArtifact.sourceCode().contains(">>> 13"));
        assertTrue(javaArtifact.sourceCode().contains("canonical_hash=" + eqReport.candidateCanonicalHash()));

        TranspiledArtifact jminorArtifact = ConceptBackendEmitter.transpile(
                List.of(classBytes),
                SampleProceduralNoise.class.getName(),
                "columnMixSyntacticVariant",
                JavaConceptBackendEmitter.BACKEND_ID_JMINOR,
                EmitOptions.gpuFp64());
        assertEquals(".jminor", jminorArtifact.fileExtension());
        assertTrue(jminorArtifact.sourceCode().contains("@effects(pure=true"));
        assertTrue(jminorArtifact.sourceCode().contains("canonical_hash=\"" + eqReport.candidateCanonicalHash() + "\""));
        assertTrue(jminorArtifact.sourceCode().contains("public static fn columnMixSyntacticVariant(long colX, long colZ, long worldSeed) -> long"));
    }

    public static final class SampleColumnGradientLoop {
        public static double evalColumn(double wx, double wy, double wz) {
            double acc = 0.0;
            int cy = 0;
            while (cy < 49) {
                double offset2d = (wx * 0.015625) + (wz * 0.03125) - 0.50375;
                double factor2d = (wx * 0.0078125) - (wz * 0.00390625) + 1.25;
                double yGrad = 1.5 - (cy * 0.0625) + (wy * 0.001);
                double u = (yGrad + offset2d) * factor2d;
                acc += u;
                cy++;
            }
            return acc;
        }
    }

    @Test
    @DisplayName("Hoists 2D (X, Z) Y-invariant expressions out of vertical Y loops in both OpenCL and Rust backends")
    void hoists2dColumnInvariantsOutOfVerticalYLoopInOpenClAndRust() throws Exception {
        byte[] classBytes = loadClassBytes(SampleColumnGradientLoop.class);

        TranspiledArtifact opencl = ConceptBackendEmitter.transpile(
                List.of(classBytes),
                SampleColumnGradientLoop.class.getName(),
                "evalColumn",
                OpenClBackendEmitter.BACKEND_ID,
                EmitOptions.gpuFp64());
        TranspiledArtifact rust = ConceptBackendEmitter.transpile(
                List.of(classBytes),
                SampleColumnGradientLoop.class.getName(),
                "evalColumn",
                RustBackendEmitter.BACKEND_ID,
                EmitOptions.gpuFp64());

        String clSrc = opencl.sourceCode();
        String rsSrc = rust.sourceCode();
        int clWhileIdx = clSrc.indexOf("while (");
        int clOffsetIdx = clSrc.indexOf("0.50375");
        assertTrue(clOffsetIdx > 0 && clWhileIdx > clOffsetIdx && clSrc.lastIndexOf("0.50375") < clWhileIdx,
                "Expected 2D offset2d expression to be hoisted before while loop in OpenCL");

        int rsWhileIdx = rsSrc.indexOf("while ");
        int rsOffsetIdx = rsSrc.indexOf("0.50375");
        assertTrue(rsOffsetIdx > 0 && rsWhileIdx > rsOffsetIdx && rsSrc.lastIndexOf("0.50375") < rsWhileIdx,
                "Expected 2D offset2d expression to be hoisted before while loop in Rust");

        double expected = SampleColumnGradientLoop.evalColumn(16.0, 64.0, -32.0);
        double actual = (Double) ConceptIrEvaluator.evaluateEntry(
                opencl.optimizedModule(), 16.0, 64.0, -32.0);
        assertEquals(expected, actual, 1e-11);
    }

    /**
     * Synthetic multi-octave Perlin/Blended-style noise fixture with duplicate constant LUTs
     * ({@code PERM_A}/{@code PERM_B} and {@code OCTAVE_OFFSET_X}/{@code OCTAVE_OFFSET_X_DUP}),
     * large-coordinate wrapping ({@code 33554432.0}), {@code Math.floor}, fractional extraction,
     * and min/max/selector octave blending.
     */
    public static final class SampleBlendedMultiOctaveNoise {

        private static final int[] PERM_A = new int[512];
        private static final int[] PERM_B = new int[512];
        private static final double[] OCTAVE_FREQS = {0.0625, 0.125, 0.25, 1.0};
        private static final double[] OCTAVE_AMPS = {0.5, 0.25, 0.125, 0.0625};
        private static final double[] OCTAVE_OFFSET_X = {17.3125, -41.625, 103.1875, -219.4375};
        private static final double[] OCTAVE_OFFSET_X_DUP = {17.3125, -41.625, 103.1875, -219.4375};
        private static final double[] OCTAVE_OFFSET_Y = {-9.75, 23.125, -55.875, 81.25};
        private static final double[] OCTAVE_OFFSET_Z = {31.5, -13.25, 67.75, -142.375};

        static {
            int[] base = {
                151, 160, 137, 91, 90, 15, 131, 13, 201, 95, 96, 53, 194, 233, 7, 225,
                140, 36, 103, 30, 69, 142, 8, 99, 37, 240, 21, 10, 23, 190, 6, 148,
                247, 120, 234, 75, 0, 26, 197, 62, 94, 252, 219, 203, 117, 35, 11, 32,
                57, 177, 33, 88, 237, 149, 56, 87, 174, 20, 125, 136, 171, 168, 68, 175,
                74, 165, 71, 134, 139, 48, 27, 166, 77, 146, 158, 231, 83, 111, 229, 122,
                60, 211, 133, 230, 220, 105, 92, 41, 55, 46, 245, 40, 244, 102, 143, 54,
                65, 25, 63, 161, 1, 216, 80, 73, 209, 76, 132, 187, 208, 89, 18, 169,
                200, 196, 135, 130, 116, 188, 159, 86, 164, 100, 109, 198, 173, 186, 3, 64,
                52, 217, 226, 250, 124, 123, 5, 202, 38, 147, 118, 126, 255, 82, 85, 212,
                207, 206, 59, 227, 47, 16, 58, 17, 182, 189, 28, 42, 223, 183, 170, 213,
                119, 248, 152, 2, 44, 154, 163, 70, 221, 153, 101, 155, 167, 43, 172, 9,
                129, 22, 39, 253, 19, 98, 108, 110, 79, 113, 224, 232, 178, 185, 112, 104,
                218, 246, 97, 228, 251, 34, 242, 193, 238, 210, 144, 12, 191, 179, 162, 241,
                81, 51, 145, 235, 249, 14, 239, 107, 49, 192, 214, 31, 181, 199, 106, 157,
                184, 84, 204, 176, 115, 121, 50, 45, 127, 4, 150, 254, 138, 236, 205, 93,
                222, 114, 67, 29, 24, 72, 243, 141, 128, 195, 78, 66, 215, 61, 156, 180
            };
            for (int i = 0; i < 256; i++) {
                PERM_A[i] = base[i];
                PERM_A[i + 256] = base[i];
                PERM_B[i] = base[i];
                PERM_B[i + 256] = base[i];
            }
        }

        private static double wrapCoord(double v) {
            return v - Math.floor(v / 33554432.0 + 0.5) * 33554432.0;
        }

        private static double fade(double t) {
            return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
        }

        private static double lerp(double t, double a, double b) {
            return a + t * (b - a);
        }

        private static double grad(int hash, double x, double y, double z) {
            int h = hash & 15;
            double u = h < 8 ? x : y;
            double v = h < 4 ? y : ((h == 12 || h == 14) ? x : z);
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }

        private static double sampleOctaveA(double sx, double sy, double sz) {
            double wx = wrapCoord(sx);
            double wy = wrapCoord(sy);
            double wz = wrapCoord(sz);
            double gx = Math.floor(wx);
            double gy = Math.floor(wy);
            double gz = Math.floor(wz);
            int ix = ((int) gx) & 255;
            int iy = ((int) gy) & 255;
            int iz = ((int) gz) & 255;
            double rx = wx - gx;
            double ry = wy - gy;
            double rz = wz - gz;
            double u = fade(rx);
            double v = fade(ry);
            double w = fade(rz);
            int a = PERM_A[ix] + iy;
            int aa = PERM_A[a] + iz;
            int ab = PERM_A[a + 1] + iz;
            int b = PERM_A[ix + 1] + iy;
            int ba = PERM_A[b] + iz;
            int bb = PERM_A[b + 1] + iz;
            return lerp(
                    w,
                    lerp(
                            v,
                            lerp(u, grad(PERM_A[aa], rx, ry, rz), grad(PERM_A[ba], rx - 1.0, ry, rz)),
                            lerp(u, grad(PERM_A[ab], rx, ry - 1.0, rz), grad(PERM_A[bb], rx - 1.0, ry - 1.0, rz))),
                    lerp(
                            v,
                            lerp(u, grad(PERM_A[aa + 1], rx, ry, rz - 1.0), grad(PERM_A[ba + 1], rx - 1.0, ry, rz - 1.0)),
                            lerp(u, grad(PERM_A[ab + 1], rx, ry - 1.0, rz - 1.0), grad(PERM_A[bb + 1], rx - 1.0, ry - 1.0, rz - 1.0))));
        }

        private static double sampleOctaveB(double sx, double sy, double sz) {
            double wx = wrapCoord(sx);
            double wy = wrapCoord(sy);
            double wz = wrapCoord(sz);
            double gx = Math.floor(wx);
            double gy = Math.floor(wy);
            double gz = Math.floor(wz);
            int ix = ((int) gx) & 255;
            int iy = ((int) gy) & 255;
            int iz = ((int) gz) & 255;
            double rx = wx - gx;
            double ry = wy - gy;
            double rz = wz - gz;
            double u = fade(rx);
            double v = fade(ry);
            double w = fade(rz);
            int a = PERM_B[ix] + iy;
            int aa = PERM_B[a] + iz;
            int ab = PERM_B[a + 1] + iz;
            int b = PERM_B[ix + 1] + iy;
            int ba = PERM_B[b] + iz;
            int bb = PERM_B[b + 1] + iz;
            return lerp(
                    w,
                    lerp(
                            v,
                            lerp(u, grad(PERM_B[aa], rx, ry, rz), grad(PERM_B[ba], rx - 1.0, ry, rz)),
                            lerp(u, grad(PERM_B[ab], rx, ry - 1.0, rz), grad(PERM_B[bb], rx - 1.0, ry - 1.0, rz))),
                    lerp(
                            v,
                            lerp(u, grad(PERM_B[aa + 1], rx, ry, rz - 1.0), grad(PERM_B[ba + 1], rx - 1.0, ry, rz - 1.0)),
                            lerp(u, grad(PERM_B[ab + 1], rx, ry - 1.0, rz - 1.0), grad(PERM_B[bb + 1], rx - 1.0, ry - 1.0, rz - 1.0))));
        }

        public static double sampleBlended(double x, double y, double z) {
            double minSum = 0.0;
            double maxSum = 0.0;
            double selector = 0.0;
            int oct = 0;
            while (oct < 4) {
                double freq = OCTAVE_FREQS[oct];
                double amp = OCTAVE_AMPS[oct];
                double ox = OCTAVE_OFFSET_X[oct];
                double oxDup = OCTAVE_OFFSET_X_DUP[oct];
                double oy = OCTAVE_OFFSET_Y[oct];
                double oz = OCTAVE_OFFSET_Z[oct];
                minSum += sampleOctaveA(x * freq + ox, y * freq + oy, z * freq + oz) * amp;
                maxSum += sampleOctaveB(x * freq + oxDup + 19.5, y * freq + oy - 11.25, z * freq + oz + 7.75) * amp;
                selector += sampleOctaveA(x * (freq * 0.5) + ox, y * (freq * 0.5) + oy, z * (freq * 0.5) + oz) * amp;
                oct++;
            }
            double t = selector * 0.5 + 0.5;
            if (t <= 0.0) {
                return minSum;
            }
            if (t >= 1.0) {
                return maxSum;
            }
            return lerp(t, minSum, maxSum);
        }
    }

    @Test
    @DisplayName("Lowers shared multi-octave Perlin/Blended ConceptModule across FP64, FP32, and INT64_Q32_FP32 with deduplicated LUTs and parity across R=0..16,777,216")
    void lowersSharedBlendedNoiseAcrossAllThreeArithmeticModesWithParityUpTo16M() throws Exception {
        byte[] classBytes = loadClassBytes(SampleBlendedMultiOctaveNoise.class);
        ConceptModule lifted = BytecodeSsaLifter.liftModule(
                List.of(classBytes),
                SampleBlendedMultiOctaveNoise.class.getName(),
                "sampleBlended");
        ConceptModule sharedModule = ConceptOptimizerPass.optimize(lifted);

        // 1. Verify automatic deduplication collapsed PERM_B -> PERM_A and OCTAVE_OFFSET_X_DUP -> OCTAVE_OFFSET_X
        assertEquals(6, sharedModule.lookupTables().size(),
                "Expected 8 raw LUTs to deduplicate into 6 unique LUTs (PERM_A, FREQS, AMPS, OFFSET_X, OFFSET_Y, OFFSET_Z)");
        assertTrue(sharedModule.lookupTables().stream().noneMatch(l -> l.symbolName().contains("PERM_B")),
                "Duplicate PERM_B LUT should be deduplicated");
        assertTrue(sharedModule.lookupTables().stream().noneMatch(l -> l.symbolName().contains("OCTAVE_OFFSET_X_DUP")),
                "Duplicate OCTAVE_OFFSET_X_DUP LUT should be deduplicated");

        OpenClBackendEmitter openClEmitter = new OpenClBackendEmitter();
        JavaConceptBackendEmitter javaEmitter = JavaConceptBackendEmitter.javaBackend();

        // Emit BOTH GPU OpenCL and CPU Java from the EXACT SAME ConceptModule in all 3 precision modes
        TranspiledArtifact clFp64 = openClEmitter.emit(sharedModule, EmitOptions.of(GpuExactArithmeticMode.NATIVE_FP64, BatchLayout.SINGLE_POINT));
        TranspiledArtifact clFp32 = openClEmitter.emit(sharedModule, EmitOptions.of(GpuExactArithmeticMode.DOWNCAST_FP32, BatchLayout.SINGLE_POINT));
        TranspiledArtifact clQ32 = openClEmitter.emit(sharedModule, EmitOptions.of(GpuExactArithmeticMode.INT64_Q32_FP32, BatchLayout.SINGLE_POINT));

        TranspiledArtifact javaFp64 = javaEmitter.emit(sharedModule, EmitOptions.of(GpuExactArithmeticMode.NATIVE_FP64, BatchLayout.SINGLE_POINT));
        TranspiledArtifact javaFp32 = javaEmitter.emit(sharedModule, EmitOptions.of(GpuExactArithmeticMode.DOWNCAST_FP32, BatchLayout.SINGLE_POINT));
        TranspiledArtifact javaQ32 = javaEmitter.emit(sharedModule, EmitOptions.of(GpuExactArithmeticMode.INT64_Q32_FP32, BatchLayout.SINGLE_POINT));

        // 2. Verify OpenCL precision isolation & deduplicated __constant LUT packing
        assertTrue(clFp64.sourceCode().contains("#pragma OPENCL EXTENSION cl_khr_fp64 : enable"));
        assertTrue(clFp64.sourceCode().contains("inline double sampleBlended(double x, double y, double z)"));
        assertFalse(clFp64.sourceCode().contains("PERM_B"));
        assertFalse(clFp64.sourceCode().contains("OCTAVE_OFFSET_X_DUP"));

        assertFalse(clFp32.sourceCode().contains("cl_khr_fp64"), "DOWNCAST_FP32 must not enable cl_khr_fp64");
        assertFalse(Pattern.compile("\\bdouble\\b").matcher(clFp32.sourceCode()).find(),
                "DOWNCAST_FP32 must not emit any double types");
        assertTrue(clFp32.sourceCode().contains("inline float sampleBlended(float x, float y, float z)"));

        assertFalse(clQ32.sourceCode().contains("cl_khr_fp64"), "INT64_Q32_FP32 must not enable cl_khr_fp64");
        assertFalse(Pattern.compile("\\bdouble\\b").matcher(clQ32.sourceCode()).find(),
                "INT64_Q32_FP32 must not emit any double types");
        assertTrue(clQ32.sourceCode().contains("inline float sampleBlended(long x, long y, long z)"),
                "INT64_Q32_FP32 should lower large-coordinate inputs to Q32.32 long and return FP32 float");
        assertTrue(clQ32.sourceCode().contains("q32_mul(") && clQ32.sourceCode().contains("q32_floor(")
                && clQ32.sourceCode().contains("q32_to_f32("));
        assertTrue(clQ32.sourceCode().contains("__constant long ") && clQ32.sourceCode().contains("_q32[4]"),
                "INT64_Q32_FP32 should emit deduplicated Q32.32 __constant long LUTs for coordinate tables");
        assertTrue(clQ32.sourceCode().contains("__constant float ") && clQ32.sourceCode().contains("OCTAVE_AMPS[4]"),
                "INT64_Q32_FP32 should keep amplitude LUT in FP32");

        // 3. Test coordinates across R = 0 .. 16,777,216 blocks (including sub-block fractional & negative)
        double[][] testCoords = {
            {0.0, 0.0, 0.0},
            {0.375, 64.125, -0.625},
            {17.25, 72.5, -19.75},
            {256.125, 48.75, 512.875},
            {4096.625, 96.25, -4096.375},
            {65536.375, 32.5, -65536.625},
            {1_048_576.25, 80.125, -1_048_576.75},
            {4_194_304.625, 64.375, 4_194_304.125},
            {8_388_608.375, 40.25, -8_388_608.625},
            {16_777_200.25, 70.5, -16_777_200.75},
            {16_777_215.375, 64.25, 16_777_215.625},
            {16_777_216.0, 64.0, -16_777_216.0},
            {16_777_216.375, 64.125, -16_777_216.625},
            {-16_777_216.0, 64.0, 16_777_216.0}
        };

        double[] expectedRef = new double[testCoords.length];
        for (int i = 0; i < testCoords.length; i++) {
            expectedRef[i] = SampleBlendedMultiOctaveNoise.sampleBlended(
                    testCoords[i][0], testCoords[i][1], testCoords[i][2]);
            double irEval = (Double) ConceptIrEvaluator.evaluateEntry(
                    sharedModule, testCoords[i][0], testCoords[i][1], testCoords[i][2]);
            assertEquals(expectedRef[i], irEval, 0.0, "ConceptIrEvaluator mismatch at index " + i);
        }

        // 4. Compile & run emitted Java 17 in all 3 precision modes
        double[] javaFp64Vals = new double[testCoords.length];
        float[] javaFp32Vals = new float[testCoords.length];
        float[] javaQ32Vals = new float[testCoords.length];
        Path tempDir = Files.createTempDirectory("concept-parity-test");
        try {
            Class<?> clsFp64 = compileAndLoadGeneratedJava(tempDir.resolve("java_fp64"), javaFp64.sourceCode());
            Class<?> clsFp32 = compileAndLoadGeneratedJava(tempDir.resolve("java_fp32"), javaFp32.sourceCode());
            Class<?> clsQ32 = compileAndLoadGeneratedJava(tempDir.resolve("java_q32"), javaQ32.sourceCode());

            Method mFp64 = clsFp64.getMethod("sampleBlended", double.class, double.class, double.class);
            Method mFp32 = clsFp32.getMethod("sampleBlended", float.class, float.class, float.class);
            Method mQ32 = clsQ32.getMethod("sampleBlended", long.class, long.class, long.class);

            double maxQ32Err = 0.0;
            double maxFp32NearErr = 0.0;
            double maxFp32FarErr = 0.0;
            for (int i = 0; i < testCoords.length; i++) {
                double x = testCoords[i][0];
                double y = testCoords[i][1];
                double z = testCoords[i][2];
                javaFp64Vals[i] = (Double) mFp64.invoke(null, x, y, z);
                javaFp32Vals[i] = (Float) mFp32.invoke(null, (float) x, (float) y, (float) z);
                javaQ32Vals[i] = (Float) mQ32.invoke(
                        null,
                        ConceptOptimizerPass.doubleToQ32(x),
                        ConceptOptimizerPass.doubleToQ32(y),
                        ConceptOptimizerPass.doubleToQ32(z));

                // NATIVE_FP64: exact 0.0 drift across R = 0..16,777,216
                assertEquals(expectedRef[i], javaFp64Vals[i], 0.0,
                        "Java NATIVE_FP64 drift at point " + i + " (" + x + ", " + y + ", " + z + ")");

                double q32Err = Math.abs(expectedRef[i] - javaQ32Vals[i]);
                maxQ32Err = Math.max(maxQ32Err, q32Err);
                assertTrue(q32Err < 1e-5,
                        "Java INT64_Q32_FP32 error too large at point " + i + " (R=" + Math.abs(x) + "): " + q32Err);

                double fp32Err = Math.abs(expectedRef[i] - javaFp32Vals[i]);
                if (Math.abs(x) <= 4096.625) {
                    maxFp32NearErr = Math.max(maxFp32NearErr, fp32Err);
                } else {
                    maxFp32FarErr = Math.max(maxFp32FarErr, fp32Err);
                }
                assertTrue(Double.isFinite(javaFp32Vals[i]) && fp32Err < 2.0,
                        "Java DOWNCAST_FP32 out of bounded envelope at point " + i + ": " + fp32Err);
            }
            assertTrue(maxFp32NearErr < 1e-3,
                    "Expected tight DOWNCAST_FP32 tolerance near origin, got " + maxFp32NearErr);
            assertTrue(maxQ32Err < 1e-5,
                    "Expected INT64_Q32_FP32 max error < 1e-5 across R=0..16,777,216, got " + maxQ32Err);
            assertTrue(maxQ32Err < maxFp32FarErr,
                    "Expected INT64_Q32_FP32 (" + maxQ32Err + ") to dramatically outperform DOWNCAST_FP32 ("
                            + maxFp32FarErr + ") at large coordinates up to R=16,777,216");

            // 5. Compile & execute emitted OpenCL C99 in all 3 modes via GCC C99 OpenCL shim (always when gcc present)
            if (Files.isExecutable(Path.of("/usr/bin/gcc"))) {
                double[] clFp64Vals = runOpenClViaGccShim(
                        tempDir.resolve("gcc_fp64"), clFp64.sourceCode(), GpuExactArithmeticMode.NATIVE_FP64, testCoords);
                double[] clFp32Vals = runOpenClViaGccShim(
                        tempDir.resolve("gcc_fp32"), clFp32.sourceCode(), GpuExactArithmeticMode.DOWNCAST_FP32, testCoords);
                double[] clQ32Vals = runOpenClViaGccShim(
                        tempDir.resolve("gcc_q32"), clQ32.sourceCode(), GpuExactArithmeticMode.INT64_Q32_FP32, testCoords);

                for (int i = 0; i < testCoords.length; i++) {
                    assertEquals(expectedRef[i], clFp64Vals[i], 0.0,
                            "OpenCL NATIVE_FP64 (C99) drift at point " + i);
                    assertEquals(javaQ32Vals[i], clQ32Vals[i], 1e-6,
                            "OpenCL INT64_Q32_FP32 (C99) mismatch vs Java Q32 at point " + i);
                    assertTrue(Math.abs(expectedRef[i] - clQ32Vals[i]) < 1e-5,
                            "OpenCL INT64_Q32_FP32 (C99) error vs FP64 ref at point " + i);
                    assertEquals(javaFp32Vals[i], clFp32Vals[i], 1e-5,
                            "OpenCL DOWNCAST_FP32 (C99) mismatch vs Java FP32 at point " + i);
                }
            }

            // 6. Real OpenCL GPU build + execution check via libOpenCL.so.1 when a GPU runtime is available
            if (isRealOpenClGpuAvailable()) {
                double[] gpuFp64Vals = runOpenClSinglePointOnGpu(
                        tempDir.resolve("gpu_fp64"), clFp64.sourceCode(), GpuExactArithmeticMode.NATIVE_FP64, testCoords);
                double[] gpuFp32Vals = runOpenClSinglePointOnGpu(
                        tempDir.resolve("gpu_fp32"), clFp32.sourceCode(), GpuExactArithmeticMode.DOWNCAST_FP32, testCoords);
                double[] gpuQ32Vals = runOpenClSinglePointOnGpu(
                        tempDir.resolve("gpu_q32"), clQ32.sourceCode(), GpuExactArithmeticMode.INT64_Q32_FP32, testCoords);

                for (int i = 0; i < testCoords.length; i++) {
                    assertEquals(expectedRef[i], gpuFp64Vals[i], 0.0,
                            "Real GPU OpenCL NATIVE_FP64 drift at point " + i);
                    assertEquals(javaQ32Vals[i], gpuQ32Vals[i], 1e-6,
                            "Real GPU OpenCL INT64_Q32_FP32 mismatch vs Java Q32 at point " + i);
                    assertTrue(Math.abs(expectedRef[i] - gpuQ32Vals[i]) < 1e-5,
                            "Real GPU OpenCL INT64_Q32_FP32 error vs FP64 ref at point " + i);
                    assertEquals(javaFp32Vals[i], gpuFp32Vals[i], 1e-5,
                            "Real GPU OpenCL DOWNCAST_FP32 mismatch vs Java FP32 at point " + i);
                }
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("Emits and executes all four BatchLayouts (SINGLE_POINT, CORNER_GRID_4X8X4, SECTION_4096_VOXEL, SECTION_4096_LCA_NIBBLE_PACKED) from the same ConceptModule")
    void emitsAndExecutesAllFourBatchLayoutsFromSharedConceptModule() throws Exception {
        byte[] classBytes = loadClassBytes(SampleBlendedMultiOctaveNoise.class);
        ConceptModule sharedModule = ConceptOptimizerPass.optimize(BytecodeSsaLifter.liftModule(
                List.of(classBytes),
                SampleBlendedMultiOctaveNoise.class.getName(),
                "sampleBlended"));

        OpenClBackendEmitter openClEmitter = new OpenClBackendEmitter();
        JavaConceptBackendEmitter javaEmitter = JavaConceptBackendEmitter.javaBackend();

        Path tempDir = Files.createTempDirectory("concept-batch-layouts-test");
        try {
            // 1. Verify OpenCL + Java emission for all 4 BatchLayouts in both NATIVE_FP64 and INT64_Q32_FP32
            for (GpuExactArithmeticMode mode : List.of(
                    GpuExactArithmeticMode.NATIVE_FP64,
                    GpuExactArithmeticMode.DOWNCAST_FP32,
                    GpuExactArithmeticMode.INT64_Q32_FP32)) {
                for (BatchLayout layout : BatchLayout.values()) {
                    EmitOptions opts = EmitOptions.of(mode, layout);
                    TranspiledArtifact clArt = openClEmitter.emit(sharedModule, opts);
                    TranspiledArtifact javaArt = javaEmitter.emit(sharedModule, opts);

                    assertNotNull(clArt.kernel3DName());
                    assertEquals(clArt.kernel3DName(), javaArt.kernel3DName(),
                            "CPU Java and GPU OpenCL must share identical batch kernel name for " + layout);
                    assertTrue(clArt.sourceCode().contains("__kernel void " + clArt.kernel3DName() + "("),
                            "Missing OpenCL kernel for " + layout);
                    assertTrue(javaArt.sourceCode().contains("public static void " + javaArt.kernel3DName() + "("),
                            "Missing Java batch method for " + layout);
                    if (isRealOpenClGpuAvailable()) {
                        verifyOpenClCompilesOnGpu(
                                tempDir.resolve("build_" + mode.name() + "_" + layout.name() + ".cl"),
                                clArt.sourceCode());
                    }
                }
            }

            // 2. Execute Java CORNER_GRID_4X8X4, SECTION_4096_VOXEL, and SECTION_4096_LCA_NIBBLE_PACKED at R=16,777,200
            int ox = 16_777_200;
            int oy = 64;
            int oz = -16_777_200;
            int[] origins = {ox, oy, oz, 0};

            // 2a. CORNER_GRID_4X8X4 in NATIVE_FP64 and INT64_Q32_FP32
            TranspiledArtifact cornerFp64 = javaEmitter.emit(
                    sharedModule, EmitOptions.of(GpuExactArithmeticMode.NATIVE_FP64, BatchLayout.CORNER_GRID_4X8X4));
            Class<?> cornerClsFp64 = compileAndLoadGeneratedJava(tempDir.resolve("corner_fp64"), cornerFp64.sourceCode());
            double[] cornersFp64 = new double[5 * 3 * 5];
            cornerClsFp64.getMethod(
                            "evaluate_sampleBlended_corner_grid_4x8x4",
                            double[].class, int[].class, int.class, int.class, int.class, int.class)
                    .invoke(null, cornersFp64, origins, 1, 5, 3, 5);

            TranspiledArtifact cornerQ32 = javaEmitter.emit(
                    sharedModule, EmitOptions.of(GpuExactArithmeticMode.INT64_Q32_FP32, BatchLayout.CORNER_GRID_4X8X4));
            Class<?> cornerClsQ32 = compileAndLoadGeneratedJava(tempDir.resolve("corner_q32"), cornerQ32.sourceCode());
            float[] cornersQ32 = new float[5 * 3 * 5];
            cornerClsQ32.getMethod(
                            "evaluate_sampleBlended_corner_grid_4x8x4",
                            float[].class, int[].class, int.class, int.class, int.class, int.class)
                    .invoke(null, cornersQ32, origins, 1, 5, 3, 5);

            for (int cy = 0; cy < 3; cy++) {
                for (int cz = 0; cz < 5; cz++) {
                    for (int cx = 0; cx < 5; cx++) {
                        int idx = (cy * 5 + cz) * 5 + cx;
                        double ref = SampleBlendedMultiOctaveNoise.sampleBlended(
                                ox + cx * 4, oy + cy * 8, oz + cz * 4);
                        assertEquals(ref, cornersFp64[idx], 0.0, "CORNER_GRID_4X8X4 FP64 drift at idx=" + idx);
                        assertEquals(ref, cornersQ32[idx], 1e-5, "CORNER_GRID_4X8X4 Q32 drift at idx=" + idx);
                    }
                }
            }

            // 2b. SECTION_4096_VOXEL in INT64_Q32_FP32
            TranspiledArtifact secQ32 = javaEmitter.emit(
                    sharedModule, EmitOptions.of(GpuExactArithmeticMode.INT64_Q32_FP32, BatchLayout.SECTION_4096_VOXEL));
            Class<?> secClsQ32 = compileAndLoadGeneratedJava(tempDir.resolve("sec_q32"), secQ32.sourceCode());
            float[] voxelsQ32 = new float[4096];
            secClsQ32.getMethod("evaluate_sampleBlended_section_4096", float[].class, int[].class, int.class)
                    .invoke(null, voxelsQ32, origins, 1);

            for (int vIdx : new int[] {0, 1, 15, 16, 255, 256, 1337, 2048, 4095}) {
                int lx = vIdx & 15;
                int lz = (vIdx >> 4) & 15;
                int ly = (vIdx >> 8) & 15;
                double ref = SampleBlendedMultiOctaveNoise.sampleBlended(ox + lx, oy + ly, oz + lz);
                assertEquals(ref, voxelsQ32[vIdx], 1e-5, "SECTION_4096_VOXEL Q32 drift at vIdx=" + vIdx);
            }

            // 2c. SECTION_4096_LCA_NIBBLE_PACKED in NATIVE_FP64
            TranspiledArtifact lcaFp64 = javaEmitter.emit(
                    sharedModule, EmitOptions.of(GpuExactArithmeticMode.NATIVE_FP64, BatchLayout.SECTION_4096_LCA_NIBBLE_PACKED));
            Class<?> lcaClsFp64 = compileAndLoadGeneratedJava(tempDir.resolve("lca_fp64"), lcaFp64.sourceCode());
            int[] packedFrames = new int[516];
            lcaClsFp64.getMethod("evaluate_lca_packed_batch3d", int[].class, int[].class, int.class)
                    .invoke(null, packedFrames, origins, 1);

            for (int vIdx = 0; vIdx < 4096; vIdx++) {
                int lx = vIdx & 15;
                int lz = (vIdx >> 4) & 15;
                int ly = (vIdx >> 8) & 15;
                int wg = (vIdx >> 8) & 15;
                int lid = vIdx & 255;
                int word = packedFrames[4 + (wg << 5) + (lid >> 3)];
                int actualNibble = (word >>> ((lx & 7) << 2)) & 0xF;
                int expectedNibble = SampleBlendedMultiOctaveNoise.sampleBlended(ox + lx, oy + ly, oz + lz) > 0.0 ? 1 : 0;
                assertEquals(expectedNibble, actualNibble, "LCA nibble mismatch at vIdx=" + vIdx);
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static Class<?> compileAndLoadGeneratedJava(Path workDir, String sourceCode) throws Exception {
        Files.createDirectories(workDir);
        Matcher m = Pattern.compile("public\\s+final\\s+class\\s+(\\w+)").matcher(sourceCode);
        assertTrue(m.find(), "Could not locate generated Java class name");
        String className = m.group(1);
        Path javaFile = workDir.resolve(className + ".java");
        Files.writeString(javaFile, sourceCode, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK system JavaCompiler is required to compile generated Java");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(javaFile.toFile());
            Boolean ok = compiler.getTask(null, fm, diagnostics, List.of("-d", workDir.toString()), null, units).call();
            if (!Boolean.TRUE.equals(ok)) {
                StringBuilder err = new StringBuilder("Generated Java compilation failed:\n");
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    err.append(d).append('\n');
                }
                throw new AssertionError(err.toString());
            }
        }
        URLClassLoader loader = new URLClassLoader(new URL[] {workDir.toUri().toURL()}, BytecodeConceptTranspilerTest.class.getClassLoader());
        return Class.forName(className, true, loader);
    }

    private static double[] runOpenClViaGccShim(
            Path workDir,
            String clSource,
            GpuExactArithmeticMode mode,
            double[][] coords) throws Exception {
        Files.createDirectories(workDir);
        String sanitizedCl = clSource.replaceAll("(?m)^#pragma\\s+OPENCL\\b.*$", "");
        StringBuilder driver = new StringBuilder();
        driver.append("#include <stdio.h>\n")
                .append("#include <stdint.h>\n")
                .append("#include <math.h>\n")
                .append("#define __constant const\n")
                .append("#define __kernel\n")
                .append("#define __global\n")
                .append("#define __local\n")
                .append("#define restrict\n")
                .append("typedef uint64_t ulong;\n")
                .append("typedef uint32_t uint;\n")
                .append("typedef uint8_t uchar;\n")
                .append("typedef struct { int x, y, z, w; } int4;\n")
                .append("static inline long mul_hi(long a, long b) { return (long)(((__int128)a * (__int128)b) >> 64); }\n")
                .append("static inline size_t get_global_id(int d) { (void)d; return 0; }\n")
                .append("static inline size_t get_local_id(int d) { (void)d; return 0; }\n")
                .append("static inline void barrier(int f) { (void)f; }\n")
                .append("static inline uint atomic_or(volatile uint* p, uint v) { uint o = *p; *p = o | v; return o; }\n")
                .append("#define CLK_LOCAL_MEM_FENCE 1\n")
                .append("#define CLK_GLOBAL_MEM_FENCE 2\n")
                .append("static inline double floor_cl_d(double v) { return floor(v); }\n")
                .append("static inline float floor_cl_f(float v) { return floorf(v); }\n")
                .append("#define floor(x) _Generic((x), float: floorf, double: floor)(x)\n")
                .append("#define ceil(x) _Generic((x), float: ceilf, double: ceil)(x)\n")
                .append("#define fabs(x) _Generic((x), float: fabsf, double: fabs)(x)\n")
                .append("#define fmin(x, y) _Generic((x), float: fminf, double: fmin)(x, y)\n")
                .append("#define fmax(x, y) _Generic((x), float: fmaxf, double: fmax)(x, y)\n")
                .append("#define rint(x) _Generic((x), float: rintf, double: rint)(x)\n")
                .append("#define fma(x, y, z) _Generic((x), float: fmaf, double: fma)(x, y, z)\n")
                .append("#define inline static inline\n\n")
                .append(sanitizedCl)
                .append("\nint main(void) {\n");
        for (double[] pt : coords) {
            if (mode == GpuExactArithmeticMode.NATIVE_FP64) {
                driver.append("    printf(\"%.17g\\n\", sampleBlended(")
                        .append(pt[0]).append(", ").append(pt[1]).append(", ").append(pt[2]).append("));\n");
            } else if (mode == GpuExactArithmeticMode.DOWNCAST_FP32) {
                driver.append("    printf(\"%.9g\\n\", (double)sampleBlended(")
                        .append((float) pt[0]).append("f, ").append((float) pt[1]).append("f, ").append((float) pt[2]).append("f));\n");
            } else {
                driver.append("    printf(\"%.9g\\n\", (double)sampleBlended(")
                        .append(ConceptOptimizerPass.doubleToQ32(pt[0])).append("L, ")
                        .append(ConceptOptimizerPass.doubleToQ32(pt[1])).append("L, ")
                        .append(ConceptOptimizerPass.doubleToQ32(pt[2])).append("L));\n");
            }
        }
        driver.append("    return 0;\n}\n");

        Path cFile = workDir.resolve("kernel_driver.c");
        Path binFile = workDir.resolve("kernel_driver");
        Files.writeString(cFile, driver.toString(), StandardCharsets.UTF_8);

        Process compile = new ProcessBuilder(
                "/usr/bin/gcc", "-std=c11", "-O2", "-ffp-contract=off",
                cFile.toString(), "-o", binFile.toString(), "-lm")
                .redirectErrorStream(true)
                .start();
        String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, compile.waitFor(), "GCC compilation of OpenCL " + mode + " failed:\n" + compileOut);

        Process run = new ProcessBuilder(binFile.toString()).redirectErrorStream(true).start();
        String runOut = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, run.waitFor(), "GCC execution of OpenCL " + mode + " failed:\n" + runOut);

        String[] lines = runOut.trim().split("\\R");
        assertEquals(coords.length, lines.length, "Unexpected output line count from GCC runner");
        double[] out = new double[coords.length];
        for (int i = 0; i < coords.length; i++) {
            out[i] = Double.parseDouble(lines[i].trim());
        }
        return out;
    }

    private static boolean isRealOpenClGpuAvailable() {
        if (!Files.isExecutable(Path.of("/usr/bin/python3"))) {
            return false;
        }
        if (!Files.exists(Path.of("/usr/lib64/libOpenCL.so.1"))
                && !Files.exists(Path.of("/usr/lib/x86_64-linux-gnu/libOpenCL.so.1"))) {
            return false;
        }
        try {
            String probe = """
                    import ctypes, sys
                    for path in ("/usr/lib64/libOpenCL.so.1", "/usr/lib/x86_64-linux-gnu/libOpenCL.so.1"):
                        try:
                            lib = ctypes.CDLL(path)
                            num_p = ctypes.c_uint(0)
                            if lib.clGetPlatformIDs(0, None, ctypes.byref(num_p)) == 0 and num_p.value > 0:
                                plats = (ctypes.c_void_p * num_p.value)()
                                lib.clGetPlatformIDs(num_p.value, plats, None)
                                dev = ctypes.c_void_p()
                                num_d = ctypes.c_uint(0)
                                for p in plats:
                                    if lib.clGetDeviceIDs(ctypes.c_void_p(p), 4, 1, ctypes.byref(dev), ctypes.byref(num_d)) == 0 and num_d.value > 0:
                                        sys.exit(0)
                        except OSError:
                            pass
                    sys.exit(1)
                    """;
            Process p = new ProcessBuilder("/usr/bin/python3", "-c", probe).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void verifyOpenClCompilesOnGpu(Path clFile, String clSource) throws Exception {
        Files.writeString(clFile, clSource, StandardCharsets.UTF_8);
        String py = """
                import ctypes, sys
                lib = None
                for path in ("/usr/lib64/libOpenCL.so.1", "/usr/lib/x86_64-linux-gnu/libOpenCL.so.1", "libOpenCL.so.1"):
                    try:
                        lib = ctypes.CDLL(path)
                        break
                    except OSError:
                        pass
                if lib is None:
                    sys.exit(0)
                num_p = ctypes.c_uint(0)
                if lib.clGetPlatformIDs(0, None, ctypes.byref(num_p)) != 0 or num_p.value == 0:
                    sys.exit(0)
                plats = (ctypes.c_void_p * num_p.value)()
                lib.clGetPlatformIDs(num_p.value, plats, None)
                dev = ctypes.c_void_p()
                num_d = ctypes.c_uint(0)
                found = False
                for p in plats:
                    if lib.clGetDeviceIDs(ctypes.c_void_p(p), 4, 1, ctypes.byref(dev), ctypes.byref(num_d)) == 0 and num_d.value > 0:
                        found = True
                        break
                if not found:
                    sys.exit(0)
                err = ctypes.c_int(0)
                lib.clCreateContext.restype = ctypes.c_void_p
                ctx = lib.clCreateContext(None, 1, ctypes.byref(dev), None, None, ctypes.byref(err))
                assert err.value == 0 and ctx, f"clCreateContext failed: {err.value}"
                src = open(sys.argv[1], "rb").read()
                src_p = ctypes.c_char_p(src)
                src_l = ctypes.c_size_t(len(src))
                lib.clCreateProgramWithSource.restype = ctypes.c_void_p
                prog = lib.clCreateProgramWithSource(ctypes.c_void_p(ctx), 1, ctypes.byref(src_p), ctypes.byref(src_l), ctypes.byref(err))
                assert err.value == 0 and prog, f"clCreateProgramWithSource failed: {err.value}"
                rc = lib.clBuildProgram(ctypes.c_void_p(prog), 1, ctypes.byref(dev), b"", None, None)
                if rc != 0:
                    log_sz = ctypes.c_size_t(0)
                    lib.clGetProgramBuildInfo(ctypes.c_void_p(prog), dev, 0x1183, 0, None, ctypes.byref(log_sz))
                    buf = ctypes.create_string_buffer(max(1, log_sz.value))
                    lib.clGetProgramBuildInfo(ctypes.c_void_p(prog), dev, 0x1183, log_sz.value, buf, None)
                    print(buf.value.decode("utf-8", errors="replace"), file=sys.stderr)
                    sys.exit(rc)
                lib.clReleaseProgram(ctypes.c_void_p(prog))
                lib.clReleaseContext(ctypes.c_void_p(ctx))
                """;
        Process p = new ProcessBuilder("/usr/bin/python3", "-c", py, clFile.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "Real OpenCL GPU build failed for " + clFile.getFileName() + ":\n" + output);
    }

    private static double[] runOpenClSinglePointOnGpu(
            Path workDir,
            String clSource,
            GpuExactArithmeticMode mode,
            double[][] coords) throws Exception {
        Files.createDirectories(workDir);
        Path clFile = workDir.resolve("kernel.cl");
        Files.writeString(clFile, clSource, StandardCharsets.UTF_8);

        StringBuilder coordArg = new StringBuilder();
        for (int i = 0; i < coords.length; i++) {
            if (i > 0) {
                coordArg.append(';');
            }
            if (mode == GpuExactArithmeticMode.INT64_Q32_FP32) {
                coordArg.append(ConceptOptimizerPass.doubleToQ32(coords[i][0])).append(',')
                        .append(ConceptOptimizerPass.doubleToQ32(coords[i][1])).append(',')
                        .append(ConceptOptimizerPass.doubleToQ32(coords[i][2]));
            } else {
                coordArg.append(coords[i][0]).append(',')
                        .append(coords[i][1]).append(',')
                        .append(coords[i][2]);
            }
        }

        String py = """
                import ctypes, sys
                lib = None
                for path in ("/usr/lib64/libOpenCL.so.1", "/usr/lib/x86_64-linux-gnu/libOpenCL.so.1", "libOpenCL.so.1"):
                    try:
                        lib = ctypes.CDLL(path)
                        break
                    except OSError:
                        pass
                assert lib is not None
                num_p = ctypes.c_uint(0)
                lib.clGetPlatformIDs(0, None, ctypes.byref(num_p))
                plats = (ctypes.c_void_p * num_p.value)()
                lib.clGetPlatformIDs(num_p.value, plats, None)
                dev = ctypes.c_void_p()
                num_d = ctypes.c_uint(0)
                for p in plats:
                    if lib.clGetDeviceIDs(ctypes.c_void_p(p), 4, 1, ctypes.byref(dev), ctypes.byref(num_d)) == 0 and num_d.value > 0:
                        break
                err = ctypes.c_int(0)
                lib.clCreateContext.restype = ctypes.c_void_p
                ctx = lib.clCreateContext(None, 1, ctypes.byref(dev), None, None, ctypes.byref(err))
                assert err.value == 0
                lib.clCreateCommandQueue.restype = ctypes.c_void_p
                q = lib.clCreateCommandQueue(ctypes.c_void_p(ctx), dev, 0, ctypes.byref(err))
                assert err.value == 0
                src = open(sys.argv[1], "rb").read()
                mode = sys.argv[2]
                pts = sys.argv[3].split(";")
                src_p = ctypes.c_char_p(src)
                src_l = ctypes.c_size_t(len(src))
                lib.clCreateProgramWithSource.restype = ctypes.c_void_p
                prog = lib.clCreateProgramWithSource(ctypes.c_void_p(ctx), 1, ctypes.byref(src_p), ctypes.byref(src_l), ctypes.byref(err))
                rc = lib.clBuildProgram(ctypes.c_void_p(prog), 1, ctypes.byref(dev), b"", None, None)
                if rc != 0:
                    log_sz = ctypes.c_size_t(0)
                    lib.clGetProgramBuildInfo(ctypes.c_void_p(prog), dev, 0x1183, 0, None, ctypes.byref(log_sz))
                    buf = ctypes.create_string_buffer(max(1, log_sz.value))
                    lib.clGetProgramBuildInfo(ctypes.c_void_p(prog), dev, 0x1183, log_sz.value, buf, None)
                    print(buf.value.decode("utf-8", errors="replace"), file=sys.stderr)
                    sys.exit(rc)
                lib.clCreateKernel.restype = ctypes.c_void_p
                kern = lib.clCreateKernel(ctypes.c_void_p(prog), b"evaluate_sampleBlended_grid3d", ctypes.byref(err))
                assert err.value == 0 and kern
                out_ctype = ctypes.c_double if mode == "NATIVE_FP64" else ctypes.c_float
                lib.clCreateBuffer.restype = ctypes.c_void_p
                out_buf = lib.clCreateBuffer(ctypes.c_void_p(ctx), 1, ctypes.sizeof(out_ctype), None, ctypes.byref(err))
                assert err.value == 0
                one_i = ctypes.c_int(1)
                gsize = (ctypes.c_size_t * 3)(1, 1, 1)
                for pt in pts:
                    xs, ys, zs = pt.split(",")
                    if mode == "NATIVE_FP64":
                        ox, oy, oz, sc = ctypes.c_double(float(xs)), ctypes.c_double(float(ys)), ctypes.c_double(float(zs)), ctypes.c_double(1.0)
                    elif mode == "DOWNCAST_FP32":
                        ox, oy, oz, sc = ctypes.c_float(float(xs)), ctypes.c_float(float(ys)), ctypes.c_float(float(zs)), ctypes.c_float(1.0)
                    else:
                        ox, oy, oz, sc = ctypes.c_longlong(int(xs)), ctypes.c_longlong(int(ys)), ctypes.c_longlong(int(zs)), ctypes.c_longlong(1 << 32)
                    out_ptr = ctypes.c_void_p(out_buf)
                    assert lib.clSetKernelArg(ctypes.c_void_p(kern), 0, ctypes.sizeof(ctypes.c_void_p), ctypes.byref(out_ptr)) == 0
                    assert lib.clSetKernelArg(ctypes.c_void_p(kern), 1, ctypes.sizeof(one_i), ctypes.byref(one_i)) == 0
                    assert lib.clSetKernelArg(ctypes.c_void_p(kern), 2, ctypes.sizeof(one_i), ctypes.byref(one_i)) == 0
                    assert lib.clSetKernelArg(ctypes.c_void_p(kern), 3, ctypes.sizeof(one_i), ctypes.byref(one_i)) == 0
                    assert lib.clSetKernelArg(ctypes.c_void_p(kern), 4, ctypes.sizeof(ox), ctypes.byref(ox)) == 0
                    assert lib.clSetKernelArg(ctypes.c_void_p(kern), 5, ctypes.sizeof(oy), ctypes.byref(oy)) == 0
                    assert lib.clSetKernelArg(ctypes.c_void_p(kern), 6, ctypes.sizeof(oz), ctypes.byref(oz)) == 0
                    assert lib.clSetKernelArg(ctypes.c_void_p(kern), 7, ctypes.sizeof(sc), ctypes.byref(sc)) == 0
                    assert lib.clEnqueueNDRangeKernel(ctypes.c_void_p(q), ctypes.c_void_p(kern), 3, None, gsize, None, 0, None, None) == 0
                    res = out_ctype(0)
                    assert lib.clEnqueueReadBuffer(ctypes.c_void_p(q), ctypes.c_void_p(out_buf), 1, 0, ctypes.sizeof(out_ctype), ctypes.byref(res), 0, None, None) == 0
                    print(f"{res.value:.17g}")
                lib.clReleaseMemObject(ctypes.c_void_p(out_buf))
                lib.clReleaseKernel(ctypes.c_void_p(kern))
                lib.clReleaseProgram(ctypes.c_void_p(prog))
                lib.clReleaseCommandQueue(ctypes.c_void_p(q))
                lib.clReleaseContext(ctypes.c_void_p(ctx))
                """;
        Process p = new ProcessBuilder("/usr/bin/python3", "-c", py, clFile.toString(), mode.name(), coordArg.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "Real GPU OpenCL execution failed for " + mode + ":\n" + output);

        String[] lines = output.trim().split("\\R");
        assertEquals(coords.length, lines.length, "Unexpected output line count from GPU runner");
        double[] out = new double[coords.length];
        for (int i = 0; i < coords.length; i++) {
            out[i] = Double.parseDouble(lines[i].trim());
        }
        return out;
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // Closed-World Live Object-Graph Lifting Fixtures (Pure Generic JVM Shapes)
    // -------------------------------------------------------------------------

    public interface CalcCtx {
        int xCoord();

        int yCoord();

        int zCoord();
    }

    public record BasicCtx(int xCoord, int yCoord, int zCoord) implements CalcCtx {
    }

    public interface GridSamplerCtx {
        float bias();
    }

    public record SimpleSamplerCtx(float bias) implements GridSamplerCtx {
    }

    public interface RuleEvalCtx {
        long worldSeed();
    }

    public record SimpleRuleCtx(long worldSeed) implements RuleEvalCtx {
    }

    public record VecRec(float gx, float gy, float gz) {
        public float dot3(float dx, float dy, float dz) {
            return gx * dx + gy * dy + gz * dz;
        }
    }

    public enum CombineOp {
        ADD,
        MUL,
        CLAMP_BLEND
    }

    public record RefBox<T>(T target) {
    }

    public interface ScalarDagNode {
        double eval(CalcCtx ctx);

        default float evalAsFloat(CalcCtx ctx) {
            return (float) eval(ctx);
        }
    }

    public static final class Wrap1 implements ScalarDagNode {
        private final RefBox<ScalarDagNode> holder;

        public Wrap1(RefBox<ScalarDagNode> holder) {
            this.holder = holder;
        }

        @Override
        public double eval(CalcCtx ctx) {
            return holder.target().eval(ctx);
        }
    }

    public static final class DirectWrap implements ScalarDagNode {
        private final ScalarDagNode inner;

        public DirectWrap(ScalarDagNode inner) {
            this.inner = inner;
        }

        @Override
        public double eval(CalcCtx ctx) {
            return inner.eval(ctx);
        }
    }

    public record OffsetLeafRec(double base, VecRec weights) implements ScalarDagNode {
        @Override
        public double eval(CalcCtx ctx) {
            return base
                    + (double) weights.gx() * ctx.xCoord()
                    + (double) weights.gy() * ctx.yCoord()
                    + (double) weights.gz() * ctx.zCoord();
        }
    }

    public static final class AlphaNode implements ScalarDagNode {
        private final double freq;
        private final float floatAmplitude;
        private final int[] perm;
        private final int[][] grad2d;
        private final Optional<ScalarDagNode> modulation;
        private final OptionalDouble bonus;
        private final CombineOp op;

        public AlphaNode(
                double freq,
                float floatAmplitude,
                int[] perm,
                int[][] grad2d,
                Optional<ScalarDagNode> modulation,
                OptionalDouble bonus,
                CombineOp op) {
            this.freq = freq;
            this.floatAmplitude = floatAmplitude;
            this.perm = perm;
            this.grad2d = grad2d;
            this.modulation = modulation;
            this.bonus = bonus;
            this.op = op;
        }

        @Override
        public double eval(CalcCtx ctx) {
            double sx = ctx.xCoord() * freq;
            double sy = ctx.yCoord() * freq;
            double sz = ctx.zCoord() * freq;
            int ix = (int) Math.floor(sx);
            int iy = (int) Math.floor(sy);
            int iz = (int) Math.floor(sz);
            double fx = sx - ix;
            double fy = sy - iy;
            double fz = sz - iz;
            int h = perm[(perm[(ix & 15) + (iy & 15)] + (iz & 15)) & 31] & 7;
            int[] g = grad2d[h];
            double dot = g[0] * fx + g[1] * fy + g[2] * fz;
            double val = dot * (double) floatAmplitude + bonus.orElse(0.125);
            if (modulation.isPresent()) {
                double m = modulation.get().eval(ctx);
                val = switch (op) {
                    case ADD -> val + m;
                    case MUL -> val * m;
                    case CLAMP_BLEND -> Math.max(-2.0, Math.min(2.0, val + m * 0.5));
                };
            } else if (op.ordinal() == 1) {
                val = val * 1.5;
            }
            return val;
        }
    }

    public static final class SeqLoopNode implements ScalarDagNode {
        private final List<ScalarDagNode> children;
        private final ScalarDagNode[] postStages;
        private final double[] weights;

        public SeqLoopNode(List<ScalarDagNode> children, ScalarDagNode[] postStages, double[] weights) {
            this.children = children;
            this.postStages = postStages;
            this.weights = weights;
        }

        @Override
        public double eval(CalcCtx ctx) {
            double acc = 0.0;
            for (int i = 0; i < children.size(); i++) {
                acc += children.get(i).eval(ctx) * weights[i];
            }
            for (ScalarDagNode post : postStages) {
                acc += post.evalAsFloat(ctx) * 0.25;
            }
            return acc;
        }
    }

    public interface VoxelFieldSampler {
        float sampleValue(GridSamplerCtx ctx, int x, int y, int z);
    }

    public record SamplerWrap(RefBox<VoxelFieldSampler> box) implements VoxelFieldSampler {
        @Override
        public float sampleValue(GridSamplerCtx ctx, int x, int y, int z) {
            return box.target().sampleValue(ctx, x, y, z);
        }
    }

    public static final class VecLutSampler implements VoxelFieldSampler {
        private final VecRec[] gradLut;
        private final int[] perm;
        private final VecRec scaleRec;
        private final double fineScale;

        public VecLutSampler(VecRec[] gradLut, int[] perm, VecRec scaleRec, double fineScale) {
            this.gradLut = gradLut;
            this.perm = perm;
            this.scaleRec = scaleRec;
            this.fineScale = fineScale;
        }

        private static double preciseWave(double u, double v) {
            return u * 0.75 - v * 0.25;
        }

        @Override
        public float sampleValue(GridSamplerCtx ctx, int x, int y, int z) {
            float fx = x * scaleRec.gx();
            float fy = y * scaleRec.gy();
            float fz = z * scaleRec.gz();
            int idx = perm[((x & 7) * 3 + (y & 7) * 5 + (z & 7)) & 31] & (gradLut.length - 1);
            VecRec g = gradLut[idx];
            float d = g.dot3(fx, fy, fz) + g.gy() * 0.125f;
            float coerced = (float) preciseWave((double) fx * fineScale, (double) fz * fineScale);
            return d + coerced + ctx.bias();
        }
    }

    public static final class CompositeSamplerLoop implements VoxelFieldSampler {
        private final List<VoxelFieldSampler> layers;
        private final float[] gains;

        public CompositeSamplerLoop(List<VoxelFieldSampler> layers, float[] gains) {
            this.layers = layers;
            this.gains = gains;
        }

        @Override
        public float sampleValue(GridSamplerCtx ctx, int x, int y, int z) {
            float sum = 0.0f;
            int idx = 0;
            for (VoxelFieldSampler layer : layers) {
                sum += layer.sampleValue(ctx, x, y, z) * gains[idx];
                idx++;
            }
            return sum;
        }
    }

    public static final class SurfaceMatToken {
        private final String label;

        public SurfaceMatToken(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public interface SurfaceDecisionNode {
        SurfaceMatToken choose(RuleEvalCtx ctx, int x, int y, int z);
    }

    public record YThresholdDecision(int minY, SurfaceMatToken token) implements SurfaceDecisionNode {
        @Override
        public SurfaceMatToken choose(RuleEvalCtx ctx, int x, int y, int z) {
            long hash = (ctx.worldSeed() ^ (x * 31L + z * 17L)) & 3L;
            if (y + (int) hash >= minY) {
                return token;
            }
            return null;
        }
    }

    public static final class SequenceDecisionRule implements SurfaceDecisionNode {
        private final List<SurfaceDecisionNode> rules;
        private final SurfaceMatToken fallback;

        public SequenceDecisionRule(List<SurfaceDecisionNode> rules, SurfaceMatToken fallback) {
            this.rules = rules;
            this.fallback = fallback;
        }

        @Override
        public SurfaceMatToken choose(RuleEvalCtx ctx, int x, int y, int z) {
            for (SurfaceDecisionNode rule : rules) {
                SurfaceMatToken res = rule.choose(ctx, x, y, z);
                if (res != null) {
                    return res;
                }
            }
            return fallback;
        }
    }

    public static final class ImpureNanoTimeNode implements ScalarDagNode {
        @Override
        public double eval(CalcCtx ctx) {
            return (System.nanoTime() & 0xFFL) + ctx.xCoord();
        }
    }

    public static final class ImpureFieldMutatorNode implements ScalarDagNode {
        private int invocations;

        @Override
        public double eval(CalcCtx ctx) {
            invocations = invocations + 1;
            return invocations + ctx.yCoord();
        }
    }

    @Test
    @DisplayName("Lifts closed-world compute(Ctx)->double OOP DAG with wrappers, Records, Optionals, Enums, List loops, 2D LUTs, and LUT dedup")
    void liftsClosedWorldComputeDoubleDagWithZeroDrift() throws Exception {
        int[] sharedPerm = new int[32];
        for (int i = 0; i < 32; i++) {
            sharedPerm[i] = (i * 13 + 7) & 31;
        }
        int[] secondIdenticalPerm = sharedPerm.clone();
        int[][] grad2d = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
            {1, 0, 1}, {-1, 0, 1}, {0, 1, 1}, {0, -1, 1}
        };

        ScalarDagNode modLeaf = new OffsetLeafRec(0.375, new VecRec(0.0625f, -0.03125f, 0.125f));
        AlphaNode alpha1 = new AlphaNode(
                0.125,
                0.75f,
                sharedPerm,
                grad2d,
                Optional.of(new DirectWrap(modLeaf)),
                OptionalDouble.of(0.25),
                CombineOp.CLAMP_BLEND);
        AlphaNode alpha2 = new AlphaNode(
                0.25,
                -0.5f,
                secondIdenticalPerm,
                grad2d,
                Optional.empty(),
                OptionalDouble.empty(),
                CombineOp.MUL);
        ScalarDagNode wrappedAlpha2 = new Wrap1(new RefBox<>(new DirectWrap(alpha2)));
        ScalarDagNode root = new Wrap1(new RefBox<>(new SeqLoopNode(
                List.of(alpha1, wrappedAlpha2),
                new ScalarDagNode[] {modLeaf},
                new double[] {1.0, 0.5})));

        BytecodeSsaLifter.ObjectInterner ctxInterner = new BytecodeSsaLifter.ObjectInterner() {
            @Override
            public BytecodeSsaLifter.ContextParamBinding resolveContextGetter(
                    int entryParamIndex, String ownerInternalName, String methodName, String descriptor) {
                if (entryParamIndex == 0 && "()I".equals(descriptor)) {
                    return switch (methodName) {
                        case "xCoord" -> new BytecodeSsaLifter.ContextParamBinding("x", BytecodeConceptIr.ConceptType.I32);
                        case "yCoord" -> new BytecodeSsaLifter.ContextParamBinding("y", BytecodeConceptIr.ConceptType.I32);
                        case "zCoord" -> new BytecodeSsaLifter.ContextParamBinding("z", BytecodeConceptIr.ConceptType.I32);
                        default -> null;
                    };
                }
                return null;
            }

            @Override
            public List<BytecodeSsaLifter.ContextParamBinding> contextParameters(int entryParamIndex) {
                if (entryParamIndex == 0) {
                    return List.of(
                            new BytecodeSsaLifter.ContextParamBinding("x", BytecodeConceptIr.ConceptType.I32),
                            new BytecodeSsaLifter.ContextParamBinding("y", BytecodeConceptIr.ConceptType.I32),
                            new BytecodeSsaLifter.ContextParamBinding("z", BytecodeConceptIr.ConceptType.I32));
                }
                return List.of();
            }
        };

        String desc = "(" + CalcCtx.class.descriptorString() + ")D";
        ConceptModule lifted = BytecodeSsaLifter.liftInstanceGraph(
                root,
                "eval",
                desc,
                BytecodeSsaLifter.GraphLiftOptions.withInterner(ctxInterner));
        assertTrue(lifted.isPureCompute(), () -> "Expected pure compute module, got unmapped: " + lifted.unmappedReasons());
        assertTrue(lifted.unmappedReasons().isEmpty());

        // Verify structural wrapper erasure eliminated Wrap1 and DirectWrap helpers
        for (ConceptFunction helper : lifted.helperFunctions()) {
            assertFalse(helper.ownerClass().endsWith("/Wrap1"), "Wrap1 wrapper should be erased by bytecode shape");
            assertFalse(helper.ownerClass().endsWith("/DirectWrap"), "DirectWrap wrapper should be erased by bytecode shape");
        }

        ConceptModule optimized = ConceptOptimizerPass.optimize(lifted);
        assertTrue(optimized.isPureCompute());
        assertTrue(optimized.helperFunctions().isEmpty(),
                "Expected ConceptOptimizerPass to inline all specialized instance helpers");

        // sharedPerm and secondIdenticalPerm have identical 32 I32 values -> deduplicated to 1 perm LUT + 1 grad2d LUT (weights folded!)
        long permLutCount = optimized.lookupTables().stream()
                .filter(l -> l.elementType() == BytecodeConceptIr.ConceptType.I32 && l.values().size() == 32)
                .count();
        assertEquals(1L, permLutCount, "Expected identical 32-entry perm LUTs across AlphaNode instances to be deduplicated");
        long weightsLutCount = optimized.lookupTables().stream()
                .filter(l -> l.elementType() == BytecodeConceptIr.ConceptType.F64)
                .count();
        assertEquals(0L, weightsLutCount, "Expected constant-index weights[] loads in unrolled loop to be constant-folded and pruned");

        Path tempDir = Files.createTempDirectory("graph_lift_double_test");
        try {
            EmitOptions fp64Opts = EmitOptions.gpuFp64();
            TranspiledArtifact javaArt = JavaConceptBackendEmitter.javaBackend().emit(optimized, fp64Opts);
            Class<?> compiledJava = compileAndLoadGeneratedJava(tempDir.resolve("java_fp64"), javaArt.sourceCode());
            Method evalMethod = compiledJava.getMethod("eval", int.class, int.class, int.class);

            TranspiledArtifact clArt = new OpenClBackendEmitter().emit(optimized, fp64Opts);
            int[][] coords = {
                {0, 64, 0},
                {17, -12, 43},
                {-129, 128, -77},
                {511, 0, -513},
                {-3, 19, 8}
            };
            double[] clOut = runScalarIntCoordOpenClViaGcc(tempDir.resolve("cl_fp64"), clArt.sourceCode(), "eval", true, coords, null);

            for (int i = 0; i < coords.length; i++) {
                int x = coords[i][0];
                int y = coords[i][1];
                int z = coords[i][2];
                double expected = root.eval(new BasicCtx(x, y, z));
                double irVal = ((Number) ConceptIrEvaluator.evaluateEntry(optimized, x, y, z)).doubleValue();
                double javaBackendVal = ((Number) evalMethod.invoke(null, x, y, z)).doubleValue();
                assertEquals(expected, irVal, 0.0, "Zero drift required for ConceptIrEvaluator at " + i);
                assertEquals(expected, javaBackendVal, 0.0, "Zero drift required for JavaConceptBackendEmitter at " + i);
                assertEquals(expected, clOut[i], 0.0, "Zero drift required for OpenCL C99 backend at " + i);
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("Lifts closed-world sampleValue(SamplerCtx,int,int,int)->float OOP DAG with VecRec[] SoA LUTs and D2F/F2D coercion")
    void liftsClosedWorldSampleFloatDagWithRecordLutsAndZeroDrift() throws Exception {
        VecRec[] gradLut = {
            new VecRec(1.0f, 0.5f, -0.5f),
            new VecRec(-1.0f, 0.25f, 0.75f),
            new VecRec(0.5f, -1.0f, 0.25f),
            new VecRec(-0.5f, -0.25f, 1.0f)
        };
        int[] perm = new int[32];
        for (int i = 0; i < 32; i++) {
            perm[i] = (i * 7 + 3) & 31;
        }

        VoxelFieldSampler layer0 = new SamplerWrap(new RefBox<>(
                new VecLutSampler(gradLut, perm, new VecRec(0.25f, 0.125f, 0.5f), 0.5)));
        VoxelFieldSampler layer1 = new VecLutSampler(gradLut, perm.clone(), new VecRec(-0.125f, 0.25f, 0.25f), 0.25);
        VoxelFieldSampler root = new SamplerWrap(new RefBox<>(
                new CompositeSamplerLoop(List.of(layer0, layer1), new float[] {0.75f, -0.5f})));

        BytecodeSsaLifter.ObjectInterner samplerInterner = new BytecodeSsaLifter.ObjectInterner() {
            @Override
            public BytecodeSsaLifter.ContextParamBinding resolveContextGetter(
                    int entryParamIndex, String ownerInternalName, String methodName, String descriptor) {
                if (entryParamIndex == 0 && "bias".equals(methodName) && "()F".equals(descriptor)) {
                    return new BytecodeSsaLifter.ContextParamBinding("bias", BytecodeConceptIr.ConceptType.F32);
                }
                return null;
            }
        };

        String desc = "(" + GridSamplerCtx.class.descriptorString() + "III)F";
        ConceptModule lifted = BytecodeSsaLifter.liftInstanceGraph(
                root,
                "sampleValue",
                desc,
                BytecodeSsaLifter.GraphLiftOptions.withInterner(samplerInterner));
        assertTrue(lifted.isPureCompute(), () -> "Expected pure float sampler module, got: " + lifted.unmappedReasons());

        ConceptModule optimized = ConceptOptimizerPass.optimize(lifted);
        assertTrue(optimized.isPureCompute());
        assertTrue(optimized.helperFunctions().isEmpty(), "Expected all float sampler helpers to inline");

        Path tempDir = Files.createTempDirectory("graph_lift_float_test");
        try {
            EmitOptions opts = EmitOptions.gpuFp64();
            TranspiledArtifact javaArt = JavaConceptBackendEmitter.javaBackend().emit(optimized, opts);
            Class<?> compiledJava = compileAndLoadGeneratedJava(tempDir.resolve("java_f32"), javaArt.sourceCode());
            Method sampleMethod = compiledJava.getMethod("sampleValue", int.class, int.class, int.class, float.class);

            TranspiledArtifact clArt = new OpenClBackendEmitter().emit(optimized, opts);
            int[][] coords = {
                {0, 0, 0},
                {12, 64, -9},
                {-35, 120, 77},
                {255, -64, -128}
            };
            float biasVal = 0.375f;
            double[] clOut = runScalarIntCoordOpenClViaGcc(
                    tempDir.resolve("cl_f32"), clArt.sourceCode(), "sampleValue", false, coords, biasVal);

            GridSamplerCtx liveCtx = new SimpleSamplerCtx(biasVal);
            for (int i = 0; i < coords.length; i++) {
                int x = coords[i][0];
                int y = coords[i][1];
                int z = coords[i][2];
                float expected = root.sampleValue(liveCtx, x, y, z);
                float irVal = ((Number) ConceptIrEvaluator.evaluateEntry(optimized, x, y, z, biasVal)).floatValue();
                float javaBackendVal = ((Number) sampleMethod.invoke(null, x, y, z, biasVal)).floatValue();
                assertEquals(expected, irVal, 0.0f, "Zero FP32 drift required for ConceptIrEvaluator at " + i);
                assertEquals(expected, javaBackendVal, 0.0f, "Zero FP32 drift required for JavaConceptBackendEmitter at " + i);
                assertEquals(expected, (float) clOut[i], 0.0f, "Zero FP32 drift required for OpenCL backend at " + i);
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("Lifts ObjectInterner terminal-object rule DAG with zero drift across Java, JavaConceptBackendEmitter, FastSlotEvaluator, and OpenCL")
    void liftsInternedTerminalObjectRuleDagAcrossFastSlotEvaluatorAndOpenCl() throws Exception {
        SurfaceMatToken granite = new SurfaceMatToken("granite");
        SurfaceMatToken slate = new SurfaceMatToken("slate");
        SurfaceMatToken turf = new SurfaceMatToken("turf");
        IdentityHashMap<Object, Integer> tokenIds = new IdentityHashMap<>();
        tokenIds.put(turf, 11);
        tokenIds.put(granite, 22);
        tokenIds.put(slate, 33);

        SurfaceDecisionNode root = new SequenceDecisionRule(
                List.of(
                        new YThresholdDecision(96, turf),
                        new YThresholdDecision(32, granite)),
                slate);

        BytecodeSsaLifter.ObjectInterner ruleInterner = new BytecodeSsaLifter.ObjectInterner() {
            @Override
            public Integer internObject(Object instance) {
                return tokenIds.get(instance);
            }

            @Override
            public int nullObjectId() {
                return 0;
            }

            @Override
            public BytecodeSsaLifter.ContextParamBinding resolveContextGetter(
                    int entryParamIndex, String ownerInternalName, String methodName, String descriptor) {
                if (entryParamIndex == 0 && "worldSeed".equals(methodName) && "()J".equals(descriptor)) {
                    return new BytecodeSsaLifter.ContextParamBinding("seed", BytecodeConceptIr.ConceptType.I64);
                }
                return null;
            }
        };

        String desc = "(" + RuleEvalCtx.class.descriptorString() + "III)" + SurfaceMatToken.class.descriptorString();
        ConceptModule lifted = BytecodeSsaLifter.liftInstanceGraph(
                root,
                "choose",
                desc,
                BytecodeSsaLifter.GraphLiftOptions.withInterner(ruleInterner));
        assertTrue(lifted.isPureCompute(), () -> "Expected pure rule module, got: " + lifted.unmappedReasons());

        ConceptModule optimized = ConceptOptimizerPass.optimize(lifted);
        assertTrue(optimized.isPureCompute());
        assertEquals(BytecodeConceptIr.ConceptType.I32, optimized.entryFunction().returnType());

        WorldgenKernelSwapper.FastSlotEvaluator fastSlot = WorldgenKernelSwapper.FastSlotEvaluator.compile(optimized);

        Path tempDir = Files.createTempDirectory("graph_lift_rule_test");
        try {
            EmitOptions opts = EmitOptions.gpuFp64();
            TranspiledArtifact javaArt = JavaConceptBackendEmitter.javaBackend().emit(optimized, opts);
            Class<?> compiledJava = compileAndLoadGeneratedJava(tempDir.resolve("java_rule"), javaArt.sourceCode());
            Method chooseMethod = compiledJava.getMethod("choose", int.class, int.class, int.class, long.class);

            TranspiledArtifact clArt = new OpenClBackendEmitter().emit(optimized, opts);
            long seed = 0x123456789ABCDEFL;
            RuleEvalCtx liveCtx = new SimpleRuleCtx(seed);
            int[][] coords = {
                {4, 110, -8},
                {4, 95, -8},
                {-19, 33, 42},
                {-19, 30, 42},
                {0, -16, 0}
            };
            int[] clInts = runIntRuleOpenClViaGcc(tempDir.resolve("cl_rule"), clArt.sourceCode(), "choose", coords, seed);

            for (int i = 0; i < coords.length; i++) {
                int x = coords[i][0];
                int y = coords[i][1];
                int z = coords[i][2];
                int expected = tokenIds.get(root.choose(liveCtx, x, y, z));
                int irVal = ((Number) ConceptIrEvaluator.evaluateEntry(optimized, x, y, z, seed)).intValue();
                int javaVal = ((Number) chooseMethod.invoke(null, x, y, z, seed)).intValue();
                int fastVal = fastSlot.evalVoxel(x, y, z, seed);
                assertEquals(expected, irVal, "ConceptIrEvaluator mismatch at " + i);
                assertEquals(expected, javaVal, "JavaConceptBackendEmitter mismatch at " + i);
                assertEquals(expected, fastVal, "FastSlotEvaluator mismatch at " + i);
                assertEquals(expected, clInts[i], "OpenCL backend mismatch at " + i);
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("Fails closed with exact UnmappedReason on System.nanoTime() and PUTFIELD side effects")
    void failsClosedWithExactUnmappedReasonOnImpureNodes() {
        BytecodeSsaLifter.ObjectInterner ctxInterner = new BytecodeSsaLifter.ObjectInterner() {
            @Override
            public BytecodeSsaLifter.ContextParamBinding resolveContextGetter(
                    int entryParamIndex, String ownerInternalName, String methodName, String descriptor) {
                if (entryParamIndex == 0 && "()I".equals(descriptor)) {
                    return new BytecodeSsaLifter.ContextParamBinding(methodName, BytecodeConceptIr.ConceptType.I32);
                }
                return null;
            }
        };
        String desc = "(" + CalcCtx.class.descriptorString() + ")D";

        // 1. Impure System.nanoTime() inside wrapped DAG
        ScalarDagNode nanoWrapped = new Wrap1(new RefBox<>(new ImpureNanoTimeNode()));
        ConceptModule nanoMod = BytecodeSsaLifter.liftInstanceGraph(
                nanoWrapped,
                "eval",
                desc,
                BytecodeSsaLifter.GraphLiftOptions.withInterner(ctxInterner));
        assertFalse(nanoMod.isPureCompute(), "System.nanoTime() must mark module pureCompute=false");
        assertFalse(nanoMod.unmappedReasons().isEmpty(), "Expected exact UnmappedReason for System.nanoTime()");
        BytecodeSsaLifter.UnmappedReason nanoReason = nanoMod.unmappedReasons().get(0);
        assertTrue(nanoReason.concreteClass().endsWith("ImpureNanoTimeNode"),
                "Unexpected concreteClass: " + nanoReason.concreteClass());
        assertEquals("eval", nanoReason.method());
        assertEquals(0xB8, nanoReason.opcode(), "Expected INVOKESTATIC (0xB8) opcode");
        assertTrue(nanoReason.bytecodeOffset() >= 0);

        ConceptModule nanoOpt = ConceptOptimizerPass.optimize(nanoMod);
        assertFalse(nanoOpt.isPureCompute(), "ConceptOptimizerPass must preserve fail-closed pureCompute=false");
        assertFalse(nanoOpt.unmappedReasons().isEmpty());

        // 2. Impure PUTFIELD mutation inside DAG
        ScalarDagNode mutatorWrapped = new DirectWrap(new ImpureFieldMutatorNode());
        ConceptModule mutMod = BytecodeSsaLifter.liftInstanceGraph(
                mutatorWrapped,
                "eval",
                desc,
                BytecodeSsaLifter.GraphLiftOptions.withInterner(ctxInterner));
        assertFalse(mutMod.isPureCompute(), "PUTFIELD must mark module pureCompute=false");
        assertFalse(mutMod.unmappedReasons().isEmpty(), "Expected exact UnmappedReason for PUTFIELD");
        BytecodeSsaLifter.UnmappedReason mutReason = mutMod.unmappedReasons().stream()
                .filter(r -> r.opcode() == 0xB5)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected PUTFIELD (0xB5) in " + mutMod.unmappedReasons()));
        assertTrue(mutReason.concreteClass().endsWith("ImpureFieldMutatorNode"));
        assertEquals("eval", mutReason.method());
        assertTrue(mutReason.bytecodeOffset() >= 0);
    }

    private static double[] runScalarIntCoordOpenClViaGcc(
            Path workDir,
            String clSource,
            String fnName,
            boolean fp64Return,
            int[][] coords,
            Float optionalExtraFloat) throws Exception {
        Files.createDirectories(workDir);
        String sanitizedCl = clSource.replaceAll("(?m)^#pragma\\s+OPENCL\\b.*$", "");
        StringBuilder driver = new StringBuilder();
        driver.append("#include <stdio.h>\n")
                .append("#include <stdint.h>\n")
                .append("#include <math.h>\n")
                .append("#define __constant const\n")
                .append("#define __kernel\n")
                .append("#define __global\n")
                .append("#define __local\n")
                .append("#define restrict\n")
                .append("typedef uint64_t ulong;\n")
                .append("typedef uint32_t uint;\n")
                .append("typedef uint8_t uchar;\n")
                .append("typedef struct { int x, y, z, w; } int4;\n")
                .append("static inline long mul_hi(long a, long b) { return (long)(((__int128)a * (__int128)b) >> 64); }\n")
                .append("static inline size_t get_global_id(int d) { (void)d; return 0; }\n")
                .append("static inline size_t get_local_id(int d) { (void)d; return 0; }\n")
                .append("static inline void barrier(int f) { (void)f; }\n")
                .append("static inline uint atomic_or(volatile uint* p, uint v) { uint o = *p; *p = o | v; return o; }\n")
                .append("#define CLK_LOCAL_MEM_FENCE 1\n")
                .append("#define CLK_GLOBAL_MEM_FENCE 2\n")
                .append("#define floor(x) _Generic((x), float: floorf, double: floor)(x)\n")
                .append("#define ceil(x) _Generic((x), float: ceilf, double: ceil)(x)\n")
                .append("#define fabs(x) _Generic((x), float: fabsf, double: fabs)(x)\n")
                .append("#define fmin(x, y) _Generic((x), float: fminf, double: fmin)(x, y)\n")
                .append("#define fmax(x, y) _Generic((x), float: fmaxf, double: fmax)(x, y)\n")
                .append("#define rint(x) _Generic((x), float: rintf, double: rint)(x)\n")
                .append("#define fma(x, y, z) _Generic((x), float: fmaf, double: fma)(x, y, z)\n")
                .append("#define inline static inline\n\n")
                .append(sanitizedCl)
                .append("\nint main(void) {\n");
        for (int[] pt : coords) {
            String extra = optionalExtraFloat != null ? (", " + optionalExtraFloat + "f") : "";
            if (fp64Return) {
                driver.append("    printf(\"%.17g\\n\", ")
                        .append(fnName).append('(')
                        .append(pt[0]).append(", ").append(pt[1]).append(", ").append(pt[2]).append(extra)
                        .append("));\n");
            } else {
                driver.append("    printf(\"%.9g\\n\", (double)")
                        .append(fnName).append('(')
                        .append(pt[0]).append(", ").append(pt[1]).append(", ").append(pt[2]).append(extra)
                        .append("));\n");
            }
        }
        driver.append("    return 0;\n}\n");

        Path cFile = workDir.resolve("scalar_driver.c");
        Path binFile = workDir.resolve("scalar_driver");
        Files.writeString(cFile, driver.toString(), StandardCharsets.UTF_8);

        Process compile = new ProcessBuilder(
                "/usr/bin/gcc", "-std=c11", "-O2", "-ffp-contract=off",
                cFile.toString(), "-o", binFile.toString(), "-lm")
                .redirectErrorStream(true)
                .start();
        String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, compile.waitFor(), "GCC compilation of OpenCL scalar kernel failed:\n" + compileOut);

        Process run = new ProcessBuilder(binFile.toString()).redirectErrorStream(true).start();
        String runOut = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, run.waitFor(), "GCC execution of OpenCL scalar kernel failed:\n" + runOut);

        String[] lines = runOut.trim().split("\\R");
        assertEquals(coords.length, lines.length);
        double[] out = new double[coords.length];
        for (int i = 0; i < coords.length; i++) {
            out[i] = Double.parseDouble(lines[i].trim());
        }
        return out;
    }

    private static int[] runIntRuleOpenClViaGcc(
            Path workDir,
            String clSource,
            String fnName,
            int[][] coords,
            long seed) throws Exception {
        Files.createDirectories(workDir);
        String sanitizedCl = clSource.replaceAll("(?m)^#pragma\\s+OPENCL\\b.*$", "");
        StringBuilder driver = new StringBuilder();
        driver.append("#include <stdio.h>\n")
                .append("#include <stdint.h>\n")
                .append("#include <math.h>\n")
                .append("#define __constant const\n")
                .append("#define __kernel\n")
                .append("#define __global\n")
                .append("#define __local\n")
                .append("#define restrict\n")
                .append("typedef uint64_t ulong;\n")
                .append("typedef uint32_t uint;\n")
                .append("typedef uint8_t uchar;\n")
                .append("typedef struct { int x, y, z, w; } int4;\n")
                .append("static inline long mul_hi(long a, long b) { return (long)(((__int128)a * (__int128)b) >> 64); }\n")
                .append("static inline size_t get_global_id(int d) { (void)d; return 0; }\n")
                .append("static inline size_t get_local_id(int d) { (void)d; return 0; }\n")
                .append("static inline void barrier(int f) { (void)f; }\n")
                .append("static inline uint atomic_or(volatile uint* p, uint v) { uint o = *p; *p = o | v; return o; }\n")
                .append("#define CLK_LOCAL_MEM_FENCE 1\n")
                .append("#define CLK_GLOBAL_MEM_FENCE 2\n")
                .append("#define inline static inline\n\n")
                .append(sanitizedCl)
                .append("\nint main(void) {\n");
        for (int[] pt : coords) {
            driver.append("    printf(\"%d\\n\", ")
                    .append(fnName).append('(')
                    .append(pt[0]).append(", ").append(pt[1]).append(", ").append(pt[2])
                    .append(", ").append(seed).append("LL));\n");
        }
        driver.append("    return 0;\n}\n");

        Path cFile = workDir.resolve("rule_driver.c");
        Path binFile = workDir.resolve("rule_driver");
        Files.writeString(cFile, driver.toString(), StandardCharsets.UTF_8);

        Process compile = new ProcessBuilder(
                "/usr/bin/gcc", "-std=c11", "-O2", "-ffp-contract=off",
                cFile.toString(), "-o", binFile.toString(), "-lm")
                .redirectErrorStream(true)
                .start();
        String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, compile.waitFor(), "GCC compilation of OpenCL rule kernel failed:\n" + compileOut);

        Process run = new ProcessBuilder(binFile.toString()).redirectErrorStream(true).start();
        String runOut = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, run.waitFor(), "GCC execution of OpenCL rule kernel failed:\n" + runOut);

        String[] lines = runOut.trim().split("\\R");
        assertEquals(coords.length, lines.length);
        int[] out = new int[coords.length];
        for (int i = 0; i < coords.length; i++) {
            out[i] = Integer.parseInt(lines[i].trim());
        }
        return out;
    }

    static final class StaticRecursiveSplineNode {
        final double threshold;
        final double leafValue;
        final StaticRecursiveSplineNode left;
        final StaticRecursiveSplineNode right;

        StaticRecursiveSplineNode(double threshold, double leafValue, StaticRecursiveSplineNode left, StaticRecursiveSplineNode right) {
            this.threshold = threshold;
            this.leafValue = leafValue;
            this.left = left;
            this.right = right;
        }

        static double sampleStatic(StaticRecursiveSplineNode node, double x) {
            if (node.left == null) {
                return node.leafValue;
            }
            return x < node.threshold ? sampleStatic(node.left, x) : sampleStatic(node.right, x);
        }

        double eval(double x, double y, double z) {
            return sampleStatic(this, x + z * 0.5);
        }
    }

    static final class CustomAbstractMapHolder extends java.util.AbstractMap<String, Integer> {
        final double bias;

        CustomAbstractMapHolder(double bias) {
            this.bias = bias;
        }

        @Override
        public java.util.Set<Entry<String, Integer>> entrySet() {
            return java.util.Set.of();
        }

        double compute(double x, double y, double z) {
            return x + bias;
        }
    }

    @FunctionalInterface
    interface TestFluidPicker {
        TestFluidStatus computeFluid(int x, int y, int z);
    }

    record TestFluidStatus(int fluidLevel, String fluidType) {}

    private static TestFluidPicker createStandardTestFluidPicker(int seaLevel, TestFluidStatus lava, TestFluidStatus sea) {
        return (x, y, z) -> y < Math.min(-54, seaLevel) ? lava : sea;
    }

    @Test
    @DisplayName("Lifts static recursive tree calls, AbstractMap subclasses without InaccessibleObjectException, and hidden $$Lambda closures")
    void liftsStaticRecursiveSplineAbstractMapSubclassAndHiddenLambda() {
        // 1. Static recursive helper across distinct tree instances
        StaticRecursiveSplineNode leafA = new StaticRecursiveSplineNode(0.0, 1.5, null, null);
        StaticRecursiveSplineNode leafB = new StaticRecursiveSplineNode(0.0, 3.5, null, null);
        StaticRecursiveSplineNode leafC = new StaticRecursiveSplineNode(0.0, 7.5, null, null);
        StaticRecursiveSplineNode mid = new StaticRecursiveSplineNode(10.0, 0.0, leafB, leafC);
        StaticRecursiveSplineNode root = new StaticRecursiveSplineNode(0.0, 0.0, leafA, mid);

        ConceptModule splineMod = BytecodeSsaLifter.liftInstanceGraph(root, "eval", "(DDD)D");
        assertTrue(splineMod.isPureCompute(), "Static recursive spline must lift purely: " + splineMod.unmappedReasons());
        assertTrue(splineMod.unmappedReasons().isEmpty());
        JavaConceptBackendEmitter.Double3DEvaluator splineEval = JavaConceptBackendEmitter.compileToDouble3D(splineMod);
        assertEquals(1.5, splineEval.evaluate(-2, 0, 0), 1e-9);
        assertEquals(3.5, splineEval.evaluate(4, 0, 2), 1e-9);
        assertEquals(7.5, splineEval.evaluate(12, 0, 4), 1e-9);

        // 2. Subclass of java.util.AbstractMap must not throw InaccessibleObjectException on JDK superclass fields
        CustomAbstractMapHolder mapHolder = new CustomAbstractMapHolder(2.25);
        ConceptModule mapMod = BytecodeSsaLifter.liftInstanceGraph(mapHolder, "compute", "(DDD)D");
        assertTrue(mapMod.isPureCompute(), "AbstractMap subclass must lift cleanly: " + mapMod.unmappedReasons());
        assertEquals(5.25, JavaConceptBackendEmitter.compileToDouble3D(mapMod).evaluate(3, 0, 0), 1e-9);

        // 3. Hidden $$Lambda closure capturing (int, FluidStatus, FluidStatus)
        TestFluidStatus lava = new TestFluidStatus(-54, "minecraft:lava");
        TestFluidStatus water = new TestFluidStatus(63, "minecraft:water");
        TestFluidPicker picker = createStandardTestFluidPicker(63, lava, water);
        BytecodeSsaLifter.ObjectInterner interner = new BytecodeSsaLifter.ObjectInterner() {
            @Override
            public Integer internObject(Object instance) {
                if (instance == lava) return 1;
                if (instance == water) return 2;
                return null;
            }
        };
        ConceptModule lambdaMod = BytecodeSsaLifter.liftInstanceGraph(
                picker, "computeFluid", null, BytecodeSsaLifter.GraphLiftOptions.withInterner(interner));
        assertTrue(lambdaMod.isPureCompute(), "Hidden $$Lambda FluidPicker must lift purely: " + lambdaMod.unmappedReasons());
        assertTrue(lambdaMod.unmappedReasons().isEmpty());
        JavaConceptBackendEmitter.Double3DEvaluator fpEval = JavaConceptBackendEmitter.compileToDouble3D(lambdaMod);
        assertEquals(1.0, fpEval.evaluate(0, -60, 0), 1e-9);
        assertEquals(1.0, fpEval.evaluate(0, -55, 0), 1e-9);
        assertEquals(2.0, fpEval.evaluate(0, -54, 0), 1e-9);
        assertEquals(2.0, fpEval.evaluate(0, 63, 0), 1e-9);
    }

    @Test
    @DisplayName("Direct JVM bytecode emitter and fixed javac path match ConceptIrEvaluator on nested VarDecl, loops, helpers, intrinsics, and 80+ LUTs")
    void directJvmBytecodeAndJavacCompileLargeLutAndNestedVarDeclParity() {
        List<BytecodeConceptIr.ConceptLut> luts = new java.util.ArrayList<>();
        for (int t = 0; t < 80; t++) {
            List<Number> vals = new java.util.ArrayList<>(256);
            for (int i = 0; i < 256; i++) {
                vals.add(((i * (t + 3)) ^ (t * 31)) & 0xFF);
            }
            luts.add(new BytecodeConceptIr.ConceptLut(
                    "test/LargeLut", "p" + t, "lut_p" + t, BytecodeConceptIr.ConceptType.I32, vals));
        }

        // Helper function: double helper(int idx, double scale) using While loop, CompareTemp, MathIntrinsic, LutLoad
        BytecodeConceptIr.ConceptFunction helper = new BytecodeConceptIr.ConceptFunction(
                "test/LargeLut",
                "mixHelper",
                "(ID)D",
                List.of(
                        new BytecodeConceptIr.ConceptParam("idx", BytecodeConceptIr.ConceptType.I32, 0),
                        new BytecodeConceptIr.ConceptParam("scale", BytecodeConceptIr.ConceptType.F64, 1)),
                BytecodeConceptIr.ConceptType.F64,
                java.util.Map.of(
                        "acc", BytecodeConceptIr.ConceptType.F64,
                        "k", BytecodeConceptIr.ConceptType.I32,
                        "tmp", BytecodeConceptIr.ConceptType.F64),
                List.of(
                        new BytecodeConceptIr.ConceptStmt.VarDecl(
                                "acc", BytecodeConceptIr.ConceptType.F64, new BytecodeConceptIr.ConceptExpr.DoubleConst(0.0)),
                        new BytecodeConceptIr.ConceptStmt.VarDecl(
                                "k", BytecodeConceptIr.ConceptType.I32, new BytecodeConceptIr.ConceptExpr.IntConst(0)),
                        new BytecodeConceptIr.ConceptStmt.While(
                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                        BytecodeConceptIr.BinaryOperator.LT,
                                        new BytecodeConceptIr.ConceptExpr.Var("k", BytecodeConceptIr.ConceptType.I32),
                                        new BytecodeConceptIr.ConceptExpr.IntConst(4),
                                        BytecodeConceptIr.ConceptType.BOOL),
                                List.of(
                                        // Nested VarDecl whose name also exists in localTypes (previously broke javac)
                                        new BytecodeConceptIr.ConceptStmt.VarDecl(
                                                "tmp",
                                                BytecodeConceptIr.ConceptType.F64,
                                                new BytecodeConceptIr.ConceptExpr.Cast(
                                                        BytecodeConceptIr.ConceptType.F64,
                                                        new BytecodeConceptIr.ConceptExpr.LutLoad(
                                                                "lut_p79",
                                                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                                        BytecodeConceptIr.BinaryOperator.BIT_AND,
                                                                        new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                                                BytecodeConceptIr.BinaryOperator.ADD,
                                                                                new BytecodeConceptIr.ConceptExpr.Var("idx", BytecodeConceptIr.ConceptType.I32),
                                                                                new BytecodeConceptIr.ConceptExpr.Var("k", BytecodeConceptIr.ConceptType.I32),
                                                                                BytecodeConceptIr.ConceptType.I32),
                                                                        new BytecodeConceptIr.ConceptExpr.IntConst(255),
                                                                        BytecodeConceptIr.ConceptType.I32),
                                                                BytecodeConceptIr.ConceptType.I32))),
                                        new BytecodeConceptIr.ConceptStmt.Assign(
                                                "acc",
                                                BytecodeConceptIr.ConceptType.F64,
                                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                        BytecodeConceptIr.BinaryOperator.ADD,
                                                        new BytecodeConceptIr.ConceptExpr.Var("acc", BytecodeConceptIr.ConceptType.F64),
                                                        new BytecodeConceptIr.ConceptExpr.MathIntrinsic(
                                                                BytecodeConceptIr.MathIntrinsicKind.FLOOR,
                                                                List.of(new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                                        BytecodeConceptIr.BinaryOperator.MUL,
                                                                        new BytecodeConceptIr.ConceptExpr.Var("tmp", BytecodeConceptIr.ConceptType.F64),
                                                                        new BytecodeConceptIr.ConceptExpr.Var("scale", BytecodeConceptIr.ConceptType.F64),
                                                                        BytecodeConceptIr.ConceptType.F64)),
                                                                BytecodeConceptIr.ConceptType.F64),
                                                        BytecodeConceptIr.ConceptType.F64)),
                                        new BytecodeConceptIr.ConceptStmt.Assign(
                                                "k",
                                                BytecodeConceptIr.ConceptType.I32,
                                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                        BytecodeConceptIr.BinaryOperator.ADD,
                                                        new BytecodeConceptIr.ConceptExpr.Var("k", BytecodeConceptIr.ConceptType.I32),
                                                        new BytecodeConceptIr.ConceptExpr.IntConst(1),
                                                        BytecodeConceptIr.ConceptType.I32)))),
                        new BytecodeConceptIr.ConceptStmt.Return(
                                new BytecodeConceptIr.ConceptExpr.Var("acc", BytecodeConceptIr.ConceptType.F64))),
                true);

        // Entry function: double eval(int x, int y, int z) using nested If VarDecl, Ternary, LogicalAnd/Or, helper Call
        BytecodeConceptIr.ConceptFunction entry = new BytecodeConceptIr.ConceptFunction(
                "test/LargeLut",
                "eval",
                "(III)D",
                List.of(
                        new BytecodeConceptIr.ConceptParam("x", BytecodeConceptIr.ConceptType.I32, 0),
                        new BytecodeConceptIr.ConceptParam("y", BytecodeConceptIr.ConceptType.I32, 1),
                        new BytecodeConceptIr.ConceptParam("z", BytecodeConceptIr.ConceptType.I32, 2)),
                BytecodeConceptIr.ConceptType.F64,
                java.util.Map.of(
                        "branchVal", BytecodeConceptIr.ConceptType.F64,
                        "base", BytecodeConceptIr.ConceptType.I32),
                List.of(
                        new BytecodeConceptIr.ConceptStmt.VarDecl(
                                "base",
                                BytecodeConceptIr.ConceptType.I32,
                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                        BytecodeConceptIr.BinaryOperator.BIT_AND,
                                        new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                BytecodeConceptIr.BinaryOperator.ADD,
                                                new BytecodeConceptIr.ConceptExpr.Var("x", BytecodeConceptIr.ConceptType.I32),
                                                new BytecodeConceptIr.ConceptExpr.Var("z", BytecodeConceptIr.ConceptType.I32),
                                                BytecodeConceptIr.ConceptType.I32),
                                        new BytecodeConceptIr.ConceptExpr.IntConst(255),
                                        BytecodeConceptIr.ConceptType.I32)),
                        new BytecodeConceptIr.ConceptStmt.If(
                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                        BytecodeConceptIr.BinaryOperator.LOGICAL_AND,
                                        new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                BytecodeConceptIr.BinaryOperator.GT,
                                                new BytecodeConceptIr.ConceptExpr.Var("y", BytecodeConceptIr.ConceptType.I32),
                                                new BytecodeConceptIr.ConceptExpr.IntConst(0),
                                                BytecodeConceptIr.ConceptType.BOOL),
                                        new BytecodeConceptIr.ConceptExpr.UnaryOp(
                                                BytecodeConceptIr.UnaryOperator.LOGICAL_NOT,
                                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                        BytecodeConceptIr.BinaryOperator.EQ,
                                                        new BytecodeConceptIr.ConceptExpr.Var("x", BytecodeConceptIr.ConceptType.I32),
                                                        new BytecodeConceptIr.ConceptExpr.IntConst(999),
                                                        BytecodeConceptIr.ConceptType.BOOL),
                                                BytecodeConceptIr.ConceptType.BOOL),
                                        BytecodeConceptIr.ConceptType.BOOL),
                                List.of(new BytecodeConceptIr.ConceptStmt.VarDecl(
                                        "branchVal",
                                        BytecodeConceptIr.ConceptType.F64,
                                        new BytecodeConceptIr.ConceptExpr.Call(
                                                "test/LargeLut",
                                                "mixHelper",
                                                "(ID)D",
                                                List.of(
                                                        new BytecodeConceptIr.ConceptExpr.Var("base", BytecodeConceptIr.ConceptType.I32),
                                                        new BytecodeConceptIr.ConceptExpr.DoubleConst(0.25)),
                                                BytecodeConceptIr.ConceptType.F64))),
                                List.of(new BytecodeConceptIr.ConceptStmt.VarDecl(
                                        "branchVal",
                                        BytecodeConceptIr.ConceptType.F64,
                                        new BytecodeConceptIr.ConceptExpr.Ternary(
                                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                        BytecodeConceptIr.BinaryOperator.LE,
                                                        new BytecodeConceptIr.ConceptExpr.Var("y", BytecodeConceptIr.ConceptType.I32),
                                                        new BytecodeConceptIr.ConceptExpr.IntConst(-10),
                                                        BytecodeConceptIr.ConceptType.BOOL),
                                                new BytecodeConceptIr.ConceptExpr.DoubleConst(-7.5),
                                                new BytecodeConceptIr.ConceptExpr.Cast(
                                                        BytecodeConceptIr.ConceptType.F64,
                                                        new BytecodeConceptIr.ConceptExpr.LutLoad(
                                                                "lut_p0",
                                                                new BytecodeConceptIr.ConceptExpr.Var("base", BytecodeConceptIr.ConceptType.I32),
                                                                BytecodeConceptIr.ConceptType.I32)),
                                                BytecodeConceptIr.ConceptType.F64)))),
                        new BytecodeConceptIr.ConceptStmt.Return(
                                new BytecodeConceptIr.ConceptExpr.Var("branchVal", BytecodeConceptIr.ConceptType.F64))),
                true);

        ConceptModule mod = new ConceptModule(
                "LargeLutNestedVarDeclStress",
                luts,
                List.of(helper),
                entry,
                null,
                null,
                List.of());

        JavaConceptBackendEmitter.Double3DEvaluator directBc =
                JvmBytecodeConceptEmitter.assembleToDouble3D(mod, "stress80lut");
        JavaConceptBackendEmitter.Double3DEvaluator javacEval =
                JavaConceptBackendEmitter.compileToDouble3DViaJavac(mod, "stress80lut_javac");
        assertNotNull(javacEval, "Fixed JavaConceptBackendEmitter javac path must compile 80 LUTs + nested VarDecl");

        int[][] coords = {
            {0, 10, 0},
            {17, 64, -23},
            {-5, 0, 12},
            {100, -10, 44},
            {999, 5, 3},
            {-128, -50, 255}
        };
        for (int[] c : coords) {
            double expected = ((Number) ConceptIrEvaluator.evaluateEntry(mod, c[0], c[1], c[2])).doubleValue();
            double actualBc = directBc.evaluate(c[0], c[1], c[2]);
            double actualJavac = javacEval.evaluate(c[0], c[1], c[2]);
            assertEquals(expected, actualBc, 0.0, "Direct JVM bytecode mismatch at " + java.util.Arrays.toString(c));
            assertEquals(expected, actualJavac, 0.0, "Fixed javac mismatch at " + java.util.Arrays.toString(c));
        }
    }

    @Test
    @DisplayName("Pure Call CSE deduplicates shared 2D helper calls and evalColumnY hoists Y-invariant prefix with 0.0 ULP parity")
    void pureCallCseAndColumnYHoistingExactParity() {
        // Pure 2D helper that cannot be inlined (contains Return inside While) so it remains a Call node
        BytecodeConceptIr.ConceptFunction helper2d = new BytecodeConceptIr.ConceptFunction(
                "test/ColumnHoist",
                "noise2d",
                "(II)D",
                List.of(
                        new BytecodeConceptIr.ConceptParam("x", BytecodeConceptIr.ConceptType.I32, 0),
                        new BytecodeConceptIr.ConceptParam("z", BytecodeConceptIr.ConceptType.I32, 1)),
                BytecodeConceptIr.ConceptType.F64,
                java.util.Map.of("k", BytecodeConceptIr.ConceptType.I32, "acc", BytecodeConceptIr.ConceptType.F64),
                List.of(
                        new BytecodeConceptIr.ConceptStmt.VarDecl("k", BytecodeConceptIr.ConceptType.I32, new BytecodeConceptIr.ConceptExpr.IntConst(0)),
                        new BytecodeConceptIr.ConceptStmt.VarDecl("acc", BytecodeConceptIr.ConceptType.F64, new BytecodeConceptIr.ConceptExpr.DoubleConst(0.0)),
                        new BytecodeConceptIr.ConceptStmt.While(
                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                        BytecodeConceptIr.BinaryOperator.LT,
                                        new BytecodeConceptIr.ConceptExpr.Var("k", BytecodeConceptIr.ConceptType.I32),
                                        new BytecodeConceptIr.ConceptExpr.IntConst(4),
                                        BytecodeConceptIr.ConceptType.BOOL),
                                List.of(
                                        new BytecodeConceptIr.ConceptStmt.Assign(
                                                "acc",
                                                BytecodeConceptIr.ConceptType.F64,
                                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                        BytecodeConceptIr.BinaryOperator.ADD,
                                                        new BytecodeConceptIr.ConceptExpr.Var("acc", BytecodeConceptIr.ConceptType.F64),
                                                        new BytecodeConceptIr.ConceptExpr.Cast(
                                                                BytecodeConceptIr.ConceptType.F64,
                                                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                                        BytecodeConceptIr.BinaryOperator.ADD,
                                                                        new BytecodeConceptIr.ConceptExpr.Var("x", BytecodeConceptIr.ConceptType.I32),
                                                                        new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                                                BytecodeConceptIr.BinaryOperator.MUL,
                                                                                new BytecodeConceptIr.ConceptExpr.Var("z", BytecodeConceptIr.ConceptType.I32),
                                                                                new BytecodeConceptIr.ConceptExpr.Var("k", BytecodeConceptIr.ConceptType.I32),
                                                                                BytecodeConceptIr.ConceptType.I32),
                                                                        BytecodeConceptIr.ConceptType.I32)),
                                                        BytecodeConceptIr.ConceptType.F64)),
                                        new BytecodeConceptIr.ConceptStmt.If(
                                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                        BytecodeConceptIr.BinaryOperator.EQ,
                                                        new BytecodeConceptIr.ConceptExpr.Var("k", BytecodeConceptIr.ConceptType.I32),
                                                        new BytecodeConceptIr.ConceptExpr.IntConst(3),
                                                        BytecodeConceptIr.ConceptType.BOOL),
                                                List.of(new BytecodeConceptIr.ConceptStmt.Return(
                                                        new BytecodeConceptIr.ConceptExpr.Var("acc", BytecodeConceptIr.ConceptType.F64))),
                                                List.of()),
                                        new BytecodeConceptIr.ConceptStmt.Assign(
                                                "k",
                                                BytecodeConceptIr.ConceptType.I32,
                                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                        BytecodeConceptIr.BinaryOperator.ADD,
                                                        new BytecodeConceptIr.ConceptExpr.Var("k", BytecodeConceptIr.ConceptType.I32),
                                                        new BytecodeConceptIr.ConceptExpr.IntConst(1),
                                                        BytecodeConceptIr.ConceptType.I32)))),
                        new BytecodeConceptIr.ConceptStmt.Return(
                                new BytecodeConceptIr.ConceptExpr.Var("acc", BytecodeConceptIr.ConceptType.F64))),
                true);

        // Entry calls noise2d(x, z) twice (CSE candidate) and combines with Y-dependent term
        BytecodeConceptIr.ConceptExpr call2d = new BytecodeConceptIr.ConceptExpr.Call(
                "test/ColumnHoist",
                "noise2d",
                "(II)D",
                List.of(
                        new BytecodeConceptIr.ConceptExpr.Var("x", BytecodeConceptIr.ConceptType.I32),
                        new BytecodeConceptIr.ConceptExpr.Var("z", BytecodeConceptIr.ConceptType.I32)),
                BytecodeConceptIr.ConceptType.F64);
        BytecodeConceptIr.ConceptFunction entry = new BytecodeConceptIr.ConceptFunction(
                "test/ColumnHoist",
                "eval",
                "(III)D",
                List.of(
                        new BytecodeConceptIr.ConceptParam("x", BytecodeConceptIr.ConceptType.I32, 0),
                        new BytecodeConceptIr.ConceptParam("y", BytecodeConceptIr.ConceptType.I32, 1),
                        new BytecodeConceptIr.ConceptParam("z", BytecodeConceptIr.ConceptType.I32, 2)),
                BytecodeConceptIr.ConceptType.F64,
                java.util.Map.of("a", BytecodeConceptIr.ConceptType.F64, "b", BytecodeConceptIr.ConceptType.F64),
                List.of(
                        new BytecodeConceptIr.ConceptStmt.Assign("a", BytecodeConceptIr.ConceptType.F64, call2d),
                        new BytecodeConceptIr.ConceptStmt.Assign(
                                "b",
                                BytecodeConceptIr.ConceptType.F64,
                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                        BytecodeConceptIr.BinaryOperator.MUL,
                                        call2d,
                                        new BytecodeConceptIr.ConceptExpr.DoubleConst(0.5),
                                        BytecodeConceptIr.ConceptType.F64)),
                        new BytecodeConceptIr.ConceptStmt.Return(
                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                        BytecodeConceptIr.BinaryOperator.SUB,
                                        new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                BytecodeConceptIr.BinaryOperator.ADD,
                                                new BytecodeConceptIr.ConceptExpr.Var("a", BytecodeConceptIr.ConceptType.F64),
                                                new BytecodeConceptIr.ConceptExpr.Var("b", BytecodeConceptIr.ConceptType.F64),
                                                BytecodeConceptIr.ConceptType.F64),
                                        new BytecodeConceptIr.ConceptExpr.Cast(
                                                BytecodeConceptIr.ConceptType.F64,
                                                new BytecodeConceptIr.ConceptExpr.Var("y", BytecodeConceptIr.ConceptType.I32)),
                                        BytecodeConceptIr.ConceptType.F64))),
                true);

        ConceptModule raw = new ConceptModule(
                "PureCallCseColumnHoist",
                List.of(),
                List.of(helper2d),
                entry,
                null,
                null,
                List.of());
        ConceptModule optimized = ConceptOptimizerPass.optimize(raw);

        // Verify pure Call CSE collapsed the 2 duplicate calls to noise2d(x, z) into 1
        String javaSrc = new JavaConceptBackendEmitter()
                .emit(optimized, ConceptBackendEmitter.EmitOptions.defaults())
                .sourceCode();
        int callOccurrences = javaSrc.split("noise2d\\(", -1).length - 1;
        assertEquals(2, callOccurrences, "Expected 1 declaration + 1 CSE-deduplicated call to noise2d");

        // Verify JvmBytecodeConceptEmitter emits evalColumnY and matches ConceptIrEvaluator bit-for-bit
        JvmBytecodeConceptEmitter.AssembledClass assembled =
                JvmBytecodeConceptEmitter.assembleClass(optimized, "io/github/dailystruggle/bytecode/transpile/gen/IrBc_ColTest");
        assertTrue(assembled.hasColumnYMethod(), "Expected evalColumnY to be emitted for Y-invariant prefix");

        JavaConceptBackendEmitter.Double3DEvaluator eval =
                JvmBytecodeConceptEmitter.assembleToDouble3D(optimized, "col_hoist_test");
        float[] colOut = new float[20];
        int baseY = -64;
        int stepY = 8;
        int countY = 16;
        int offset = 2;
        eval.evaluateColumnY(13, baseY, stepY, countY, -7, colOut, offset);
        for (int i = 0; i < countY; i++) {
            int y = baseY + i * stepY;
            float expected = (float) ((Number) ConceptIrEvaluator.evaluateEntry(raw, 13, y, -7)).doubleValue();
            assertEquals(expected, colOut[offset + i], 0.0f, "ColumnY mismatch at i=" + i + " y=" + y);
        }

        // Also verify non-contiguous hoisting when a Y-dependent statement precedes 2D statements and nested Calls.
        BytecodeConceptIr.ConceptFunction nonContigEntry = new BytecodeConceptIr.ConceptFunction(
                "test/ColumnHoist",
                "eval",
                "(III)D",
                List.of(
                        new BytecodeConceptIr.ConceptParam("x", BytecodeConceptIr.ConceptType.I32, 0),
                        new BytecodeConceptIr.ConceptParam("y", BytecodeConceptIr.ConceptType.I32, 1),
                        new BytecodeConceptIr.ConceptParam("z", BytecodeConceptIr.ConceptType.I32, 2)),
                BytecodeConceptIr.ConceptType.F64,
                java.util.Map.of(
                        "yScaled", BytecodeConceptIr.ConceptType.F64,
                        "late2d", BytecodeConceptIr.ConceptType.F64),
                List.of(
                        new BytecodeConceptIr.ConceptStmt.Assign(
                                "yScaled",
                                BytecodeConceptIr.ConceptType.F64,
                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                        BytecodeConceptIr.BinaryOperator.MUL,
                                        new BytecodeConceptIr.ConceptExpr.Cast(
                                                BytecodeConceptIr.ConceptType.F64,
                                                new BytecodeConceptIr.ConceptExpr.Var("y", BytecodeConceptIr.ConceptType.I32)),
                                        new BytecodeConceptIr.ConceptExpr.DoubleConst(0.25),
                                        BytecodeConceptIr.ConceptType.F64)),
                        new BytecodeConceptIr.ConceptStmt.Assign("late2d", BytecodeConceptIr.ConceptType.F64, call2d),
                        new BytecodeConceptIr.ConceptStmt.Return(
                                new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                        BytecodeConceptIr.BinaryOperator.ADD,
                                        new BytecodeConceptIr.ConceptExpr.Var("yScaled", BytecodeConceptIr.ConceptType.F64),
                                        new BytecodeConceptIr.ConceptExpr.BinaryOp(
                                                BytecodeConceptIr.BinaryOperator.ADD,
                                                new BytecodeConceptIr.ConceptExpr.Var("late2d", BytecodeConceptIr.ConceptType.F64),
                                                call2d,
                                                BytecodeConceptIr.ConceptType.F64),
                                        BytecodeConceptIr.ConceptType.F64))),
                true);
        ConceptModule nonContigMod = new ConceptModule(
                "NonContigColumnHoist", List.of(), List.of(helper2d), nonContigEntry, null, null, List.of());
        JvmBytecodeConceptEmitter.AssembledClass ncAssembled = JvmBytecodeConceptEmitter.assembleClass(
                nonContigMod, "io/github/dailystruggle/bytecode/transpile/gen/IrBc_NcColTest");
        assertTrue(ncAssembled.hasColumnYMethod(), "Expected evalColumnY for non-contiguous Y-invariant statements/calls");
        JavaConceptBackendEmitter.Double3DEvaluator ncEval =
                JvmBytecodeConceptEmitter.assembleToDouble3D(nonContigMod, "nc_col_hoist_test");
        float[] ncOut = new float[20];
        ncEval.evaluateColumnY(19, baseY, stepY, countY, 11, ncOut, offset);
        for (int i = 0; i < countY; i++) {
            int y = baseY + i * stepY;
            float expected = (float) ((Number) ConceptIrEvaluator.evaluateEntry(nonContigMod, 19, y, 11)).doubleValue();
            assertEquals(expected, ncOut[offset + i], 0.0f, "Non-contiguous ColumnY mismatch at i=" + i + " y=" + y);
        }
    }
}
