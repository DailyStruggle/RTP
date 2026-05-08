package io.github.dailystruggle.effectsapi.fabric.LocalEffects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.fabric.FabricRegistryCompat;
import io.github.dailystruggle.effectsapi.fabric.LocalEffects.enums.FabricPotionKeys;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.EnumMap;

/**
 * Fabric counterpart of
 * {@code io.github.dailystruggle.effectsapi.bukkit.LocalEffects.PotionEffect}.
 * Applies a Mojmap {@link MobEffect} to the target {@link ServerPlayer}
 * via {@code ServerPlayer#addEffect}.
 *
 * <p>Note on Mojmap drift: {@code MobEffectInstance(MobEffect, int, int)}
 * was the long-stable shape through 1.20.x. On 1.20.5+ it became
 * {@code MobEffectInstance(Holder<MobEffect>, int, int)}. We construct via
 * the Holder overload (matching the 1.21.1 build target) and let earlier
 * MC versions get patched via the rtp-fabric multi-version directories.
 */
public class FabricPotionEffect extends Effect<FabricPotionKeys> {

    private static final MobEffect DEFAULT_POTION = FabricRegistryCompat.resolve(
            BuiltInRegistries.MOB_EFFECT,
            ResourceLocation.tryParse("minecraft:speed"));

    public FabricPotionEffect() throws IllegalArgumentException {
        super(new EnumMap<>(FabricPotionKeys.class));
        EnumMap<FabricPotionKeys, Object> d = getData();
        d.put(FabricPotionKeys.TYPE, DEFAULT_POTION);
        d.put(FabricPotionKeys.DURATION, 200); // 10s
        d.put(FabricPotionKeys.AMPLIFIER, 0);
        this.data = d;
        this.defaults = d.clone();
    }

    @Override
    public void run() {
        if (!(target instanceof ServerPlayer)) return;
        ServerPlayer player = (ServerPlayer) target;

        Object typeObj = data.get(FabricPotionKeys.TYPE);
        if (!(typeObj instanceof MobEffect)) return;
        MobEffect potion = (MobEffect) typeObj;

        int duration  = numAsInt(data.get(FabricPotionKeys.DURATION),  200);
        int amplifier = numAsInt(data.get(FabricPotionKeys.AMPLIFIER), 0);

        Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(potion);
        player.addEffect(new MobEffectInstance(holder, duration, amplifier));
    }

    @Override
    public String toPermission() {
        return String.valueOf(data.get(FabricPotionKeys.TYPE)) + "."
                + data.get(FabricPotionKeys.DURATION) + "."
                + data.get(FabricPotionKeys.AMPLIFIER);
    }

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    private static final FabricPotionKeys[] KEY_ORDER = {
            FabricPotionKeys.TYPE, FabricPotionKeys.DURATION, FabricPotionKeys.AMPLIFIER
    };

    private static int numAsInt(Object o, int fallback) {
        return (o instanceof Number) ? ((Number) o).intValue() : fallback;
    }
}
