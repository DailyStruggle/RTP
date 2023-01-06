import commonTestImpl.TestRTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.simpleyaml.configuration.file.YamlFile;

import java.util.AbstractMap;
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
        RTP.getInstance().databaseAccessor = database;
        database.startup();

        Map<String, YamlFile> connect = database.connect();
        Assertions.assertNotNull(connect);
        Assertions.assertNotEquals(0,connect.size());
        DatabaseAccessor.TableObj key = new DatabaseAccessor.TableObj("teleportDelay");
        Assertions.assertNotNull(key);
        Assertions.assertNotNull(key.object);

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> testMap = new HashMap<>();
        testMap.put(key,new DatabaseAccessor.TableObj(2));
        database.write(connect,"config.yml",testMap);

        @NotNull Optional<Map<String, Object>> read = database.read(connect,
                "config.yml",
                new AbstractMap.SimpleEntry<>(key.object.toString(),5));
        Assertions.assertNotNull(read);
        Assertions.assertTrue(read.isPresent());
        Assertions.assertEquals(2L,read.get().get("teleportDelay"));

        testMap.put(key,new DatabaseAccessor.TableObj(5));
        database.write(connect,"config.yml",testMap);

        database.disconnect(connect);

        connect = database.connect();
        read = database.read(connect,
                "config.yml",
                new AbstractMap.SimpleEntry<>(key.object.toString(),2));
        Assertions.assertNotNull(read);
        Assertions.assertTrue(read.isPresent());
        Assertions.assertEquals(5,read.get().get("teleportDelay"));
        database.disconnect(connect);
    }
}
