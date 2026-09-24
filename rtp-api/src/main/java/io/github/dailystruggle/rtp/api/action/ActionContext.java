package io.github.dailystruggle.rtp.api.action;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Caller-provided invocation context when triggering a scripted action (ADR-093).
 *
 * @param metadata custom contextual parameters or tag bindings passed to the script execution
 */
@PublicApi
public record ActionContext(Map<String, Object> metadata) {
  public static final ActionContext EMPTY = new ActionContext(Collections.emptyMap());

  public ActionContext {
    metadata = (metadata == null) ? Collections.emptyMap() : Map.copyOf(metadata);
  }

  public static ActionContext empty() {
    return EMPTY;
  }

  public static ActionContext of(String key, Object value) {
    Objects.requireNonNull(key, "key must not be null");
    return new ActionContext(Map.of(key, value));
  }

  public static ActionContext of(Map<String, Object> metadata) {
    return new ActionContext(metadata);
  }

  /**
   * Constructs an ActionContext holding ordered player clusters (e.g. 1v1, 2v2, 1v2, teams).
   *
   * @param clusters list of player clusters
   * @return action context with clusters metadata
   */
  public static ActionContext ofClusters(List<List<UUID>> clusters) {
    Objects.requireNonNull(clusters, "clusters must not be null");
    return new ActionContext(Map.of("clusters", clusters));
  }

  /**
   * Constructs an ActionContext holding named player clusters (e.g. red, blue).
   *
   * @param namedClusters map of cluster name to player UUIDs
   * @return action context with named clusters metadata
   */
  public static ActionContext ofNamedClusters(Map<String, List<UUID>> namedClusters) {
    Objects.requireNonNull(namedClusters, "namedClusters must not be null");
    return new ActionContext(Map.of("clusters", namedClusters));
  }

  /**
   * Returns player clusters if configured in metadata under "clusters" or "groups",
   * or empty list if no clusters are present.
   */
  @SuppressWarnings("unchecked")
  public List<List<UUID>> clusters() {
    Object raw = metadata.get("clusters");
    if (raw == null) raw = metadata.get("groups");
    if (raw == null) return Collections.emptyList();

    if (raw instanceof List<?> outerList) {
      List<List<UUID>> result = new ArrayList<>();
      for (Object elem : outerList) {
        if (elem instanceof Collection<?> coll) {
          List<UUID> cluster = new ArrayList<>();
          for (Object item : coll) {
            if (item instanceof UUID u) cluster.add(u);
            else if (item != null) {
              try {
                cluster.add(UUID.fromString(item.toString().trim()));
              } catch (IllegalArgumentException ignored) {}
            }
          }
          if (!cluster.isEmpty()) result.add(Collections.unmodifiableList(cluster));
        }
      }
      return Collections.unmodifiableList(result);
    } else if (raw instanceof Map<?, ?> map) {
      List<List<UUID>> result = new ArrayList<>();
      for (Object val : map.values()) {
        if (val instanceof Collection<?> coll) {
          List<UUID> cluster = new ArrayList<>();
          for (Object item : coll) {
            if (item instanceof UUID u) cluster.add(u);
            else if (item != null) {
              try {
                cluster.add(UUID.fromString(item.toString().trim()));
              } catch (IllegalArgumentException ignored) {}
            }
          }
          if (!cluster.isEmpty()) result.add(Collections.unmodifiableList(cluster));
        }
      }
      return Collections.unmodifiableList(result);
    }
    return Collections.emptyList();
  }

  /**
   * Returns named player clusters if configured in metadata under "clusters" or "groups",
   * or empty map if not present or configured as an unnamed list.
   */
  @SuppressWarnings("unchecked")
  public Map<String, List<UUID>> namedClusters() {
    Object raw = metadata.get("clusters");
    if (raw == null) raw = metadata.get("groups");
    if (raw instanceof Map<?, ?> map) {
      Map<String, List<UUID>> result = new java.util.LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() != null && entry.getValue() instanceof Collection<?> coll) {
          List<UUID> cluster = new ArrayList<>();
          for (Object item : coll) {
            if (item instanceof UUID u) cluster.add(u);
            else if (item != null) {
              try {
                cluster.add(UUID.fromString(item.toString().trim()));
              } catch (IllegalArgumentException ignored) {}
            }
          }
          result.put(entry.getKey().toString().trim(), Collections.unmodifiableList(cluster));
        }
      }
      return Collections.unmodifiableMap(result);
    }
    return Collections.emptyMap();
  }
}
