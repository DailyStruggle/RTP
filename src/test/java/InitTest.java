import commonTestImpl.TestRTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.AsyncTaskProcessing;
import io.github.dailystruggle.rtp.common.tasks.SyncTaskProcessing;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public class InitTest {

    @Test
    void TestStartup() {
        RTP.serverAccessor = new TestRTPServerAccessor();
        RTP rtp = new RTP();
    }
}
