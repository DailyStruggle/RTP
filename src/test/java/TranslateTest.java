import leafcraft.rtp.tools.selection.Translate;
import org.junit.jupiter.api.Test;

public class TranslateTest {

    @Test
    public void translateTest() {
        long len = 100l;

        int[] xz = {31,-59};
        int cr = 1024/16;
        System.out.println("initial coords: " + xz[0] + ", " + xz[1]);

        double res = Translate.xzToCircleLocation(cr, xz[0],xz[1],0,0);
        xz = Translate.circleLocationToXZ(cr,0,0,res);

        System.out.println("nearest circle location: " + res);
        System.out.println("nearest circle coords: " + xz[0] + ", " + xz[1]);

        res = Translate.xzToSquareLocation(cr, xz[0],xz[1],0,0);
        xz = Translate.squareLocationToXZ(cr,0,0,res);

        System.out.println("nearest square location: " + res);
        System.out.println("nearest square coords: " + xz[0] + ", " + xz[1]);


        res = Translate.xzToSquareLocation(cr, xz[0],xz[1],0,0);
        xz = Translate.squareLocationToXZ(cr,0,0,res);

        System.out.println("nearest square location: " + res);
        System.out.println("nearest square coords: " + xz[0] + ", " + xz[1]);

    }
}
