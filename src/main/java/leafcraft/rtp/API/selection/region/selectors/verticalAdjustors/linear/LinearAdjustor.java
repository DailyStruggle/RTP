package leafcraft.rtp.api.selection.region.selectors.verticalAdjustors.linear;

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

public class LinearAdjustor extends VerticalAdjustor<GenericVerticalAdjustorKeys> {
    protected LinearAdjustor(List<Predicate<RTPBlock>> verifiers, EnumMap<GenericVerticalAdjustorKeys, Object> def) {
        super(GenericVerticalAdjustorKeys.class, "linear", verifiers, def);
    }

    List<String> keys = Arrays.stream(GenericVerticalAdjustorKeys.values()).map(Enum::name).collect(Collectors.toList());
    @Override
    public Collection<String> keys() {
        return keys;
    }

    @Override
    public @Nullable RTPLocation adjust(@NotNull RTPChunk input) {
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
}