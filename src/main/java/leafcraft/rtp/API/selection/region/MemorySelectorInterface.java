package leafcraft.rtp.API.selection.region;

public interface MemorySelectorInterface extends SelectorInterface {
    boolean save(String name);
    boolean load(String name);

    boolean isKnownBad(int x, int z);
}