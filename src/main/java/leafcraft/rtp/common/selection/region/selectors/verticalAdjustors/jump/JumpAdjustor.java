package leafcraft.rtp.common.selection.region.selectors.verticalAdjustors.jump;

import leafcraft.rtp.common.selection.region.selectors.verticalAdjustors.GenericVerticalAdjustorKeys;
import leafcraft.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import leafcraft.rtp.common.substitutions.RTPBlock;
import leafcraft.rtp.common.substitutions.RTPChunk;
import leafcraft.rtp.common.substitutions.RTPLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class JumpAdjustor extends VerticalAdjustor<JumpAdjustorKeys> {
    private static final EnumMap<JumpAdjustorKeys,Object> defaults = new EnumMap<>(JumpAdjustorKeys.class);
    static {
        defaults.put(JumpAdjustorKeys.maxY, 127);
        defaults.put(JumpAdjustorKeys.minY,32);
        defaults.put(JumpAdjustorKeys.step, 0);
        defaults.put(JumpAdjustorKeys.requireSkyLight,true);
    }

    public JumpAdjustor(List<Predicate<RTPBlock>> verifiers) {
        super(JumpAdjustorKeys.class,"jump",verifiers, defaults);
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

    @Override
    public int minY() {
        return getNumber(JumpAdjustorKeys.minY,0).intValue();
    }

    @Override
    public int maxY() {
        return getNumber(JumpAdjustorKeys.maxY,256).intValue();
    }
}