package io.github.dailystruggle.bytecode;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Zero-classloading JVM {@code .class} constant-pool and bytecode scanner that discovers
 * configuration key literals read by plugin classes ({@code READ_BY_CLASS} AST edges) and
 * {@code Enum} constant sets without triggering static initializers or Bukkit classloader side
 * effects ({@code leafconfig-ADR-001} Section 1 &amp; Section 2).
 *
 * @governedBy ADR-115
 * @governedBy ADR-126
 * @governedBy leafconfig-ADR-001
 * @trace REQ-CFG-ATLAS-001
 */
public final class BytecodeConfigScanner {

    private static final int CLASS_MAGIC = 0xCAFEBABE;

    // Constant pool tags (JVMS §4.4)
    private static final int CONSTANT_Utf8 = 1;
    private static final int CONSTANT_Integer = 3;
    private static final int CONSTANT_Float = 4;
    private static final int CONSTANT_Long = 5;
    private static final int CONSTANT_Double = 6;
    private static final int CONSTANT_Class = 7;
    private static final int CONSTANT_String = 8;
    private static final int CONSTANT_Fieldref = 9;
    private static final int CONSTANT_Methodref = 10;
    private static final int CONSTANT_InterfaceMethodref = 11;
    private static final int CONSTANT_NameAndType = 12;
    private static final int CONSTANT_MethodHandle = 15;
    private static final int CONSTANT_MethodType = 16;
    private static final int CONSTANT_Dynamic = 17;
    private static final int CONSTANT_InvokeDynamic = 18;
    private static final int CONSTANT_Module = 19;
    private static final int CONSTANT_Package = 20;

    // Class / field access flags
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_FINAL = 0x0010;
    private static final int ACC_ENUM = 0x4000;

    /** Known configuration getter method names across Bukkit/Paper, Bungee, Velocity, NightConfig, Mojang Codec, and RTP. */
    private static final Set<String> CONFIG_GETTER_METHODS = Set.of(
            "getBoolean", "getInt", "getLong", "getDouble", "getString",
            "getStringList", "getIntegerList", "getDoubleList", "getFloatList",
            "getMapList", "getList", "getConfigurationSection", "createSection",
            "getObject", "isSet", "contains",
            "getConfigValue", "getNumber", "getOrDefault", "node",
            "getDuration", "getData", "resolveScalar", "parse",
            "parseSecondsDurationMillis", "parseDurationSetting",
            "resolveCapacity", "resolveCapacityInt", "getProperty",
            "fieldOf", "optionalFieldOf"
    );

    /** Zero-arg methods that are generic JDK/framework helpers rather than config property accessors. */
    private static final Set<String> IGNORED_ZERO_ARG_ACCESSORS = Set.of(
            "toString", "hashCode", "length", "size", "isEmpty", "isBlank", "trim",
            "name", "ordinal", "values", "get", "iterator", "hasNext", "next",
            "longValue", "intValue", "doubleValue", "floatValue", "booleanValue",
            "getConfig", "getUniqueId", "getPlayer", "getWorld", "getName", "getUID",
            "getFrom", "getTo", "getBlockX", "getBlockY", "getBlockZ", "getX", "getY", "getZ",
            "current", "getInstance", "serverAccessor", "run", "clear", "close"
    );

    /** Pattern matching plausible YAML/TOML dotted or hyphenated configuration key literals. */
    private static final Pattern PLAUSIBLE_CONFIG_KEY = Pattern.compile(
            "^[a-zA-Z][a-zA-Z0-9_-]{1,48}(?:\\.[a-zA-Z0-9_-]{1,48}){0,8}$"
    );

    /** Common non-config string literals to exclude when scanning constant pools. */
    private static final Set<String> IGNORED_LITERALS = Set.of(
            "true", "false", "null", "init", "clinit", "Code", "LineNumberTable",
            "LocalVariableTable", "StackMapTable", "Exceptions", "Signature",
            "SourceFile", "InnerClasses", "NestHost", "NestMembers", "Record",
            "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations", "BootstrapMethods",
            "valueOf", "values", "name", "ordinal", "toString", "equals", "hashCode"
    );

    /**
     * Method-scoped AST/bytecode call site where a configuration key is read or used.
     *
     * @param className          enclosing binary class name
     * @param methodName         enclosing method name (e.g. {@code loadSettings}, {@code onEntityDamage})
     * @param getterMethod       invoked config getter (e.g. {@code getInt}, {@code getDouble}, {@code getBoolean}, {@code getString})
     * @param codeFallbackValue  hardcoded fallback default passed to 2-arg getter (e.g. {@code getInt("key", 20)}), or {@code null}
     * @param assignedField      class field or record component name written/bound, or {@code null}
     * @param unitScalingHint    detected arithmetic scaling in method body (e.g. {@code *20_TICKS}, {@code *16_BLOCKS}, {@code *1000_MS}), or {@code null}
     * @param downstreamMethods  methods in the same class/JAR that read {@code assignedField} via {@code getfield}/{@code getstatic} or record accessors
     * @param subsystemApis      Bukkit/Minecraft/engine subsystem APIs invoked by the reader or downstream methods
     * @param guardPredicates    bytecode comparison, clamp, or threshold guards governing this key (e.g. {@code clamp >= 1.0}, {@code dist <= wakeRadiusBlocks})
     */
    public record KeyCallSite(
            String className,
            String methodName,
            String getterMethod,
            String codeFallbackValue,
            String assignedField,
            String unitScalingHint,
            List<String> downstreamMethods,
            List<String> subsystemApis,
            List<String> guardPredicates
    ) {
        public KeyCallSite(
                String className,
                String methodName,
                String getterMethod,
                String codeFallbackValue,
                String assignedField,
                String unitScalingHint,
                List<String> downstreamMethods,
                List<String> subsystemApis
        ) {
            this(
                    className,
                    methodName,
                    getterMethod,
                    codeFallbackValue,
                    assignedField,
                    unitScalingHint,
                    downstreamMethods,
                    subsystemApis,
                    List.of()
            );
        }

        public KeyCallSite {
            Objects.requireNonNull(className, "className");
            methodName = methodName == null ? "<unknown>" : methodName;
            getterMethod = getterMethod == null ? "get" : getterMethod;
            downstreamMethods = downstreamMethods == null ? List.of() : List.copyOf(downstreamMethods);
            subsystemApis = subsystemApis == null ? List.of() : List.copyOf(subsystemApis);
            guardPredicates = guardPredicates == null ? List.of() : List.copyOf(guardPredicates);
        }
    }

