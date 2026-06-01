package io.github.dailystruggle.effectsapi.common;

/**
 * Fixed inner-field schema for one effect-group YAML file under
 * {@code <pluginDir>/effects/<groupName>.yml} (see effects-api-ADR-005).
 *
 * <p>The outer key (group name) is admin-chosen and therefore not enumerable;
 * each per-group YAML file's <em>inner</em> fields are enumerated here so
 * {@code MultiConfigParser<EffectsGroupKeys>} can drive both load and the
 * per-locale {@code lang/<l>/effects.lang.yml} key remap (
 * {@code WHEN -&gt; "触发阶段"} etc.).
 *
 * <ul>
 *   <li>{@link #version}    schema version (must match the on-disk default).</li>
 *   <li>{@link #when}       pipeline stage token (one of:
 *       {@code firstjoin / join / presetup / postsetup / preload / postload /
 *        preteleport / postteleport / cancel / queuepush / queuepop}).</li>
 *   <li>{@link #permission} optional permission node gating membership.</li>
 *   <li>{@link #players}    optional explicit UUID/name allowlist (the
 *       permission-less path used on Fabric servers without a perms manager).</li>
 *   <li>{@link #inherit}    optional list of group names whose tokens are
 *       prepended; defaults to {@code [default]} for the same stage when
 *       absent. Pass an empty list to opt out.</li>
 *   <li>{@link #effects}    ordered token list ({@code <NAME>.<arg1>.<...>}),
 *       same grammar as the suffix of an {@code rtp.effect.<stage>.*}
 *       permission node.</li>
 * </ul>
 *
 * <p>Stage tokens (the value of {@code when:}) and effect-name tokens are
 * <em>kept literal</em> across locales — only field keys are localized. This
 * matches project precedent (e.g. {@code shape: SQUARE} in
 * {@code lang/zh/regions/default.yml}).
 */
public enum EffectsGroupKeys {
  version,
  when,
  permission,
  players,
  inherit,
  effects
}
