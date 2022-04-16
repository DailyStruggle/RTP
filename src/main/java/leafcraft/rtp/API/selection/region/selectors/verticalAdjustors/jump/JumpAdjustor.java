package leafcraft.rtp.api.selection.region.selectors.verticalAdjustors.jump;

import leafcraft.rtp.api.selection.region.selectors.verticalAdjustors.GenericVerticalAdjustorKeys;
import leafcraft.rtp.api.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import leafcraft.rtp.api.substitutions.RTPBlock;
import leafcraft.rtp.api.substitutions.RTPChunk;
import leafcraft.rtp.api.substitutions.RTPLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class JumpAdjustor extends VerticalAdjustor<JumpAdjustorKeys> {
    protected JumpAdjustor(List<Predicate<RTPBlock>> verifiers, EnumMap<JumpAdjustorKeys, Object> def) {
        super(JumpAdjustorKeys.class,"jump",verifiers, def);
    }

    List<String> keys = Arrays.stream(GenericVerticalAdjustorKeys.values()).map(Enum::name).collect(Collectors.toList());
    @Override
    public Collection<String> keys() {
        return keys;
    }

    @Override
    public @Nullable RTPLocation adjust(@NotNull RTPChunk chunk) {
        int maxY = getNumber(JumpAdjustorKeys.maxY, 320L).intValue();
        int minY = getNumber(JumpAdjustorKeys.minY, 0L).intValue();
        int step = getNumber(JumpAdjustorKeys.step, 0).intValue();
        Boolean requireSkyLight = (Boolean) getData().getOrDefault(JumpAdjustorKeys.requireSkyLight, false);

        int oldY = minY;

        //enforce valid inputs
        step = Math.max(step,1);
        step = Math.min(step,(maxY-minY)/8);

        for(int it_len = step; it_len > 2; it_len = it_len/2) {
            int i = minY;
            for(; i < maxY; i+= it_len) {
                int skylight = 15;
                RTPBlock block1 = chunk.getBlockAt(7, i, 7);
                RTPBlock block2 = chunk.getBlockAt(7, i+1, 7);
                if(requireSkyLight) skylight = block2.skyLight();
                if(block1.isAir() && block2.isAir() && skylight > 7) {
                    minY = oldY;
                    maxY = i;
                    break;
                }
                if(i > maxY-it_len) return null;
                oldY = i;
            }
        }

        for(int i = minY; i < maxY; i++) {
            int skylight = 15;
            RTPBlock block1 = chunk.getBlockAt(7, i, 7);
            RTPBlock block2 = chunk.getBlockAt(7, i+1, 7);
            if(requireSkyLight) skylight = block2.skyLight();
            if(block1.isAir() && block2.isAir() && skylight > 7) {
                return block1.getLocation();
            }
        }

        return null;
    }

    @Override
    public boolean testPlacement(@NotNull RTPBlock block) {
        for (Predicate<RTPBlock> rtpLocationPredicate : verifiers) {
            if (!rtpLocationPredicate.test(block))
                return false;
        }
        return true;
    }
}