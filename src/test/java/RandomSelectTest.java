import leafcraft.rtp.tools.selection.RandomSelect;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.junit.jupiter.api.Test;

public class RandomSelectTest {

    @Test
    public void timeTest() {
        long len = 100l;

        int[] xz = {97,99};
        long res = RandomSelect.xzToCircleLocation(xz[0],xz[1],0,0);
        int[] verify = RandomSelect.circleLocationToXZ(0,0,0,res);

        System.out.println("initial coords: " + xz[0] + ", " + xz[1]);
        System.out.println("nearest circle location: " + res);
        System.out.println("nearest circle coords: " + verify[0] + ", " + verify[1]);

        res = RandomSelect.xzToSquareLocation(xz[0],xz[1],0,0);
        verify = RandomSelect.squareLocationToXZ(0,0,0,res);

        System.out.println("nearest square location: " + res);
        System.out.println("nearest square coords: " + verify[0] + ", " + verify[1]);

    }
}
