import leafcraft.rtp.tools.selection.RandomSelect;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.junit.jupiter.api.Test;

public class RandomSelectTimeTest {

    @Test
    public void timeTest() {
        long len = 100l;

        RandomSelectParams nativeParams = new RandomSelectParams("CIRCLE",1500000000,1024,0,0,1.0);
        RandomSelectParams bigParams = new RandomSelectParams("CIRCLE",Long.MAX_VALUE,1024,0,0,1.0);

        Long startTimeNative = System.currentTimeMillis();
        for(long i = 0; i < len; i++) {
            RandomSelect.select(nativeParams);
        }
        Long stopTimeNative = System.currentTimeMillis();
        System.out.println("native operation time: " + (stopTimeNative-startTimeNative));

        Long startTimeBig = System.currentTimeMillis();
        for(long i = 0; i < len; i++) {
            RandomSelect.select(bigParams);
        }
        Long stopTimeBig = System.currentTimeMillis();
        System.out.println("big operation time: " + (stopTimeBig-startTimeBig));

    }
}
