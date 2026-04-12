package io.github.dailystruggle.rtp.api.selection;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface ILocationGenerator {
    CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context);

    CompletableFuture<GenerationResult> generateLocation(Object region, GenerationContext context);

    CompletableFuture<GenerationResult> getLocation(
            Object region,
            io.github.dailystruggle.rtp.api.entity.RTPCommandSender sender,
            io.github.dailystruggle.rtp.api.entity.RTPPlayer player,
            Set<String> biomeNames);

    CompletableFuture<GenerationResult> getLocation(Object region, Set<String> biomeNames);
}
