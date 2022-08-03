package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear;

import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.GenericVerticalAdjustorKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPBlock;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class LinearAdjustor extends VerticalAdjustor<GenericVerticalAdjustorKeys> {
    private static final EnumMap<GenericVerticalAdjustorKeys,Object> defaults = new EnumMap<>(GenericVerticalAdjustorKeys.class);
    static {
        defaults.put(GenericVerticalAdjustorKeys.maxY, 127);
        defaults.put(GenericVerticalAdjustorKeys.minY,32);
        defaults.put(GenericVerticalAdjustorKeys.direction, 0);
        defaults.put(GenericVerticalAdjustorKeys.requireSkyLight,true);
    }

    public LinearAdjustor(List<Predicate<RTPBlock>> verifiers) {
        super(GenericVerticalAdjustorKeys.class, "linear", verifiers, defaults);
    }

    @Override
    public List<String> keys() {
        return Arrays.stream(GenericVerticalAdjustorKeys.values()).map(Enum::name).collect(Collectors.toList());
    }

    @Override
    public @Nullable
    RTPLocation adjust(@NotNull RTPChunk input) {
        RTPBlock resBlock;

        int maxY = getNumber(GenericVerticalAdjustorKeys.maxY, 320L).intValue();
        int minY = getNumber(GenericVerticalAdjustorKeys.minY, 0L).intValue();
        int dir = getNumber(GenericVerticalAdjustorKeys.direction, 0).intValue();

        switch(dir) {
            case 0 -> { //bottom up
                for (int i = minY; i < maxY; i++) {
                    resBlock = input.getBlockAt(7,i,7);
                    if(testPlacement(resBlock)) return resBlock.getLocation();
                }
            }
            case 1 -> { //top down
                for (int i = maxY; i > minY; i--) {
                    resBlock = input.getBlockAt(7,i,7);
                    if(testPlacement(resBlock)) return resBlock.getLocation();
                }
            }
            case 2 -> { //middle out
                int maxDistance = (maxY - minY)/2; //dividing distance is more overflow-safe than simple average
                int middle = minY + maxDistance;
                for (int i = 0; i <= maxDistance; i++) {
                    //try top
                    resBlock = input.getBlockAt(7,middle+i,7);
                    if(testPlacement(resBlock)) return resBlock.getLocation();

                    //try bottom
                    resBlock = input.getBlockAt(7,middle-i,7);
                    if(testPlacement(resBlock)) return resBlock.getLocation();
                }
            }
            case 3 -> { //edges in
                int maxDistance = (maxY - minY)/2; //dividing distance is more overflow-safe than simple average
                int middle = minY + maxDistance;
                for (int i = maxDistance; i >= 0; i--) {
                    //try top
                    resBlock = input.getBlockAt(7,middle+i,7);
                    if(testPlacement(resBlock)) return resBlock.getLocation();

                    //try bottom
                    resBlock = input.getBlockAt(7,middle-i,7);
                    if(testPlacement(resBlock)) return resBlock.getLocation();
                }
            }
            default -> { //random order
                //load up a list of possible vertical indices
                List<Integer> trials = new ArrayList<>(maxY-minY+1);
                for (int i = minY; i < maxY; i++) {
                    trials.add(i);
                }

                //randomize order
                Collections.shuffle(trials);

                //try each
                for(int i : trials) {
                    resBlock = input.getBlockAt(7,i,7);
                    if(testPlacement(resBlock)) return resBlock.getLocation();
                }
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
        return getNumber(GenericVerticalAdjustorKeys.minY,0).intValue();
    }

    @Override
    public int maxY() {
        return getNumber(GenericVerticalAdjustorKeys.maxY,256).intValue();
    }
}