import commonTestImpl.TestRTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import org.junit.jupiter.api.Test;

public class InitTest {

    @Test
    void TestStartup() {
        RTP.serverAccessor = new TestRTPServerAccessor();
        RTP rtp = new RTP();
    }
}
