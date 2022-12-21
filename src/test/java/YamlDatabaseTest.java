import commonTestImpl.TestRTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.simpleyaml.configuration.file.YamlFile;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class YamlDatabaseTest {

    @Test
    void TestYamlFileConnection() {
        RTP.serverAccessor = new TestRTPServerAccessor();
        RTP rtp = new RTP();

        int i = 0;
        while (rtp.startupTasks.size()>0) {
            rtp.startupTasks.execute(Long.MAX_VALUE);
            i++;
            if(i>50) return;
        }

        YamlFileDatabase database = new YamlFileDatabase(RTP.configs.pluginDirectory);
        Map<String, YamlFile> connect = database.connect();
        Assertions.assertNotNull(connect);
        Assertions.assertNotEquals(0,connect.size());
        DatabaseAccessor.TableObj key = new DatabaseAccessor.TableObj("teleportDelay");
        Assertions.assertNotNull(key);
        Assertions.assertNotNull(key.object);

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> testMap = new HashMap<>();
        testMap.put(key,new DatabaseAccessor.TableObj(2));
        database.write(connect,"config.yml",testMap);

        Optional<DatabaseAccessor.TableObj> read = database.read(connect, "config.yml", key);
        Assertions.assertNotNull(read);
        Assertions.assertTrue(read.isPresent());
        Assertions.assertEquals(2L,read.get().object);

        testMap.put(key,new DatabaseAccessor.TableObj(5));
        database.write(connect,"config.yml",testMap);

        database.disconnect(connect);

        connect = database.connect();
        read = database.read(connect, "config.yml", key);
        Assertions.assertNotNull(read);
        Assertions.assertTrue(read.isPresent());
        Assertions.assertTrue(read.get().equals(5));
        database.disconnect(connect);
    }
}
