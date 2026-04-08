package io.github.dailystruggle.rtp.common.selection.region.selectors.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import java.io.IOException;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract class for shapes used in region selection
 *
 * @param <E> enum of available parameters for the shape
 */
public abstract class Shape<E extends Enum<E>> extends FactoryValue<E> {
  public final String name;

  protected final List<BiPredicate<GenerationContext, RTPCoords>> verifiers = new ArrayList<>();
  protected final List<BiPredicate<GenerationContext, MutableRTPCoords>> verifiersMutable = new ArrayList<>();

  public boolean checkVerifiers(GenerationContext context, RTPCoords coords) {
    for (int i = 0; i < verifiers.size(); i++) {
      BiPredicate<GenerationContext, RTPCoords> verifier = verifiers.get(i);
      if (!verifier.test(context, coords)) return false;
    }
    return true;
  }

  public boolean checkVerifiers(GenerationContext context, MutableRTPCoords coords) {
    for (int i = 0; i < verifiersMutable.size(); i++) {
      BiPredicate<GenerationContext, MutableRTPCoords> verifier = verifiersMutable.get(i);
      if (!verifier.test(context, coords)) return false;
    }
    return true;
  }

  /**
   * Constructor for Shape
   *
   * @param eClass - enum class to use
   * @param name - unique name of shape
   * @param data - default data
   * @throws IllegalArgumentException - in case of invalid inputs for expected types
   */
  public Shape(Class<E> eClass, String name, EnumMap<E, Object> data)
      throws IllegalArgumentException {
    super(eClass, name);
    this.name = name;
    this.data.putAll(data);
    for (E val : myClass.getEnumConstants()) {
      if (!data.containsKey(val))
        throw new IllegalArgumentException("All values must be filled out on shape instantiation");
    }
    try {
      loadLangFile("shape");
    } catch (IOException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
  }

  /**
   * Rotate a point around the origin
   *
   * @param input the x and z coordinates
   * @param degrees the degrees to rotate
   * @return the rotated coordinates
   */
  public int[] rotate(int[] input, long degrees) {
    double angle = Math.toRadians(degrees);

    double s = Math.sin(angle);
    double c = Math.cos(angle);

    int x = input[0];
    int z = input[1];

    // generate new point
    input[0] = (int) (x * c - z * s);
    input[1] = (int) (x * s + z * c);

    return input;
  }

  /**
   * Rotate coordinates around the origin
   *
   * @param coords the coordinates
   * @param degrees the degrees to rotate
   */
  public void rotate(MutableRTPCoords coords, long degrees) {
    double angle = Math.toRadians(degrees);

    double s = Math.sin(angle);
    double c = Math.cos(angle);

    int x = coords.x;
    int z = coords.z;

    // generate new point
    coords.setXZ((int) (x * c - z * s), (int) (x * s + z * c));
  }

  @Override
  public @NotNull EnumMap<E, Object> getData() {
    return data.clone();
  }

  /**
   * Select a random point within the shape
   *
   * @return the selected coordinates
   */
  public abstract int[] select();

  /**
   * Get the command parameters for the shape
   *
   * @return a map of command parameters
   */
  public abstract Map<String, CommandParameter> getParameters();

  @Override
  public Shape<E> clone() {
    return (Shape<E>) super.clone();
  }

  /**
   * Determine if a point is within the shape
   *
   * @param x the x coordinate (in chunks)
   * @param z the z coordinate (in chunks)
   * @return true if the point is within the shape, false otherwise
   */
  public abstract boolean contains(int x, int z);

  @SuppressWarnings("unchecked")
  @Override
  public boolean equals(Object o) {
    if (o == null) return false;
    if (!o.getClass().equals(getClass()) || !((Shape<?>) o).myClass.equals(myClass)) return false;
    EnumMap<E, Object> data1 = getData();
    EnumMap<E, Object> data2 = ((Shape<E>) o).getData();
    for (Map.Entry<E, Object> entry : data1.entrySet()) {
      E key = entry.getKey();
      Object value = entry.getValue();
      try {
        Number number1 = getNumber(key, 0);
        try {
          Number number2 = ((Shape<E>) o).getNumber(key, 0);
          if (number1.doubleValue() != number2.doubleValue()) return false;
        } catch (IllegalArgumentException ignored) {
          return false;
        }
      } catch (IllegalArgumentException ignored) {
        if (!value.toString().equals(data2.get(key).toString())) return false;
      }
    }
    return true;
  }
}
