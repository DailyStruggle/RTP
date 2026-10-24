package io.github.dailystruggle.menu.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architectural boundary verification ensuring {@code menu-api} has zero bytecode references to
 * {@code rtp-core} or platform runtime packages (ADR-116).
 */
@DisplayName("menu-api Architectural Boundary Verification (ADR-116)")
class MenuApiBoundaryArchTest {

  private static final List<String> FORBIDDEN_PREFIXES =
      List.of(
          "io/github/dailystruggle/rtp/common/",
          "org/bukkit/",
          "io/papermc/",
          "net/minecraft/",
          "net/fabricmc/",
          "net/neoforged/");

  @Test
  @DisplayName("menu-api classes do not reference rtp-core or platform SDK packages")
  void menuApiClassesHaveZeroForbiddenReferences() throws IOException, URISyntaxException {
    URL location = MenuDescriptor.class.getProtectionDomain().getCodeSource().getLocation();
    Path classesRoot = Path.of(location.toURI());
    Path packageDir = classesRoot.resolve("io/github/dailystruggle/menu/api");
    assertTrue(Files.isDirectory(packageDir), "Expected compiled package directory: " + packageDir);

    List<Path> classFiles;
    try (Stream<Path> walk = Files.walk(packageDir)) {
      classFiles = walk.filter(p -> p.toString().endsWith(".class")).toList();
    }
    assertFalse(classFiles.isEmpty(), "Expected compiled class files in menu-api");

    for (Path classFile : classFiles) {
      for (String utf8Entry : readConstantPoolUtf8Strings(classFile)) {
        for (String forbidden : FORBIDDEN_PREFIXES) {
          assertFalse(
              utf8Entry.contains(forbidden),
              () ->
                  "Forbidden reference '"
                      + forbidden
                      + "' found in "
                      + classesRoot.relativize(classFile)
                      + " constant pool entry: "
                      + utf8Entry);
        }
      }
    }
  }

  private static List<String> readConstantPoolUtf8Strings(Path classFile) throws IOException {
    List<String> strings = new ArrayList<>();
    try (InputStream in = Files.newInputStream(classFile);
        DataInputStream data = new DataInputStream(in)) {
      int magic = data.readInt();
      if (magic != 0xCAFEBABE) {
        throw new IOException("Invalid class file magic in " + classFile);
      }
      data.readUnsignedShort(); // minor_version
      data.readUnsignedShort(); // major_version
      int cpCount = data.readUnsignedShort();
      for (int i = 1; i < cpCount; i++) {
        int tag = data.readUnsignedByte();
        switch (tag) {
          case 1 -> { // CONSTANT_Utf8
            int length = data.readUnsignedShort();
            byte[] bytes = data.readNBytes(length);
            strings.add(new String(bytes, StandardCharsets.UTF_8));
          }
          case 3, 4 -> data.skipBytes(4); // Integer, Float
          case 5, 6 -> { // Long, Double (occupy two constant-pool slots)
            data.skipBytes(8);
            i++;
          }
          case 7, 8, 16, 19, 20 -> data.skipBytes(2); // Class, String, MethodType, Module, Package
          case 9, 10, 11, 12, 17, 18 ->
              data.skipBytes(4); // Fieldref, Methodref, InterfaceMethodref, NameAndType, Dynamic
          case 15 -> data.skipBytes(3); // MethodHandle
          default -> throw new IOException("Unknown constant pool tag " + tag + " in " + classFile);
        }
      }
    }
    return strings;
  }
}
