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
        RTP.serverAccessor = new TestRTPServerAccessor();
        //initialize to create config files
        RTP rtp = new RTP();

        int i = 0;
        while (rtp.startupTasks.size()>0) {
            rtp.startupTasks.execute(Long.MAX_VALUE);
            i++;
            if(i>50) return;
        }

        //modify values
        ConfigParser<ConfigKeys> parser = (ConfigParser<ConfigKeys>) RTP.configs.getParser(ConfigKeys.class);
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
