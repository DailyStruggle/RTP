package io.github.dailystruggle.rtp.api.platform;

import java.util.Objects;

/**
 * One recorded original block captured before emergency platform creation (ADR-060).
 * Holds world coordinates and an opaque serializable block-state token.
 */
public final class BlockDelta {
  private final int x;
  private final int y;
  private final int z;
  private final String token;

  /**
   * @param x     world block X
   * @param y     world block Y
   * @param z     world block Z
   * @param token the adapter's canonical, serializable block-state token; never {@code null}
   */
  public BlockDelta(int x, int y, int z, String token) {
    this.x = x;
    this.y = y;
    this.z = z;
    this.token = Objects.requireNonNull(token, "token");
  }

  /** @return world block X. */
  public int x() {
    return x;
  }

  /** @return world block Y. */
  public int y() {
    return y;
  }

  /** @return world block Z. */
  public int z() {
    return z;
  }

  /** @return the platform-opaque block-state token. */
  public String token() {
    return token;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof BlockDelta)) return false;
    BlockDelta that = (BlockDelta) o;
    return x == that.x && y == that.y && z == that.z && token.equals(that.token);
  }

  @Override
  public int hashCode() {
    return Objects.hash(x, y, z, token);
  }

  @Override
  public String toString() {
    return "BlockDelta[" + x + ',' + y + ',' + z + "=" + token + ']';
  }
}
