package io.github.dailystruggle.rtp.common.serverSide.substitutions;

public interface RTPBlock {
    //todo: relevant block checks
    RTPLocation getLocation();
    boolean isAir();
    int x();
    int y();
    int z();
    RTPWorld world();
    int skyLight();
    String getMaterial();
}
