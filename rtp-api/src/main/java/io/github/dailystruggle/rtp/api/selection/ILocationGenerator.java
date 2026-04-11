package io.github.dailystruggle.rtp.api.selection;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface ILocationGenerator {
    CompletableFuture<GenerationResult> getLocation(GenerationContext context);

    GenerationResult getLocation(Object region, GenerationContext context);

    GenerationResult generateLocation(Object region, GenerationContext context);

    GenerationResult getLocation(
            Object region,
            io.github.dailystruggle.rtp.api.entity.RTPCommandSender sender,
            io.github.dailystruggle.rtp.api.entity.RTPPlayer player,
            Set<String> biomeNames);

    GenerationResult getLocation(Object region, Set<String> biomeNames);
}