    /**
     * Immutable scan result for a single {@code .class} file.
     *
     * @param className             fully-qualified binary class name (e.g. {@code com.example.MyPlugin})
     * @param isEnum                whether the class is a Java {@code enum}
     * @param enumConstants         declared {@code ACC_ENUM} constant names when {@code isEnum} is true
     * @param referencedConfigKeys  configuration key literals referenced in constant pool alongside config getters
     * @param referencedEnumTypes   binary class names of enums referenced via {@code valueOf(String)}
     * @param hasSaveConfigOnDisable whether the class defines {@code onDisable} and invokes {@code saveConfig}
     * @param keyCallSites          map of config key literal to method-scoped bytecode call sites in this class
     * @param symbolReaders         map of field/accessor name to reading {@code Class#method} descriptors in this class
     * @param methodSubsystems      map of {@code Class#method} to detected subsystem API tags in this class
     * @param symbolGuards          map of field/accessor name to bytecode guard predicates in this class
     */
    public record ClassScanResult(
            String className,
            boolean isEnum,
            List<String> enumConstants,
            Set<String> referencedConfigKeys,
            Set<String> referencedEnumTypes,
            boolean hasSaveConfigOnDisable,
            Map<String, List<KeyCallSite>> keyCallSites,
            Map<String, Set<String>> symbolReaders,
            Map<String, Set<String>> methodSubsystems,
            Map<String, List<String>> symbolGuards
    ) {
        public ClassScanResult(
                String className,
                boolean isEnum,
                List<String> enumConstants,
                Set<String> referencedConfigKeys,
                Set<String> referencedEnumTypes,
                boolean hasSaveConfigOnDisable
        ) {
            this(className, isEnum, enumConstants, referencedConfigKeys, referencedEnumTypes, hasSaveConfigOnDisable, Map.of(), Map.of(), Map.of(), Map.of());
        }

        public ClassScanResult(
                String className,
                boolean isEnum,
                List<String> enumConstants,
                Set<String> referencedConfigKeys,
                Set<String> referencedEnumTypes,
                boolean hasSaveConfigOnDisable,
                Map<String, List<KeyCallSite>> keyCallSites
        ) {
            this(className, isEnum, enumConstants, referencedConfigKeys, referencedEnumTypes, hasSaveConfigOnDisable, keyCallSites, Map.of(), Map.of(), Map.of());
        }

        public ClassScanResult {
            Objects.requireNonNull(className, "className");
            enumConstants = enumConstants == null ? List.of() : List.copyOf(enumConstants);
            referencedConfigKeys = referencedConfigKeys == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(referencedConfigKeys));
            referencedEnumTypes = referencedEnumTypes == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(referencedEnumTypes));
            keyCallSites = keyCallSites == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(keyCallSites));
            symbolReaders = symbolReaders == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(symbolReaders));
            methodSubsystems = methodSubsystems == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(methodSubsystems));
            symbolGuards = symbolGuards == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(symbolGuards));
        }
    }

    private BytecodeConfigScanner() {
    }

    /**
     * Parses a raw JVM {@code .class} byte array without loading the class into any {@link ClassLoader}.
     *
     * @param classBytes raw classfile bytes
     * @return extracted scan result, or {@code null} if {@code classBytes} is not a valid classfile
     */
    public static ClassScanResult scanClassBytes(byte[] classBytes) {
        if (classBytes == null || classBytes.length < 24) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            int magic = in.readInt();
            if (magic != CLASS_MAGIC) {
                return null;
            }
            in.readUnsignedShort(); // minor_version
            in.readUnsignedShort(); // major_version
            int cpCount = in.readUnsignedShort();
            if (cpCount <= 1 || cpCount > 65535) {
                return null;
            }

            String[] utf8Pool = new String[cpCount];
            String[] numericConstantPool = new String[cpCount];
            int[] classNameIdx = new int[cpCount];
            int[] stringUtf8Idx = new int[cpCount];
            int[] fieldClassIdx = new int[cpCount];
            int[] fieldNatIdx = new int[cpCount];
            int[] methodClassIdx = new int[cpCount];
            int[] methodNatIdx = new int[cpCount];
            int[] natNameIdx = new int[cpCount];
            int[] natDescIdx = new int[cpCount];

            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case CONSTANT_Utf8 -> utf8Pool[i] = in.readUTF();
                    case CONSTANT_Integer -> numericConstantPool[i] = Integer.toString(in.readInt());
                    case CONSTANT_Float -> numericConstantPool[i] = Float.toString(in.readFloat());
                    case CONSTANT_Long -> {
                        numericConstantPool[i] = Long.toString(in.readLong());
                        i++; // 8-byte constants occupy two constant-pool slots
                    }
                    case CONSTANT_Double -> {
                        numericConstantPool[i] = Double.toString(in.readDouble());
                        i++; // 8-byte constants occupy two constant-pool slots
                    }
                    case CONSTANT_Class -> classNameIdx[i] = in.readUnsignedShort();
                    case CONSTANT_String -> stringUtf8Idx[i] = in.readUnsignedShort();
                    case CONSTANT_Fieldref -> {
                        fieldClassIdx[i] = in.readUnsignedShort();
                        fieldNatIdx[i] = in.readUnsignedShort();
                    }
                    case CONSTANT_Methodref, CONSTANT_InterfaceMethodref -> {
                        methodClassIdx[i] = in.readUnsignedShort();
                        methodNatIdx[i] = in.readUnsignedShort();
                    }
                    case CONSTANT_NameAndType -> {
                        natNameIdx[i] = in.readUnsignedShort();
                        natDescIdx[i] = in.readUnsignedShort();
                    }
                    case CONSTANT_MethodHandle -> in.skipBytes(3);
                    case CONSTANT_MethodType, CONSTANT_Module, CONSTANT_Package -> in.skipBytes(2);
                    case CONSTANT_Dynamic, CONSTANT_InvokeDynamic -> in.skipBytes(4);
                    default -> {
                        return null;
                    }
                }
            }

            int accessFlags = in.readUnsignedShort();
            int thisClass = in.readUnsignedShort();
            int superClass = in.readUnsignedShort();

            String internalName = resolveClassName(thisClass, classNameIdx, utf8Pool);
            if (internalName == null || internalName.isEmpty()) {
                return null;
            }
            String binaryClassName = internalName.replace('/', '.');
            String superInternal = resolveClassName(superClass, classNameIdx, utf8Pool);
            boolean isEnum = (accessFlags & ACC_ENUM) != 0 || "java/lang/Enum".equals(superInternal);

              int interfacesCount = in.readUnsignedShort();
              in.skipBytes(interfacesCount * 2);

              // Scan fields for ACC_ENUM constants and POJO/annotation config bindings
              int fieldsCount = in.readUnsignedShort();
              List<String> enumConstants = new ArrayList<>();
              List<RawFieldDescriptor> rawFields = new ArrayList<>();
              for (int f = 0; f < fieldsCount; f++) {
                  int fieldFlags = in.readUnsignedShort();
                  int nameIndex = in.readUnsignedShort();
                  int descIndex = in.readUnsignedShort();
                  String fName = safeUtf8(utf8Pool, nameIndex);
                  String fDesc = safeUtf8(utf8Pool, descIndex);
                  List<String> fieldAnnotationKeys = new ArrayList<>();
                  boolean hasConfigFieldAnnotation = false;
                  int attrsCount = in.readUnsignedShort();
                  for (int a = 0; a < attrsCount; a++) {
                      int attrNameIdx = in.readUnsignedShort();
                      int attrLen = in.readInt();
                      String attrName = safeUtf8(utf8Pool, attrNameIdx);
                      if (("RuntimeVisibleAnnotations".equals(attrName) || "RuntimeInvisibleAnnotations".equals(attrName))
                              && attrLen >= 2 && attrLen <= 65_536) {
                          byte[] attrBytes = new byte[attrLen];
                          in.readFully(attrBytes);
                          if (parseAnnotationsAttribute(attrBytes, utf8Pool, fieldAnnotationKeys)) {
                              hasConfigFieldAnnotation = true;
                          }
                      } else if (attrLen > 0) {
                          in.skipBytes(attrLen);
                      }
                  }
                  if ((fieldFlags & ACC_ENUM) != 0
                          || (isEnum && (fieldFlags & (ACC_STATIC | ACC_FINAL)) == (ACC_STATIC | ACC_FINAL))) {
                      if (fName != null && !fName.startsWith("$VALUES") && !fName.startsWith("ENUM$VALUES")) {
                          enumConstants.add(fName);
                      }
                  } else if (!isEnum && fName != null && fDesc != null
                          && (fieldFlags & ACC_STATIC) == 0
                          && (fieldFlags & 0x1000 /* ACC_SYNTHETIC */) == 0
                          && !fName.startsWith("this$")) {
                      rawFields.add(new RawFieldDescriptor(fName, fDesc, hasConfigFieldAnnotation, fieldAnnotationKeys));
                  }
              }

              // Scan methods and their Code attributes for call-site AST extraction
              boolean hasOnDisable = false;
              int methodsCount = in.readUnsignedShort();
              Map<String, byte[]> methodCodeByName = new LinkedHashMap<>();
              for (int m = 0; m < methodsCount; m++) {
                  in.readUnsignedShort(); // method_flags
                  int mNameIdx = in.readUnsignedShort();
                  in.readUnsignedShort(); // descriptor_index
                  String mName = safeUtf8(utf8Pool, mNameIdx);
                  if ("onDisable".equals(mName)) {
                      hasOnDisable = true;
                  }
                  int attrsCount = in.readUnsignedShort();
                  for (int a = 0; a < attrsCount; a++) {
                      int attrNameIdx = in.readUnsignedShort();
                      int attrLen = in.readInt();
                      String attrName = safeUtf8(utf8Pool, attrNameIdx);
                      if ("Code".equals(attrName) && attrLen >= 8 && attrLen <= 262_144 && mName != null) {
                          byte[] attrBytes = new byte[attrLen];
                          in.readFully(attrBytes);
                          int codeLen = ((attrBytes[4] & 0xFF) << 24)
                                  | ((attrBytes[5] & 0xFF) << 16)
                                  | ((attrBytes[6] & 0xFF) << 8)
                                  | (attrBytes[7] & 0xFF);
                          if (codeLen > 0 && 8 + codeLen <= attrBytes.length) {
                              byte[] code = new byte[codeLen];
                              System.arraycopy(attrBytes, 8, code, 0, codeLen);
                              String methodKey = methodCodeByName.containsKey(mName) ? (mName + "\0" + m) : mName;
                              methodCodeByName.put(methodKey, code);
                          }
                      } else if (attrLen > 0) {
                          in.skipBytes(attrLen);
                      }
                  }
              }

              // Scan class-level attributes for @ConfigSerializable / @Configuration annotations
              boolean hasClassConfigAnnotation = false;
              if (in.available() >= 2) {
                  int classAttrsCount = in.readUnsignedShort();
                  for (int a = 0; a < classAttrsCount; a++) {
                      if (in.available() < 6) {
                          break;
                      }
                      int attrNameIdx = in.readUnsignedShort();
                      int attrLen = in.readInt();
                      String attrName = safeUtf8(utf8Pool, attrNameIdx);
                      if (("RuntimeVisibleAnnotations".equals(attrName) || "RuntimeInvisibleAnnotations".equals(attrName))
                              && attrLen >= 2 && attrLen <= 65_536 && in.available() >= attrLen) {
                          byte[] attrBytes = new byte[attrLen];
                          in.readFully(attrBytes);
                          if (parseAnnotationsAttribute(attrBytes, utf8Pool, null)) {
                              hasClassConfigAnnotation = true;
                          }
                      } else if (attrLen > 0 && in.available() >= attrLen) {
                          in.skipBytes(attrLen);
                      } else {
                          break;
                      }
                  }
              }

              // Inspect method references for config getters, saveConfig, and Enum.valueOf
              boolean invokesConfigGetter = false;
              boolean invokesSaveConfig = false;
              Set<String> referencedEnums = new LinkedHashSet<>();

              for (int i = 1; i < cpCount; i++) {
                  int nat = methodNatIdx[i];
                  if (nat <= 0 || nat >= cpCount) {
                      continue;
                  }
                  String mName = safeUtf8(utf8Pool, natNameIdx[nat]);
                  String mDesc = safeUtf8(utf8Pool, natDescIdx[nat]);
                  String ownerInternal = resolveClassName(methodClassIdx[i], classNameIdx, utf8Pool);
                  if (mName == null) {
                      continue;
                  }
                  if (CONFIG_GETTER_METHODS.contains(mName)) {
                      invokesConfigGetter = true;
                  }
                  if ("saveConfig".equals(mName)) {
                      invokesSaveConfig = true;
                  }
                  if ("valueOf".equals(mName) && mDesc != null && mDesc.startsWith("(Ljava/lang/String;)L")) {
                      if (ownerInternal != null && !ownerInternal.startsWith("java/lang/")) {
                          referencedEnums.add(ownerInternal.replace('/', '.'));
                      }
                  }
              }

              // Collect CONSTANT_String literals and *Keys/*Params enum field refs when the class interacts with config getters
              Set<String> configKeys = new LinkedHashSet<>();
              if (invokesConfigGetter) {
                  for (int i = 1; i < cpCount; i++) {
                      int utfIdx = stringUtf8Idx[i];
                      if (utfIdx > 0 && utfIdx < cpCount) {
                          String literal = utf8Pool[utfIdx];
                          if (isPlausibleConfigKeyLiteral(literal)) {
                              configKeys.add(literal);
                          }
                      }
                      String enumKey = resolveConfigEnumKey(i, fieldClassIdx, fieldNatIdx, classNameIdx, natNameIdx, utf8Pool);
                      if (enumKey != null) {
                          configKeys.add(enumKey);
                      }
                  }
              }

              MethodFlowScan flowScan = scanMethodFlowsAndCallSites(
                      binaryClassName,
                      invokesConfigGetter,
                      methodCodeByName,
                      utf8Pool,
                      numericConstantPool,
                      classNameIdx,
                      stringUtf8Idx,
                      fieldClassIdx,
                      fieldNatIdx,
                      methodClassIdx,
                      methodNatIdx,
                      natNameIdx,
                      natDescIdx
              );

              Map<String, List<KeyCallSite>> mergedCallSites = new LinkedHashMap<>(flowScan.callSites());
              boolean isConfigPojoClass = hasClassConfigAnnotation || isConfigPojoClassName(internalName, accessFlags, isEnum);
              for (RawFieldDescriptor rf : rawFields) {
                  if (!rf.hasAnnotation() && !isConfigPojoClass) {
                      continue;
                  }
                  Set<String> fieldKeyCandidates = resolvePojoFieldKeys(rf);
                  if (fieldKeyCandidates.isEmpty()) {
                      continue;
                  }
                  String syntheticGetter = mapFieldDescriptorToGetter(rf.descriptor());
                  String candidateEnum = extractCandidateEnumFromFieldDescriptor(rf.descriptor());
                  List<String> fieldGuards = new ArrayList<>();
                  if (candidateEnum != null) {
                      referencedEnums.add(candidateEnum);
                      addUniqueGuard(fieldGuards, "enum: " + candidateEnum);
                  }
                  for (String g : flowScan.symbolGuards().getOrDefault(rf.name(), List.of())) {
                      addUniqueGuard(fieldGuards, g);
                  }
                  Set<String> downstream = new LinkedHashSet<>(flowScan.symbolReaders().getOrDefault(rf.name(), Set.of()));
                  Set<String> subsystems = new LinkedHashSet<>();
                  for (String readerRef : downstream) {
                      int hash = readerRef.indexOf('#');
                      if (hash > 0) {
                          String rMethod = readerRef.substring(hash + 1);
                          subsystems.addAll(flowScan.methodSubsystems().getOrDefault(rMethod, Set.of()));
                      }
                  }
                  KeyCallSite pojoSite = new KeyCallSite(
                          binaryClassName,
                          "<field:" + rf.name() + ">",
                          syntheticGetter,
                          null,
                          rf.name(),
                          null,
                          new ArrayList<>(downstream),
                          new ArrayList<>(subsystems),
                          fieldGuards
                  );
                  for (String fKey : fieldKeyCandidates) {
                      configKeys.add(fKey);
                      mergedCallSites.computeIfAbsent(fKey, k -> new ArrayList<>()).add(pojoSite);
                  }
              }

              return new ClassScanResult(
                      binaryClassName,
                      isEnum,
                      enumConstants,
                      configKeys,
                      referencedEnums,
                      hasOnDisable && invokesSaveConfig,
                      mergedCallSites,
                      flowScan.symbolReaders(),
                      flowScan.methodSubsystems(),
                      flowScan.symbolGuards()
              );
          } catch (IOException | RuntimeException e) {
              return null;
          }
      }

      private record RawFieldDescriptor(
              String name,
              String descriptor,
              boolean hasAnnotation,
              List<String> explicitKeys
      ) {
      }

      private static boolean isConfigPojoClassName(String internalName, int accessFlags, boolean isEnum) {
          if (isEnum || (accessFlags & 0x0200 /* ACC_INTERFACE */) != 0
                  || (accessFlags & 0x2000 /* ACC_ANNOTATION */) != 0
                  || (accessFlags & 0x0400 /* ACC_ABSTRACT */) != 0) {
              return false;
          }
          int slash = internalName.lastIndexOf('/');
          String simple = slash >= 0 ? internalName.substring(slash + 1) : internalName;
          int dollar = simple.lastIndexOf('$');
          if (dollar >= 0 && dollar + 1 < simple.length()) {
              simple = simple.substring(dollar + 1);
          }
          if ("YamlConfig".equals(simple) || "LlmHookConfig".equals(simple)) {
              return false;
          }
          return simple.endsWith("Config") || simple.endsWith("Settings") || simple.endsWith("Configuration");
      }

      private static Set<String> resolvePojoFieldKeys(RawFieldDescriptor rf) {
          Set<String> keys = new LinkedHashSet<>();
          for (String ek : rf.explicitKeys()) {
              if (isPlausibleConfigKeyLiteral(ek)) {
                  keys.add(ek);
                  addNormalizedCaseVariants(keys, ek);
              }
          }
          if (keys.isEmpty() && isPlausibleConfigKeyLiteral(rf.name())) {
              keys.add(rf.name());
              addNormalizedCaseVariants(keys, rf.name());
          }
          return keys;
      }

      private static void addNormalizedCaseVariants(Set<String> out, String key) {
          String kebab = camelToSeparated(key, '-');
          if (!kebab.equals(key) && isPlausibleConfigKeyLiteral(kebab)) {
              out.add(kebab);
          }
          String snake = camelToSeparated(key, '_');
          if (!snake.equals(key) && isPlausibleConfigKeyLiteral(snake)) {
              out.add(snake);
          }
      }

      private static String camelToSeparated(String s, char sep) {
          boolean hasUpper = false;
          boolean hasLower = false;
          for (int i = 0; i < s.length(); i++) {
              char c = s.charAt(i);
              if (Character.isUpperCase(c)) hasUpper = true;
              else if (Character.isLowerCase(c)) hasLower = true;
          }
          if (!hasUpper || !hasLower) {
              return s;
          }
          StringBuilder sb = new StringBuilder(s.length() + 4);
          for (int i = 0; i < s.length(); i++) {
              char c = s.charAt(i);
              if (Character.isUpperCase(c)) {
                  if (i > 0 && (Character.isLowerCase(s.charAt(i - 1)) || Character.isDigit(s.charAt(i - 1)))) {
                      sb.append(sep);
                  }
                  sb.append(Character.toLowerCase(c));
              } else {
                  sb.append(c);
              }
          }
          return sb.toString();
      }

      private static String mapFieldDescriptorToGetter(String fDesc) {
          if (fDesc == null || fDesc.isEmpty()) {
              return "getString";
          }
          return switch (fDesc) {
              case "I", "S", "B", "Ljava/lang/Integer;", "Ljava/lang/Short;", "Ljava/lang/Byte;",
                      "Ljava/util/concurrent/atomic/AtomicInteger;" -> "getInt";
              case "J", "Ljava/lang/Long;", "Ljava/util/concurrent/atomic/AtomicLong;" -> "getLong";
              case "D", "F", "Ljava/lang/Double;", "Ljava/lang/Float;" -> "getDouble";
              case "Z", "Ljava/lang/Boolean;", "Ljava/util/concurrent/atomic/AtomicBoolean;" -> "getBoolean";
              case "Ljava/lang/String;" -> "getString";
              case "Ljava/util/List;", "Ljava/util/Set;", "Ljava/util/Collection;" -> "getStringList";
              case "Ljava/util/Map;" -> "getConfigurationSection";
              default -> fDesc.startsWith("[") ? "getStringList" : "getString";
          };
      }

      private static String extractCandidateEnumFromFieldDescriptor(String fDesc) {
          if (fDesc == null || !fDesc.startsWith("L") || !fDesc.endsWith(";") || fDesc.length() <= 2) {
              return null;
          }
          String internal = fDesc.substring(1, fDesc.length() - 1);
          if (internal.startsWith("java/") || internal.startsWith("javax/") || internal.startsWith("jdk/")
                  || internal.startsWith("com/google/gson/") || internal.startsWith("com/fasterxml/jackson/")) {
              return null;
          }
          return internal.replace('/', '.');
      }

      private static boolean parseAnnotationsAttribute(
              byte[] attrBytes,
              String[] utf8Pool,
              List<String> extractedKeysOut
      ) {
          if (attrBytes == null || attrBytes.length < 2) {
              return false;
          }
          int[] pos = new int[]{0};
          int numAnnotations = readU2(attrBytes, pos);
          boolean matchedConfigAnnotation = false;
          for (int i = 0; i < numAnnotations && pos[0] < attrBytes.length; i++) {
              if (parseSingleAnnotation(attrBytes, pos, utf8Pool, extractedKeysOut)) {
                  matchedConfigAnnotation = true;
              }
          }
          return matchedConfigAnnotation;
      }

      private static boolean parseSingleAnnotation(
              byte[] attrBytes,
              int[] pos,
              String[] utf8Pool,
              List<String> extractedKeysOut
      ) {
          if (pos[0] + 4 > attrBytes.length) {
              pos[0] = attrBytes.length;
              return false;
          }
          int typeIdx = readU2(attrBytes, pos);
          int numPairs = readU2(attrBytes, pos);
          String typeDesc = safeUtf8(utf8Pool, typeIdx);
          boolean isKeyAnnotation = isSupportedFieldKeyAnnotation(typeDesc);
          boolean isClassAnnotation = isSupportedClassConfigAnnotation(typeDesc);
          for (int p = 0; p < numPairs && pos[0] < attrBytes.length; p++) {
              if (pos[0] + 2 > attrBytes.length) {
                  pos[0] = attrBytes.length;
                  break;
              }
              int elemNameIdx = readU2(attrBytes, pos);
              String elemName = safeUtf8(utf8Pool, elemNameIdx);
              boolean captureString = isKeyAnnotation
                      && extractedKeysOut != null
                      && (elemName == null
                              || "value".equals(elemName)
                              || "name".equals(elemName)
                              || "node".equals(elemName)
                              || "key".equals(elemName)
                              || "alternate".equals(elemName));
              parseElementValue(attrBytes, pos, utf8Pool, captureString ? extractedKeysOut : null);
          }
          return isKeyAnnotation || isClassAnnotation;
      }

      private static void parseElementValue(
              byte[] attrBytes,
              int[] pos,
              String[] utf8Pool,
              List<String> captureOut
      ) {
          if (pos[0] >= attrBytes.length) {
              return;
          }
          int tag = attrBytes[pos[0]++] & 0xFF;
          switch ((char) tag) {
              case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 'c' -> readU2(attrBytes, pos);
              case 's' -> {
                  int strUtfIdx = readU2(attrBytes, pos);
                  if (captureOut != null) {
                      String val = safeUtf8(utf8Pool, strUtfIdx);
                      if (val != null && !val.isBlank() && !captureOut.contains(val.trim())) {
                          captureOut.add(val.trim());
                      }
                  }
              }
              case 'e' -> {
                  readU2(attrBytes, pos);
                  readU2(attrBytes, pos);
              }
              case '@' -> parseSingleAnnotation(attrBytes, pos, utf8Pool, null);
              case '[' -> {
                  int numVals = readU2(attrBytes, pos);
                  for (int i = 0; i < numVals && pos[0] < attrBytes.length; i++) {
                      parseElementValue(attrBytes, pos, utf8Pool, captureOut);
                  }
              }
              default -> pos[0] = attrBytes.length;
          }
      }

      private static int readU2(byte[] bytes, int[] pos) {
          if (pos[0] + 2 > bytes.length) {
              pos[0] = bytes.length;
              return 0;
          }
          int v = ((bytes[pos[0]] & 0xFF) << 8) | (bytes[pos[0] + 1] & 0xFF);
          pos[0] += 2;
          return v;
      }

      private static boolean isSupportedFieldKeyAnnotation(String typeDesc) {
          if (typeDesc == null) return false;
          return typeDesc.endsWith("/SerializedName;") || typeDesc.endsWith("$SerializedName;")
                  || typeDesc.endsWith("/JsonProperty;") || typeDesc.endsWith("$JsonProperty;")
                  || typeDesc.endsWith("/Setting;") || typeDesc.endsWith("$Setting;")
                  || typeDesc.endsWith("/ConfigField;") || typeDesc.endsWith("$ConfigField;")
                  || typeDesc.endsWith("/SerialEntry;") || typeDesc.endsWith("$SerialEntry;")
                  || typeDesc.endsWith("/ConfigEntry;") || typeDesc.endsWith("$ConfigEntry;")
                  || typeDesc.endsWith("/PropertyName;") || typeDesc.endsWith("$PropertyName;");
      }

      private static boolean isSupportedClassConfigAnnotation(String typeDesc) {
          if (typeDesc == null) return false;
          return typeDesc.endsWith("/ConfigSerializable;") || typeDesc.endsWith("$ConfigSerializable;")
                  || typeDesc.endsWith("/Configuration;") || typeDesc.endsWith("$Configuration;")
                  || typeDesc.endsWith("/Config;") || typeDesc.endsWith("$Config;")
                  || typeDesc.endsWith("/ConfigData;") || typeDesc.endsWith("$ConfigData;");
      }

    /**
     * Checks whether a constant-pool string literal looks like a valid configuration key path.
     *
     * @param literal string literal from {@code CONSTANT_String}
     * @return {@code true} if the literal is a plausible config key
     */
    public static boolean isPlausibleConfigKeyLiteral(String literal) {
        if (literal == null || literal.length() < 2 || literal.length() > 120) {
            return false;
        }
        if (IGNORED_LITERALS.contains(literal)) {
            return false;
        }
        if (literal.contains("/") || literal.contains("\\") || literal.contains(" ") || literal.contains(":")) {
            return false;
        }
        // Reject Java package/class names (e.g. org.bukkit.Bukkit or io.github...)
        if (literal.startsWith("org.") || literal.startsWith("com.") || literal.startsWith("net.")
                || literal.startsWith("io.") || literal.startsWith("java.") || literal.startsWith("javax.")
                || literal.startsWith("jdk.") || literal.startsWith("sun.")) {
            return false;
        }
        return PLAUSIBLE_CONFIG_KEY.matcher(literal).matches();
    }

    /**
     * Aggregates multiple {@link ClassScanResult} records from a JAR into a map of config keys
     * to consuming classes ({@code READ_BY_CLASS}) and resolved enum options.
     *
     * @param results scanned classes from an artifact JAR
     * @return map of config key path to the set of consuming binary class names
     */
    public static Map<String, Set<String>> buildReadByClassIndex(Iterable<ClassScanResult> results) {
        Objects.requireNonNull(results, "results");
        Map<String, Set<String>> readByClass = new LinkedHashMap<>();
        for (ClassScanResult r : results) {
            if (r == null) continue;
            for (String key : r.referencedConfigKeys()) {
                readByClass.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(r.className());
            }
        }
        return readByClass;
    }

    /**
     * Aggregates method-scoped {@link KeyCallSite} records across all scanned classes in a JAR,
     * linking cross-class field and record-accessor consumers (`symbolReaders`, `methodSubsystems`,
     * and `symbolGuards`) so settings loaded into config records or holders trace into downstream
     * gameplay/governor methods.
     *
     * @param results scanned classes from an artifact JAR
     * @return map of config key literal to all discovered method-level call sites
     */
    public static Map<String, List<KeyCallSite>> buildKeyCallSiteIndex(Iterable<ClassScanResult> results) {
        Objects.requireNonNull(results, "results");
        Map<String, Set<String>> globalReaders = new LinkedHashMap<>();
        Map<String, Set<String>> globalSubsystems = new LinkedHashMap<>();
        Map<String, List<String>> globalGuards = new LinkedHashMap<>();

        for (ClassScanResult r : results) {
            if (r == null) continue;
            for (var e : r.symbolReaders().entrySet()) {
                globalReaders.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
            }
            for (var e : r.methodSubsystems().entrySet()) {
                globalSubsystems.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
            }
            for (var e : r.symbolGuards().entrySet()) {
                List<String> gList = globalGuards.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                for (String g : e.getValue()) {
                    if (!gList.contains(g)) {
                        gList.add(g);
                    }
                }
            }
        }

        Map<String, List<KeyCallSite>> index = new LinkedHashMap<>();
        for (ClassScanResult r : results) {
            if (r == null || r.keyCallSites().isEmpty()) continue;
            for (var entry : r.keyCallSites().entrySet()) {
                String key = entry.getKey();
                String camelLeaf = toCamelCaseIdentifier(SchemaInferrer.extractLeaf(key));
                for (KeyCallSite rawSite : entry.getValue()) {
                    String effectiveField = rawSite.assignedField();
                    if (effectiveField == null && !camelLeaf.isEmpty()
                            && (globalReaders.containsKey(camelLeaf) || globalGuards.containsKey(camelLeaf))) {
                        effectiveField = camelLeaf;
                    }
                    Set<String> mergedDownstream = new LinkedHashSet<>(rawSite.downstreamMethods());
                    Set<String> mergedSubsystems = new LinkedHashSet<>(rawSite.subsystemApis());
                    List<String> mergedGuards = new ArrayList<>(rawSite.guardPredicates());

                    List<String> candidateSymbols = new ArrayList<>(3);
                    if (effectiveField != null && !effectiveField.isEmpty()) {
                        candidateSymbols.add(effectiveField);
                    }
                    if (!camelLeaf.isEmpty() && !candidateSymbols.contains(camelLeaf)) {
                        candidateSymbols.add(camelLeaf);
                    }
                    String enclosingMethod = rawSite.methodName();
                    if (isDedicatedAccessorMethodName(enclosingMethod) && !candidateSymbols.contains(enclosingMethod)) {
                        candidateSymbols.add(enclosingMethod);
                    }

                    String selfRef = rawSite.className() + "#" + rawSite.methodName();
                    for (String sym : candidateSymbols) {
                        for (String readerRef : globalReaders.getOrDefault(sym, Set.of())) {
                            if (!readerRef.equals(selfRef)) {
                                mergedDownstream.add(readerRef);
                            }
                            mergedSubsystems.addAll(globalSubsystems.getOrDefault(readerRef, Set.of()));
                        }
                        for (String g : globalGuards.getOrDefault(sym, List.of())) {
                            if (!mergedGuards.contains(g)) {
                                mergedGuards.add(g);
                            }
                        }
                    }

                    KeyCallSite enriched = new KeyCallSite(
                            rawSite.className(),
                            rawSite.methodName(),
                            rawSite.getterMethod(),
                            rawSite.codeFallbackValue(),
                            effectiveField,
                            rawSite.unitScalingHint(),
                            new ArrayList<>(mergedDownstream),
                            new ArrayList<>(mergedSubsystems),
                            mergedGuards
                    );
                    index.computeIfAbsent(key, k -> new ArrayList<>()).add(enriched);
                }
            }
        }
        return index;
    }

    private record MethodFlowScan(
            Map<String, List<KeyCallSite>> callSites,
            Map<String, Set<String>> symbolReaders,
            Map<String, Set<String>> methodSubsystems,
            Map<String, List<String>> symbolGuards
    ) {
    }

    private static MethodFlowScan scanMethodFlowsAndCallSites(
            String binaryClassName,
            boolean invokesConfigGetter,
            Map<String, byte[]> methodCodeByName,
            String[] utf8Pool,
            String[] numericConstantPool,
            int[] classNameIdx,
            int[] stringUtf8Idx,
            int[] fieldClassIdx,
            int[] fieldNatIdx,
            int[] methodClassIdx,
            int[] methodNatIdx,
            int[] natNameIdx,
            int[] natDescIdx
    ) {
        if (methodCodeByName.isEmpty()) {
            return new MethodFlowScan(Map.of(), Map.of(), Map.of(), Map.of());
        }
        // Pass 1: discover which fields or record/getter accessors each method reads, which domain APIs it invokes, and comparison guards
        Map<String, Set<String>> fieldReadersMap = new LinkedHashMap<>();
        Map<String, Set<String>> methodSubsystemsMap = new LinkedHashMap<>();
        Map<String, List<String>> symbolGuardsMap = new LinkedHashMap<>();

        for (var entry : methodCodeByName.entrySet()) {
            String rawKey = entry.getKey();
            int nulIdx = rawKey.indexOf('\0');
            String mName = nulIdx >= 0 ? rawKey.substring(0, nulIdx) : rawKey;
            byte[] code = entry.getValue();
            Set<String> apis = new LinkedHashSet<>();
            String fqMethod = binaryClassName + "#" + mName;
            int pc = 0;
            while (pc < code.length) {
                int op = code[pc] & 0xFF;
                int step = instructionLength(code, pc);
                if (step <= 0) break;

                String readSymbol = null;
                if ((op == 0xB2 || op == 0xB4) && pc + 2 < code.length) { // getstatic / getfield
                    int cpIdx = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                    String fName = resolveFieldName(cpIdx, fieldNatIdx, natNameIdx, utf8Pool);
                    if (fName != null && isSubstantiveReaderMethod(mName, fName)) {
                        fieldReadersMap.computeIfAbsent(fName, k -> new LinkedHashSet<>()).add(fqMethod);
                        readSymbol = fName;
                    }
                } else if ((op == 0xB6 || op == 0xB7 || op == 0xB8 || op == 0xB9) && pc + 2 < code.length) {
                    int cpIdx = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                    String apiTag = classifyDomainApiCall(cpIdx, methodClassIdx, methodNatIdx, classNameIdx, natNameIdx, utf8Pool);
                    if (apiTag != null) {
                        apis.add(apiTag);
                    }
                    if ((op == 0xB6 || op == 0xB8 || op == 0xB9) && isSubstantiveReaderMethod(mName, null)) {
                        String invokedName = resolveMethodName(cpIdx, methodNatIdx, natNameIdx, utf8Pool);
                        String invokedDesc = resolveMethodDesc(cpIdx, methodNatIdx, natDescIdx, utf8Pool);
                        if (invokedName != null && invokedDesc != null
                                && !invokedName.equals(mName)
                                && invokedDesc.startsWith("()")
                                && !invokedDesc.endsWith("V")
                                && !IGNORED_ZERO_ARG_ACCESSORS.contains(invokedName)
                                && invokedName.length() >= 3) {
                            fieldReadersMap.computeIfAbsent(invokedName, k -> new LinkedHashSet<>()).add(fqMethod);
                            readSymbol = invokedName;
                        }
                    }
                }

                if (readSymbol != null) {
                if (readSymbol != null) {
                    String guard = extractPostReadGuardPredicate(
                            readSymbol,
                            code,
                            pc + step,
                            numericConstantPool,
                            stringUtf8Idx,
                            methodClassIdx,
                            methodNatIdx,
                            classNameIdx,
                            natNameIdx,
                            utf8Pool
                    );
                    if (guard != null) {
                        List<String> gList = symbolGuardsMap.computeIfAbsent(readSymbol, k -> new ArrayList<>());
                        if (!gList.contains(guard)) {
                            gList.add(guard);
                        }
                    }
                    List<String> optionGuards = new ArrayList<>();
                    extractPostReadOptionGuards(
                            code,
                            pc + step,
                            optionGuards,
                            numericConstantPool,
                            stringUtf8Idx,
                            fieldNatIdx,
                            methodClassIdx,
                            methodNatIdx,
                            classNameIdx,
                            natNameIdx,
                            natDescIdx,
                            utf8Pool
                    );
                    if (!optionGuards.isEmpty()) {
                        List<String> gList = symbolGuardsMap.computeIfAbsent(readSymbol, k -> new ArrayList<>());
                        for (String og : optionGuards) {
                            addUniqueGuard(gList, og);
                        }
                    }
                }
                }

                pc += step;
            }
            if (!apis.isEmpty()) {
                methodSubsystemsMap.computeIfAbsent(mName, k -> new LinkedHashSet<>()).addAll(apis);
                methodSubsystemsMap.computeIfAbsent(fqMethod, k -> new LinkedHashSet<>()).addAll(apis);
            }
        }

        if (!invokesConfigGetter) {
            return new MethodFlowScan(Map.of(), fieldReadersMap, methodSubsystemsMap, symbolGuardsMap);
        }

        // Pass 2: locate ldc/ldc_w or *Keys/*Params getstatic config key loads -> config getter -> field write, clamp & scaling
        Map<String, List<KeyCallSite>> callSites = new LinkedHashMap<>();
        for (var entry : methodCodeByName.entrySet()) {
            String rawKey = entry.getKey();
            int nulIdx = rawKey.indexOf('\0');
            String mName = nulIdx >= 0 ? rawKey.substring(0, nulIdx) : rawKey;
            byte[] code = entry.getValue();
            int pc = 0;
            String recentPushedConst = null;
            int recentConstAge = 99;

            while (pc < code.length) {
                int op = code[pc] & 0xFF;
                int insLen = instructionLength(code, pc);
                if (insLen <= 0) break;

                String preConst = extractPushedConstant(code, pc, numericConstantPool, stringUtf8Idx, utf8Pool);
                if (preConst != null && (op != 0x12 && op != 0x13 || !isPlausibleConfigKeyLiteral(preConst))) {
                    recentPushedConst = preConst;
                    recentConstAge = 0;
                } else {
                    recentConstAge++;
                    if (recentConstAge > 8) {
                        recentPushedConst = null;
                    }
                }

                String keyLiteral = null;
                if (op == 0x12 && pc + 1 < code.length) { // ldc
                    int strCpIdx = code[pc + 1] & 0xFF;
                    if (strCpIdx > 0 && strCpIdx < stringUtf8Idx.length) {
                        keyLiteral = safeUtf8(utf8Pool, stringUtf8Idx[strCpIdx]);
                    }
                } else if (op == 0x13 && pc + 2 < code.length) { // ldc_w
                    int strCpIdx = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                    if (strCpIdx > 0 && strCpIdx < stringUtf8Idx.length) {
                        keyLiteral = safeUtf8(utf8Pool, stringUtf8Idx[strCpIdx]);
                    }
                } else if (op == 0xB2 && pc + 2 < code.length) { // getstatic *Keys/*Params enum constant
                    int fCpIdx = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                    keyLiteral = resolveConfigEnumKey(fCpIdx, fieldClassIdx, fieldNatIdx, classNameIdx, natNameIdx, utf8Pool);
                }

                if (isPlausibleConfigKeyLiteral(keyLiteral)) {
                    String getter = null;
                    String fallbackConst = null;
                    String assignedField = null;
                    String scalingHint = null;
                    String pushedConstAfterKey = null;
                    String pushedConstAfterGetter = null;
                    String preGetterConst = recentPushedConst;
                    List<String> localGuards = new ArrayList<>();

                    int scanPc = pc + insLen;
                    int instructionsScanned = 0;
                    while (scanPc < code.length && instructionsScanned < 18) {
                        int sOp = code[scanPc] & 0xFF;
                        int sLen = instructionLength(code, scanPc);
                        if (sLen <= 0) break;

                        String constVal = extractPushedConstant(code, scanPc, numericConstantPool, stringUtf8Idx, utf8Pool);
                        if (constVal != null) {
                            if (getter == null) {
                                pushedConstAfterKey = constVal;
                            } else {
                                pushedConstAfterGetter = constVal;
                                if ("20".equals(constVal) || "20.0".equals(constVal)) {
                                    scalingHint = "*20_TICKS (seconds -> ticks)";
                                } else if ("16".equals(constVal) || "16.0".equals(constVal)) {
                                    scalingHint = "*16_BLOCKS (chunks -> blocks)";
                                } else if ("1000".equals(constVal) || "1000.0".equals(constVal)) {
                                    scalingHint = "*1000_MS (seconds -> ms)";
                                }
                            }
                        } else if (sOp == 0xB2 && scanPc + 2 < code.length && getter == null) {
                            int fCpIdx = ((code[scanPc + 1] & 0xFF) << 8) | (code[scanPc + 2] & 0xFF);
                            String fName = resolveFieldName(fCpIdx, fieldNatIdx, natNameIdx, utf8Pool);
                            if (fName != null && fName.equals(fName.toUpperCase(java.util.Locale.ROOT)) && fName.length() >= 2) {
                                pushedConstAfterKey = fName;
                            }
                        } else if ((sOp == 0xB6 || sOp == 0xB8 || sOp == 0xB9) && scanPc + 2 < code.length) {
                            int mCpIdx = ((code[scanPc + 1] & 0xFF) << 8) | (code[scanPc + 2] & 0xFF);
                            String invokedOwner = resolveClassName(methodClassIdx[mCpIdx], classNameIdx, utf8Pool);
                            String invokedName = resolveMethodName(mCpIdx, methodNatIdx, natNameIdx, utf8Pool);
                            if (invokedName != null && CONFIG_GETTER_METHODS.contains(invokedName) && getter == null) {
                                getter = invokedName;
                                fallbackConst = pushedConstAfterKey;
                                if ("getBoolean".equals(invokedName)) {
                                    if ("1".equals(fallbackConst)) {
                                        fallbackConst = "true";
                                    } else if ("0".equals(fallbackConst)) {
                                        fallbackConst = "false";
                                    }
                                }
                                if ("parseSecondsDurationMillis".equals(invokedName) || "parseDurationSetting".equals(invokedName)) {
                                    scalingHint = "*1000_MS (seconds -> ms)";
                                } else if ("resolveCapacity".equals(invokedName) || "resolveCapacityInt".equals(invokedName)) {
                                    addUniqueGuard(localGuards, "capacity >= 0 (memory-bounded)");
                                }
                            } else if (getter != null && invokedName != null) {
                                String invokedDesc = resolveMethodDesc(mCpIdx, methodNatIdx, natDescIdx, utf8Pool);
                                if ("parseSecondsDurationMillis".equals(invokedName) || "parseDurationSetting".equals(invokedName)) {
                                    scalingHint = "*1000_MS (seconds -> ms)";
                                } else if ("resolveCapacity".equals(invokedName) || "resolveCapacityInt".equals(invokedName)) {
                                    addUniqueGuard(localGuards, "capacity >= 0 (memory-bounded)");
                                } else if (sOp == 0xB8 && "valueOf".equals(invokedName)
                                        && invokedOwner != null && !isNonEnumValueOfOwner(invokedOwner)
                                        && invokedDesc != null && invokedDesc.startsWith("(Ljava/lang/String;)L")) {
                                    addUniqueGuard(localGuards, "enum: " + invokedOwner.replace('/', '.'));
                                } else if ("java/lang/String".equals(invokedOwner)
                                        && ("equals".equals(invokedName) || "equalsIgnoreCase".equals(invokedName))) {
                                    String cmpLit = pushedConstAfterGetter != null ? pushedConstAfterGetter : preGetterConst;
                                    if (isPlausibleOptionToken(cmpLit)) {
                                        addUniqueGuard(localGuards, "match == " + cmpLit);
                                    }
                                } else if ("java/lang/Math".equals(invokedOwner)) {
                                    if ("max".equals(invokedName)) {
                                        String bound = preGetterConst != null ? preGetterConst : pushedConstAfterGetter;
                                        if (bound != null) {
                                            addUniqueGuard(localGuards, "clamp >= " + bound);
                                        }
                                    } else if ("min".equals(invokedName)) {
                                        String bound = preGetterConst != null ? preGetterConst : pushedConstAfterGetter;
                                        if (bound != null) {
                                            addUniqueGuard(localGuards, "clamp <= " + bound);
                                        }
                                    } else if ("pow".equals(invokedName) && ("2".equals(pushedConstAfterGetter) || "2.0".equals(pushedConstAfterGetter))) {
                                        String leaf = SchemaInferrer.extractLeaf(keyLiteral);
                                        addUniqueGuard(localGuards, "distanceSquared >= " + leaf + "^2");
                                    }
                                }
                            }
                        } else if ((sOp == 0xB3 || sOp == 0xB5) && scanPc + 2 < code.length && getter != null) {
                            int fCpIdx = ((code[scanPc + 1] & 0xFF) << 8) | (code[scanPc + 2] & 0xFF);
                            assignedField = resolveFieldName(fCpIdx, fieldNatIdx, natNameIdx, utf8Pool);
                            break;
                        } else if (getter != null && isLocalStoreOpcode(sOp)) {
                            int storedSlot = extractLocalStoreSlot(sOp, code, scanPc);
                            if (storedSlot >= 0) {
                                String slotField = scanLocalSlotUsages(
                                        code,
                                        scanPc + sLen,
                                        storedSlot,
                                        localGuards,
                                        numericConstantPool,
                                        stringUtf8Idx,
                                        fieldNatIdx,
                                        methodClassIdx,
                                        methodNatIdx,
                                        classNameIdx,
                                        natNameIdx,
                                        natDescIdx,
                                        utf8Pool
                                );
                                if (assignedField == null && slotField != null) {
                                    assignedField = slotField;
                                }
                            }
                            break;
                        }
                        scanPc += sLen;
                        instructionsScanned++;
                    }

                    if (getter != null) {
                        String camelLeaf = toCamelCaseIdentifier(SchemaInferrer.extractLeaf(keyLiteral));
                        if (assignedField == null && !camelLeaf.isEmpty()
                                && (fieldReadersMap.containsKey(camelLeaf) || symbolGuardsMap.containsKey(camelLeaf))) {
                            assignedField = camelLeaf;
                        }
                        Set<String> downstream = new LinkedHashSet<>();
                        Set<String> subsystems = new LinkedHashSet<>(methodSubsystemsMap.getOrDefault(mName, Set.of()));
                        if (assignedField != null) {
                            Set<String> readers = fieldReadersMap.getOrDefault(assignedField, Set.of());
                            for (String readerRef : readers) {
                                if (!readerRef.equals(binaryClassName + "#" + mName)) {
                                    downstream.add(readerRef);
                                }
                                int hash = readerRef.indexOf('#');
                                if (hash > 0) {
                                    String rMethod = readerRef.substring(hash + 1);
                                    subsystems.addAll(methodSubsystemsMap.getOrDefault(rMethod, Set.of()));
                                }
                            }
                            for (String g : symbolGuardsMap.getOrDefault(assignedField, List.of())) {
                                addUniqueGuard(localGuards, g);
                            }
                        }
                        KeyCallSite site = new KeyCallSite(
                                binaryClassName,
                                mName,
                                getter,
                                fallbackConst,
                                assignedField,
                                scalingHint,
                                new ArrayList<>(downstream),
                                new ArrayList<>(subsystems),
                                localGuards
                        );
                        callSites.computeIfAbsent(keyLiteral, k -> new ArrayList<>()).add(site);
                    }
                }
                pc += insLen;
            }
        }
        return new MethodFlowScan(callSites, fieldReadersMap, methodSubsystemsMap, symbolGuardsMap);
    }

    private static void addUniqueGuard(List<String> guards, String candidate) {
        if (candidate != null && !candidate.isEmpty() && !guards.contains(candidate)) {
            guards.add(candidate);
        }
    }

    private static boolean isSubstantiveReaderMethod(String methodName, String fieldName) {
        if (methodName == null || methodName.isEmpty()) return false;
        if ("<init>".equals(methodName) || "<clinit>".equals(methodName)) return false;
        if (methodName.startsWith("$") || methodName.startsWith("lambda$")) return false;
        if ("equals".equals(methodName) || "hashCode".equals(methodName) || "toString".equals(methodName)
                || "values".equals(methodName) || "valueOf".equals(methodName)) {
            return false;
        }
        return fieldName == null || !methodName.equals(fieldName);
    }

    private static boolean isDedicatedAccessorMethodName(String methodName) {
        if (!isSubstantiveReaderMethod(methodName, null)) return false;
        String low = methodName.toLowerCase(java.util.Locale.ROOT);
        if (low.startsWith("load") || low.startsWith("reload") || low.startsWith("init")
                || low.equals("onenable") || low.equals("onload") || low.equals("ondisable")
                || low.equals("fromyaml") || low.equals("parse")) {
            return false;
        }
        return true;
    }

    private static boolean isLocalStoreOpcode(int op) {
        return (op >= 0x36 && op <= 0x3A) || (op >= 0x3B && op <= 0x4E);
    }

    private static int extractLocalStoreSlot(int op, byte[] code, int pc) {
        if (op >= 0x36 && op <= 0x3A) {
            return pc + 1 < code.length ? (code[pc + 1] & 0xFF) : -1;
        }
        if (op >= 0x3B && op <= 0x4E) {
            return (op - 0x3B) & 3;
        }
        return -1;
    }

    private static int extractAloadSlot(int op, byte[] code, int pc) {
        if (op == 0x19) {
            return pc + 1 < code.length ? (code[pc + 1] & 0xFF) : -1;
        }
        if (op >= 0x2A && op <= 0x2D) {
            return op - 0x2A;
        }
        return -1;
    }

    private static int extractAstoreSlot(int op, byte[] code, int pc) {
        if (op == 0x3A) {
            return pc + 1 < code.length ? (code[pc + 1] & 0xFF) : -1;
        }
        if (op >= 0x4B && op <= 0x4E) {
            return op - 0x4B;
        }
        return -1;
    }

    private static boolean isNonEnumValueOfOwner(String binaryOwner) {
        if (binaryOwner == null || binaryOwner.isEmpty()) return true;
        return switch (binaryOwner) {
            case "java/lang/String", "java/lang/Integer", "java/lang/Long", "java/lang/Double",
                    "java/lang/Float", "java/lang/Boolean", "java/lang/Short", "java/lang/Byte",
                    "java/lang/Character", "java/math/BigDecimal", "java/math/BigInteger",
                    "java/lang/Enum" -> true;
            default -> false;
        };
    }

    private static boolean isPlausibleOptionToken(String literal) {
        if (literal == null || literal.length() < 2 || literal.length() > 40) {
            return false;
        }
        if ("true".equalsIgnoreCase(literal) || "false".equalsIgnoreCase(literal) || "null".equalsIgnoreCase(literal)) {
            return false;
        }
        return literal.matches("^[A-Za-z][A-Za-z0-9_.:-]*$");
    }

    private static void extractPostReadOptionGuards(
            byte[] code,
            int startPc,
            List<String> targetGuards,
            String[] numericConstantPool,
            int[] stringUtf8Idx,
            int[] fieldNatIdx,
            int[] methodClassIdx,
            int[] methodNatIdx,
            int[] classNameIdx,
            int[] natNameIdx,
            int[] natDescIdx,
            String[] utf8Pool
    ) {
        int pc = startPc;
        int steps = 0;
        String pushedConst = null;
        while (pc < code.length && steps < 6) {
            int op = code[pc] & 0xFF;
            int len = instructionLength(code, pc);
            if (len <= 0) break;

            String c = extractPushedConstant(code, pc, numericConstantPool, stringUtf8Idx, utf8Pool);
            if (c != null) {
                pushedConst = c;
            } else if ((op == 0xB6 || op == 0xB8 || op == 0xB9) && pc + 2 < code.length) {
                int mCpIdx = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                String owner = resolveClassName(methodClassIdx[mCpIdx], classNameIdx, utf8Pool);
                String name = resolveMethodName(mCpIdx, methodNatIdx, natNameIdx, utf8Pool);
                String desc = resolveMethodDesc(mCpIdx, methodNatIdx, natDescIdx, utf8Pool);
                if (op == 0xB8 && "valueOf".equals(name)
                        && owner != null && !isNonEnumValueOfOwner(owner)
                        && desc != null && desc.startsWith("(Ljava/lang/String;)L")) {
                    addUniqueGuard(targetGuards, "enum: " + owner.replace('/', '.'));
                } else if ("java/lang/String".equals(owner)
                        && ("equals".equals(name) || "equalsIgnoreCase".equals(name))
                        && isPlausibleOptionToken(pushedConst)) {
                    addUniqueGuard(targetGuards, "match == " + pushedConst);
                }
            } else if (isLocalStoreOpcode(op) && steps <= 4) {
                int slot = extractAstoreSlot(op, code, pc);
                if (slot >= 0) {
                    scanLocalSlotUsages(
                            code,
                            pc + len,
                            slot,
                            targetGuards,
                            numericConstantPool,
                            stringUtf8Idx,
                            fieldNatIdx,
                            methodClassIdx,
                            methodNatIdx,
                            classNameIdx,
                            natNameIdx,
                            natDescIdx,
                            utf8Pool
                    );
                }
                break;
            }
            pc += len;
            steps++;
        }
    }

    private static String scanLocalSlotUsages(
            byte[] code,
            int startPc,
            int initialSlot,
            List<String> targetGuards,
            String[] numericConstantPool,
            int[] stringUtf8Idx,
            int[] fieldNatIdx,
            int[] methodClassIdx,
            int[] methodNatIdx,
            int[] classNameIdx,
            int[] natNameIdx,
            int[] natDescIdx,
            String[] utf8Pool
    ) {
        Set<Integer> trackedSlots = new LinkedHashSet<>();
        trackedSlots.add(initialSlot);
        String assignedField = null;
        int slotLoadedStepsAgo = -1;
        String recentStringConst = null;
        int recentStringStepsAgo = -1;

        int pc = startPc;
        while (pc < code.length && !trackedSlots.isEmpty()) {
            int op = code[pc] & 0xFF;
            int len = instructionLength(code, pc);
            if (len <= 0) break;

            if (slotLoadedStepsAgo >= 0) {
                slotLoadedStepsAgo++;
                if (slotLoadedStepsAgo > 8) {
                    slotLoadedStepsAgo = -1;
                }
            }
            if (recentStringStepsAgo >= 0) {
                recentStringStepsAgo++;
                if (recentStringStepsAgo > 4) {
                    recentStringStepsAgo = -1;
                    recentStringConst = null;
                }
            }

            int aloadSlot = extractAloadSlot(op, code, pc);
            if (aloadSlot >= 0) {
                if (trackedSlots.contains(aloadSlot)) {
                    slotLoadedStepsAgo = 0;
                } else if (aloadSlot != 0) {
                    slotLoadedStepsAgo = -1;
                }
            } else if (isLocalStoreOpcode(op)) {
                int astoreSlot = extractAstoreSlot(op, code, pc);
                int anyStoreSlot = extractLocalStoreSlot(op, code, pc);
                if (astoreSlot >= 0 && slotLoadedStepsAgo >= 0 && slotLoadedStepsAgo <= 6) {
                    trackedSlots.add(astoreSlot);
                    slotLoadedStepsAgo = -1;
                } else if (anyStoreSlot >= 0 && trackedSlots.contains(anyStoreSlot)) {
                    trackedSlots.remove(anyStoreSlot);
                }
            } else {
                String c = extractPushedConstant(code, pc, numericConstantPool, stringUtf8Idx, utf8Pool);
                if (c != null) {
                    recentStringConst = c;
                    recentStringStepsAgo = 0;
                } else if ((op == 0xB3 || op == 0xB5) && pc + 2 < code.length
                        && slotLoadedStepsAgo >= 0 && slotLoadedStepsAgo <= 3 && assignedField == null) {
                    int fCpIdx = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                    assignedField = resolveFieldName(fCpIdx, fieldNatIdx, natNameIdx, utf8Pool);
                } else if ((op == 0xB6 || op == 0xB8 || op == 0xB9) && pc + 2 < code.length) {
                    int mCpIdx = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                    String owner = resolveClassName(methodClassIdx[mCpIdx], classNameIdx, utf8Pool);
                    String name = resolveMethodName(mCpIdx, methodNatIdx, natNameIdx, utf8Pool);
                    String desc = resolveMethodDesc(mCpIdx, methodNatIdx, natDescIdx, utf8Pool);
                    if (name != null && CONFIG_GETTER_METHODS.contains(name)) {
                        slotLoadedStepsAgo = -1;
                    } else if (slotLoadedStepsAgo >= 0 && slotLoadedStepsAgo <= 4) {
                        if (op == 0xB8 && "valueOf".equals(name)
                                && owner != null && !isNonEnumValueOfOwner(owner)
                                && desc != null && desc.startsWith("(Ljava/lang/String;)L")) {
                            addUniqueGuard(targetGuards, "enum: " + owner.replace('/', '.'));
                        } else if ("java/lang/String".equals(owner)
                                && ("equals".equals(name) || "equalsIgnoreCase".equals(name))
                                && recentStringStepsAgo >= 0 && recentStringStepsAgo <= 3
                                && isPlausibleOptionToken(recentStringConst)) {
                            addUniqueGuard(targetGuards, "match == " + recentStringConst);
                        } else if (("java/lang/String".equals(owner)
                                && ("toLowerCase".equals(name) || "toUpperCase".equals(name)
                                || "trim".equals(name) || "strip".equals(name)))
                                || ("java/util/Objects".equals(owner) && "requireNonNull".equals(name))) {
                            slotLoadedStepsAgo = 1;
                        }
                    }
                }
            }
            pc += len;
        }
        return assignedField;
    }

    private static String extractPostReadGuardPredicate(
            String symbolName,
            byte[] code,
            int startPc,
            String[] numericConstantPool,
            int[] stringUtf8Idx,
            int[] methodClassIdx,
            int[] methodNatIdx,
            int[] classNameIdx,
            int[] natNameIdx,
            String[] utf8Pool
    ) {
        int pc = startPc;
        int steps = 0;
        String pushedConst = null;
        while (pc < code.length && steps < 6) {
            int op = code[pc] & 0xFF;
            int len = instructionLength(code, pc);
            if (len <= 0) break;

            String c = extractPushedConstant(code, pc, numericConstantPool, stringUtf8Idx, utf8Pool);
            if (c != null) {
                pushedConst = c;
            } else if (op >= 0x94 && op <= 0x98 && pc + len < code.length) { // lcmp, fcmpl, fcmpg, dcmpl, dcmpg
                int nextOp = code[pc + len] & 0xFF;
                if (nextOp >= 0x99 && nextOp <= 0x9E) {
                    if (pushedConst != null) {
                        // symbol compared against constant on right: if (symbol OP const)
                        return switch (nextOp) {
                            case 0x9E -> symbolName + " > " + pushedConst;  // ifle skips when <= -> active when >
                            case 0x9B -> symbolName + " >= " + pushedConst; // iflt skips when <  -> active when >=
                            case 0x9C -> symbolName + " < " + pushedConst;  // ifge skips when >= -> active when <
                            case 0x9D -> symbolName + " <= " + pushedConst; // ifgt skips when >  -> active when <=
                            default -> null;
                        };
                    } else {
                        // variable on left compared against symbol on right: if (var OP symbol)
                        return switch (nextOp) {
                            case 0x9D -> "value <= " + symbolName; // ifgt skips when var > symbol -> active when var <= symbol
                            case 0x9C -> "value < " + symbolName;  // ifge skips when var >= symbol -> active when var < symbol
                            case 0x9B -> "value >= " + symbolName; // iflt skips when var < symbol -> active when var >= symbol
                            case 0x9E -> "value > " + symbolName;  // ifle skips when var <= symbol -> active when var > symbol
                            default -> null;
                        };
                    }
                }
            } else if (op >= 0x99 && op <= 0x9E && steps <= 2) { // single-operand branch against 0 (ifeq..ifle)
                return switch (op) {
                    case 0x9E -> symbolName + " > 0";  // ifle
                    case 0x9B -> symbolName + " >= 0"; // iflt
                    case 0x9C -> symbolName + " < 0";  // ifge
                    case 0x9D -> symbolName + " <= 0"; // ifgt
                    default -> null;
                };
            } else if (op >= 0x9F && op <= 0xA4 && steps <= 3) { // if_icmpeq..if_icmple
                if (pushedConst != null) {
                    return switch (op) {
                        case 0xA4 -> symbolName + " > " + pushedConst;  // if_icmple
                        case 0xA1 -> symbolName + " >= " + pushedConst; // if_icmplt
                        case 0xA2 -> symbolName + " < " + pushedConst;  // if_icmpge
                        case 0xA3 -> symbolName + " <= " + pushedConst; // if_icmpgt
                        default -> null;
                    };
                } else {
                    return switch (op) {
                        case 0xA4 -> "value <= " + symbolName; // if_icmple (e.g. if (len > maxSummaryChars) -> active bound <= maxSummaryChars)
                        case 0xA3 -> "value <= " + symbolName;
                        case 0xA1 -> "value >= " + symbolName;
                        default -> null;
                    };
                }
            } else if (op == 0xB8 && pc + 2 < code.length) { // invokestatic Math.min / Math.max
                int mCpIdx = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                String owner = resolveClassName(methodClassIdx[mCpIdx], classNameIdx, utf8Pool);
                String name = resolveMethodName(mCpIdx, methodNatIdx, natNameIdx, utf8Pool);
                if ("java/lang/Math".equals(owner)) {
                    if ("min".equals(name)) {
                        return "clamp <= " + symbolName;
                    } else if ("max".equals(name)) {
                        return "clamp >= " + (pushedConst != null ? pushedConst : symbolName);
                    }
                }
            } else if ((op == 0x60 || op == 0x61) && steps == 0) { // iadd / ladd (e.g. tick + reloadCooldownTicks)
                return "window = tick + " + symbolName;
            }
            pc += len;
            steps++;
        }
        return null;
    }

    static String toCamelCaseIdentifier(String rawLeaf) {
        if (rawLeaf == null || rawLeaf.isEmpty()) return "";
        if (!rawLeaf.contains("-") && !rawLeaf.contains("_")) {
            return rawLeaf;
        }
        String[] parts = rawLeaf.split("[-_]+");
        StringBuilder sb = new StringBuilder(rawLeaf.length());
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            if (sb.length() == 0) {
                sb.append(p.toLowerCase(java.util.Locale.ROOT));
            } else {
                sb.append(Character.toUpperCase(p.charAt(0)));
                if (p.length() > 1) {
                    sb.append(p.substring(1).toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return sb.toString();
    }

    private static String extractPushedConstant(
            byte[] code,
            int pc,
            String[] numericConstantPool,
            int[] stringUtf8Idx,
            String[] utf8Pool
    ) {
        int op = code[pc] & 0xFF;
        return switch (op) {
            case 0x02 -> "-1";
            case 0x03 -> "0";
            case 0x04 -> "1";
            case 0x05 -> "2";
            case 0x06 -> "3";
            case 0x07 -> "4";
            case 0x08 -> "5";
            case 0x09 -> "0";
            case 0x0A -> "1";
            case 0x0B -> "0.0";
            case 0x0C -> "1.0";
            case 0x0D -> "2.0";
            case 0x0E -> "0.0";
            case 0x0F -> "1.0";
            case 0x10 -> pc + 1 < code.length ? Integer.toString(code[pc + 1]) : null; // bipush
            case 0x11 -> pc + 2 < code.length ? Integer.toString((short) (((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF))) : null; // sipush
            case 0x12 -> { // ldc
                if (pc + 1 >= code.length) yield null;
                int idx = code[pc + 1] & 0xFF;
                if (idx > 0 && idx < numericConstantPool.length && numericConstantPool[idx] != null) {
                    yield numericConstantPool[idx];
                }
                if (idx > 0 && idx < stringUtf8Idx.length && stringUtf8Idx[idx] > 0) {
                    yield safeUtf8(utf8Pool, stringUtf8Idx[idx]);
                }
                yield null;
            }
            case 0x13, 0x14 -> { // ldc_w / ldc2_w
                if (pc + 2 >= code.length) yield null;
                int idx = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                if (idx > 0 && idx < numericConstantPool.length && numericConstantPool[idx] != null) {
                    yield numericConstantPool[idx];
                }
                if (op == 0x13 && idx > 0 && idx < stringUtf8Idx.length && stringUtf8Idx[idx] > 0) {
                    yield safeUtf8(utf8Pool, stringUtf8Idx[idx]);
                }
                yield null;
            }
            default -> null;
        };
    }

    private static String classifyDomainApiCall(
            int methodCpIdx,
            int[] methodClassIdx,
            int[] methodNatIdx,
            int[] classNameIdx,
            int[] natNameIdx,
            String[] utf8Pool
    ) {
        if (methodCpIdx <= 0 || methodCpIdx >= methodNatIdx.length) return null;
        String owner = resolveClassName(methodClassIdx[methodCpIdx], classNameIdx, utf8Pool);
        String name = resolveMethodName(methodCpIdx, methodNatIdx, natNameIdx, utf8Pool);
        if (name == null) return null;
        String combined = ((owner != null ? owner : "") + "#" + name).toLowerCase(java.util.Locale.ROOT);
        if (combined.contains("scheduler") || combined.contains("runtask") || combined.contains("schedule")) {
            return "Scheduler/PeriodicTick";
        }
        if (combined.contains("damage") || combined.contains("combat") || combined.contains("knockback") || combined.contains("sethealth")) {
            return "Combat/DamagePipeline";
        }
        if (combined.contains("spawnentity") || combined.contains("spawner") || combined.contains("creature")) {
            return "EntitySpawning/MobAI";
        }
        if (combined.contains("hopper") || combined.contains("inventorymoveitem") || combined.contains("inventorypickup")) {
            return "Hopper/InventoryTransfer";
        }
        if (combined.contains("worldborder") || combined.contains("teleport") || combined.contains("location")
                || combined.contains("playermove") || combined.contains("movedsquared") || combined.contains("rtpteleportcancel")) {
            return "WorldBorder/SpatialTeleport";
        }
        if (combined.contains("chunk") || combined.contains("getblock") || combined.contains("cachememorycost")
                || combined.contains("regionqueuemanager") || combined.contains("backloglocationbuffer")) {
            return "ChunkIO/BlockAccess";
        }
        if (combined.contains("economy") || combined.contains("withdraw") || combined.contains("deposit") || combined.contains("vault")) {
            return "Economy/VaultTransaction";
        }
        if (combined.contains("particle") || combined.contains("playsound") || combined.contains("sendmessage") || combined.contains("title")) {
            return "PlayerFeedback/VisualAudio";
        }
        if (combined.contains("empiricalfarmblackbox") || combined.contains("hysteresis")
                || combined.contains("sleuthmitigation") || combined.contains("lagattribution")) {
            return "Governor/FarmBlackBoxHysteresis";
        }
        if (combined.contains("httpurlconnection") || combined.contains("openconnection")
                || combined.contains("processbuilder") || combined.contains("serversocket")) {
            return "Network/HttpSshTransport";
        }
        if (combined.contains("yamlconfig") || combined.contains("rtpyamlconfig") || combined.contains("jarconfigscanner")
                || combined.contains("configbackups") || combined.contains("serverconfigatlas")) {
            return "ConfigAtlas/YamlWorkspace";
        }
        return null;
    }

    private static int instructionLength(byte[] code, int pc) {
        int op = code[pc] & 0xFF;
        return switch (op) {
            case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC -> 2;
            case 0x11, 0x13, 0x14, 0x84, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0,
                 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xB2, 0xB3, 0xB4, 0xB5,
                 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7 -> 3;
            case 0xC5 -> 4;
            case 0xB9, 0xBA, 0xC8, 0xC9 -> 5;
            case 0xC4 -> { // wide
                if (pc + 1 >= code.length) yield -1;
                int subOp = code[pc + 1] & 0xFF;
                yield subOp == 0x84 ? 6 : 4;
            }
            case 0xAA -> { // tableswitch
                int aligned = (pc + 4) & ~3;
                if (aligned + 12 > code.length) yield -1;
                int low = readInt(code, aligned + 4);
                int high = readInt(code, aligned + 8);
                if (high < low || high - low > 16384) yield -1;
                yield (aligned - pc) + 12 + (high - low + 1) * 4;
            }
            case 0xAB -> { // lookupswitch
                int aligned = (pc + 4) & ~3;
                if (aligned + 8 > code.length) yield -1;
                int npairs = readInt(code, aligned + 4);
                if (npairs < 0 || npairs > 16384) yield -1;
                yield (aligned - pc) + 8 + npairs * 8;
            }
            default -> 1;
        };
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static String resolveConfigEnumKey(
            int fieldCpIdx,
            int[] fieldClassIdx,
            int[] fieldNatIdx,
            int[] classNameIdx,
            int[] natNameIdx,
            String[] utf8Pool
    ) {
        if (fieldCpIdx <= 0 || fieldCpIdx >= fieldNatIdx.length) return null;
        String owner = resolveClassName(fieldClassIdx[fieldCpIdx], classNameIdx, utf8Pool);
        if (owner == null) return null;
        if (!owner.endsWith("Keys") && !owner.endsWith("Params")) return null;
        String fieldName = resolveFieldName(fieldCpIdx, fieldNatIdx, natNameIdx, utf8Pool);
        return isPlausibleConfigKeyLiteral(fieldName) ? fieldName : null;
    }

    private static String resolveFieldName(int fieldCpIdx, int[] fieldNatIdx, int[] natNameIdx, String[] utf8Pool) {
        if (fieldCpIdx <= 0 || fieldCpIdx >= fieldNatIdx.length) return null;
        int nat = fieldNatIdx[fieldCpIdx];
        if (nat <= 0 || nat >= natNameIdx.length) return null;
        return safeUtf8(utf8Pool, natNameIdx[nat]);
    }

    private static String resolveMethodName(int methodCpIdx, int[] methodNatIdx, int[] natNameIdx, String[] utf8Pool) {
        if (methodCpIdx <= 0 || methodCpIdx >= methodNatIdx.length) return null;
        int nat = methodNatIdx[methodCpIdx];
        if (nat <= 0 || nat >= natNameIdx.length) return null;
        return safeUtf8(utf8Pool, natNameIdx[nat]);
    }

    private static String resolveMethodDesc(int methodCpIdx, int[] methodNatIdx, int[] natDescIdx, String[] utf8Pool) {
        if (methodCpIdx <= 0 || methodCpIdx >= methodNatIdx.length) return null;
        int nat = methodNatIdx[methodCpIdx];
        if (nat <= 0 || nat >= natDescIdx.length) return null;
        return safeUtf8(utf8Pool, natDescIdx[nat]);
    }

    private static String resolveClassName(int classIndex, int[] classNameIdx, String[] utf8Pool) {
        if (classIndex <= 0 || classIndex >= classNameIdx.length) {
            return null;
        }
        return safeUtf8(utf8Pool, classNameIdx[classIndex]);
    }

    private static String safeUtf8(String[] utf8Pool, int index) {
        if (index <= 0 || index >= utf8Pool.length) {
            return null;
        }
        return utf8Pool[index];
    }
}
