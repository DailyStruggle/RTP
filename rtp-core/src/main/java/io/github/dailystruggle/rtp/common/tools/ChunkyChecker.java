package io.github.dailystruggle.rtp.common.tools;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;
import org.popcraft.chunky.Chunky;
import org.popcraft.chunky.ChunkyProvider;
import org.popcraft.chunky.shape.ShapeType;

/** Utility class for interacting with the Chunky plugin */
public class ChunkyChecker {
  // stored object reference to skip plugin getting sometimes
  private static Chunky chunky = null;

  /** Shape registration hook installed by the composition root (ADR-115). */
  private static volatile Consumer<String> shapeRegistrar = s -> {};

  /** Default constructor for ChunkyChecker */
  private ChunkyChecker() {}

  /** Installs the shape registration callback used by {@link #loadChunky()}. */
  public static void setShapeRegistrar(Consumer<String> registrar) {
    if (registrar != null) shapeRegistrar = registrar;
  }

  /** getPAPI - function to if PAPI exists and scan the above object reference accordingly */
  private static void getChunky() {
    try {
      chunky = ChunkyProvider.get();
    } catch (Throwable t) {
      chunky = null;
    }
  }

  /** Load Chunky integration and register its shapes */
  public static void loadChunky() {
    // if I don't have a correct object reference, try to get one.
    getChunky();

    if (chunky != null) {
      for (Field field : ShapeType.class.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers()) && field.getType().equals(String.class)) {
          String s;
          try {
            s = (String) field.get(null);
          } catch (Throwable t) {
            continue;
          }

          s = "chunky_" + s;
          shapeRegistrar.accept(s);
        }
      }
    }
  }
}
