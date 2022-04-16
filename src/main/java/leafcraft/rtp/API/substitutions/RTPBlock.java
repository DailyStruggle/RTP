package leafcraft.rtp.api.substitutions;

public interface RTPBlock {
    //todo: relevant block checks
    RTPLocation getLocation();
    boolean isAir();
    int x();
    int y();
    int z();
    RTPWorld world();
    int skyLight();
}
