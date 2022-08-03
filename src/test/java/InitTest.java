import commonTestImpl.TestRTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import org.junit.jupiter.api.Test;

public class InitTest {
    private RTP rtp;

    @Test
    void TestStartup() {
        rtp = new RTP(new TestRTPServerAccessor());

        rtp.executeSyncTasks(Long.MAX_VALUE);
        rtp.executeAsyncTasks(Long.MAX_VALUE);
    }
}
