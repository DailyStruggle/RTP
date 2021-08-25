import leafcraft.rtp.tools.selection.Translate;
import org.junit.jupiter.api.Test;

public class TranslateTest {

    @Test
    public void translateTest() {
        long len = 100l;

        int[] xz = {500,0};
        int[] xzRes;
        int cr = 0;
        System.out.println("initial coords: " + xz[0] + ", " + xz[1]);

        double res = Translate.xzToCircleLocation(cr, xz[0],xz[1],0,0);
        xzRes = Translate.circleLocationToXZ(cr,0,0,res);

        System.out.println("nearest circle location: " + res);
        System.out.println("nearest circle coords: " + xzRes[0] + ", " + xzRes[1]);

        res = Translate.xzToCircleLocation(cr, xz[0],xz[1],0,0);
        xzRes = Translate.circleLocationToXZ(cr,0,0,res);

        System.out.println("nearest circle location: " + res);
        System.out.println("nearest circle coords: " + xzRes[0] + ", " + xzRes[1]);

        res = Translate.xzToSquareLocation(cr, xz[0],xz[1],0,0);
        xzRes = Translate.squareLocationToXZ(cr,0,0,res);

        System.out.println("nearest square location: " + res);
        System.out.println("nearest square coords: " + xzRes[0] + ", " + xzRes[1]);

        res = Translate.xzToSquareLocation(cr, xz[0],xz[1],0,0);
        xzRes = Translate.squareLocationToXZ(cr,0,0,res);

        System.out.println("nearest square location: " + res);
        System.out.println("nearest square coords: " + xzRes[0] + ", " + xzRes[1]);

    }
}
