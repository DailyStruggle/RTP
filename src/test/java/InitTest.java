import commonTestImpl.TestRTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import org.junit.jupiter.api.Test;

public class InitTest {

    @Test
    void TestStartup() {
        RTP.serverAccessor = new TestRTPServerAccessor();
        //initialize to create config files
        RTP rtp = new RTP();

        int i = 0;
        while ( rtp.startupTasks.size()>0 ) {
            rtp.startupTasks.execute( Long.MAX_VALUE );
            i++;
            if( i>50 ) return;
        }
    }
}
