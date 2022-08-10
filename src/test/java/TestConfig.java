import commonTestImpl.TestRTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class TestConfig {
    @Test
    void TestConfigSave() {
        //initialize to create config files
        RTP rtp = new RTP(new TestRTPServerAccessor());

        rtp.executeSyncTasks(Long.MAX_VALUE);
        rtp.executeAsyncTasks(Long.MAX_VALUE);

        //modify values
        ConfigParser<ConfigKeys> parser = (ConfigParser<ConfigKeys>) rtp.configs.getParser(ConfigKeys.class);
        parser.set(ConfigKeys.cancelDistance,5);

        try {
            parser.save();
        } catch (IOException e) {
            e.printStackTrace();
            Assertions.fail();
            return;
        }

        Assertions.assertEquals(parser.getNumber(ConfigKeys.cancelDistance,0.0).longValue(),5);
    }
}
