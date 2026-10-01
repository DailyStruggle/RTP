package io.github.dailystruggle.rtp.common.importer.schema;

import java.util.List;

/**
 * Built-in in-code schemas for major random teleport plugins.
 */
public final class BuiltinPluginSchemas {

    private BuiltinPluginSchemas() {}

    /**
     * In-code schema for AsyncRTP (asyrtp).
     */
    public static final PluginImportSchema ASYNC_RTP = PluginImportSchema.builder("AsyncRTP")
            .aliases("AsyncRTP", "asyncrtp", "AsyRTP", "ASYNCRTP")
            .candidateFiles("config.yml", "rtp.yml")
            .worldMapping("worlds", PluginImportSchema.Mode.MAP, new PluginImportSchema.FieldAliases(
                    List.of("max-radius", "maxRadius", "radius", "max-distance"),
                    List.of("min-radius", "minRadius", "min-distance"),
                    List.of("center.x", "center-x", "centerX", "spawn.x"),
                    List.of("center.z", "center-z", "centerZ", "spawn.z"),
                    List.of("min-y", "minY"),
                    List.of("max-y", "maxY"),
                    List.of("shape", "type"),
                    List.of("price", "cost", "vault"),
                    List.of("cooldown", "cooldown-time"),
                    List.of("delay", "teleport-delay"),
                    List.of("blacklisted-blocks", "unsafe-blocks"),
                    List.of("biomes", "blacklisted-biomes")
            ))
            .globalMapping(
                    List.of("cooldown", "default-cooldown", "teleport-cooldown"),
                    List.of("delay", "teleport-delay", "warmup"),
                    List.of("max-attempts", "maxAttempts", "retries")
            )
            .permission("asyncrtp.use", "rtp.use")
            .permission("asyncrtp.world.{world}", "rtp.world.{world}")
            .permission("asyncrtp.bypass.cooldown", "rtp.nocooldown")
            .permission("asyncrtp.bypass.delay", "rtp.nodelay")
            .permission("asyncrtp.admin", "rtp.admin")
            .build();

    /**
     * In-code schema for AdvancedRTP.
     */
    public static final PluginImportSchema ADVANCED_RTP = PluginImportSchema.builder("AdvancedRTP")
            .aliases("AdvancedRTP", "advancedrtp", "AdvRTP", "ADVANCEDRTP")
            .candidateFiles("config.yml")
            .worldMapping("worlds", PluginImportSchema.Mode.MAP, new PluginImportSchema.FieldAliases(
                    List.of("radius", "max_radius", "max-radius"),
                    List.of("min_radius", "min-radius"),
                    List.of("x", "centerX", "center-x"),
                    List.of("z", "centerZ", "center-z"),
                    List.of("min_y", "min-y"),
                    List.of("max_y", "max-y"),
                    List.of("shape", "mode"),
                    List.of("price", "cost", "money"),
                    List.of("cooldown"),
                    List.of("warmup", "delay"),
                    List.of("bad-blocks", "blacklisted_blocks", "blacklist"),
                    List.of("biomes", "blacklisted_biomes")
            ))
            .globalMapping(
                    List.of("cooldown", "global-cooldown"),
                    List.of("warmup", "teleport-delay"),
                    List.of("attempts", "max-attempts")
            )
            .permission("advancedrtp.rtp", "rtp.use")
            .permission("advancedrtp.world.{world}", "rtp.world.{world}")
            .permission("advancedrtp.bypass.cooldown", "rtp.nocooldown")
            .permission("advancedrtp.bypass.delay", "rtp.nodelay")
            .permission("advancedrtp.admin", "rtp.admin")
            .build();
}
