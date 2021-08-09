import leafcraft.rtp.tools.selection.RandomSelect;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.junit.jupiter.api.Test;

public class RandomSelectTest {

    @Test
    public void translateTest() {
        long len = 100l;

        int[] xz = {31,-59};
        int cr = 1024/16;
        System.out.println("initial coords: " + xz[0] + ", " + xz[1]);

        double res = RandomSelect.xzToCircleLocation(cr, xz[0],xz[1],0,0);
        xz = RandomSelect.circleLocationToXZ(cr,0,0,res);

        System.out.println("nearest circle location: " + res);
        System.out.println("nearest circle coords: " + xz[0] + ", " + xz[1]);

        res = RandomSelect.xzToSquareLocation(cr, xz[0],xz[1],0,0);
        xz = RandomSelect.squareLocationToXZ(cr,0,0,res);

        System.out.println("nearest square location: " + res);
        System.out.println("nearest square coords: " + xz[0] + ", " + xz[1]);


        res = RandomSelect.xzToSquareLocation(cr, xz[0],xz[1],0,0);
        xz = RandomSelect.squareLocationToXZ(cr,0,0,res);

        System.out.println("nearest square location: " + res);
        System.out.println("nearest square coords: " + xz[0] + ", " + xz[1]);

    }
}
