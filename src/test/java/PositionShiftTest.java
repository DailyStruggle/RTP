import leafcraft.rtp.tools.Cache;
import org.junit.jupiter.api.Test;

import java.util.UUID;

public class PositionShiftTest {

    @Test
    public void translateTest() {
        Cache cache = new Cache(null);

        UUID uuid = new UUID(0,0);
        long pos = 100;
        System.out.println("adding position 100 to the list");
        cache.addBadChunk(uuid,pos);
        System.out.println("check length below pos: " + cache.shiftedLocation(uuid,pos-1));
        System.out.println("check length at pos: " + cache.shiftedLocation(uuid,pos));
        System.out.println("check length above pos: " + cache.shiftedLocation(uuid,pos+10));

        System.out.println();
        System.out.println();
        System.out.println("adding position 101 to the list");
        cache.addBadChunk(uuid,pos+1);
        System.out.println("check new list size: " + cache.badChunks.size());
        System.out.println("check length below pos: " + cache.shiftedLocation(uuid,pos-1));
        System.out.println("check length at pos: " + cache.shiftedLocation(uuid,pos));
        System.out.println("check length above pos: " + cache.shiftedLocation(uuid,pos+10));

        System.out.println();
        System.out.println();
        System.out.println("adding position 103 to the list");
        cache.addBadChunk(uuid,pos+3);
        System.out.println("adding position 105 to the list");
        cache.addBadChunk(uuid,pos+5);
        System.out.println("adding position 102 to the list");
        cache.addBadChunk(uuid,pos+2);
        System.out.println("adding position 104 to the list");
        cache.addBadChunk(uuid,pos+4);
        System.out.println("check new list size: " + cache.badChunks.size());
        System.out.println("check length below pos: " + cache.shiftedLocation(uuid,pos-1));
        System.out.println("check length at pos: " + cache.shiftedLocation(uuid,pos));
        System.out.println("check length at pos+3: " + cache.shiftedLocation(uuid,pos+3));
        System.out.println("check length at pos+10: " + cache.shiftedLocation(uuid,pos+10));

        System.out.println();
        System.out.println();
        System.out.println("adding position 102 to the list");
        cache.addBadChunk(uuid,pos+2);
        System.out.println("check new list size: " + cache.badChunks.size());
        System.out.println("check length below pos: " + cache.shiftedLocation(uuid,pos-1));
        System.out.println("check length at pos: " + cache.shiftedLocation(uuid,pos));
        System.out.println("check length above pos: " + cache.shiftedLocation(uuid,pos+10));



    }


}
