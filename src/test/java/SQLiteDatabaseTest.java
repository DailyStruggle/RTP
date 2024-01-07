import commonTestImpl.TestRTPServerAccessor;
import commonTestImpl.substitutions.TestRTPCommandSender;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.SQLiteDatabaseAccessor;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.util.*;
import java.util.logging.Level;

public class SQLiteDatabaseTest {

    @Test
    void TestSQLiteConnection() {
        try {
            Class.forName( "org.sqlite.JDBC" );
        } catch ( ClassNotFoundException e ) {
            RTP.log( Level.WARNING, "ClassNotFoundException", e );
            return;
        }
        RTP.serverAccessor = new TestRTPServerAccessor();
        RTP rtp = new RTP();

        File databaseDirectory = RTP.configs.pluginDirectory;
        databaseDirectory = new File( databaseDirectory.getAbsolutePath() + File.separator + "database" );
        databaseDirectory.mkdirs();
        SQLiteDatabaseAccessor database = new SQLiteDatabaseAccessor( 
                "jdbc:sqlite:" + databaseDirectory.getAbsolutePath() + File.separator + "RTP.db" );
        RTP.getInstance().databaseAccessor = database;
        RTP.getInstance().databaseAccessor.startup();

        int i = 0;
        while ( rtp.startupTasks.size()>0 ) {
            rtp.startupTasks.execute( Long.MAX_VALUE );
            i++;
            if( i>50 ) return;
        }

        String table = "teleportData";

        UUID playerId = UUID.randomUUID();

        TeleportData inputData = new TeleportData();
        inputData.completed = true;
        inputData.delay = 2;
        inputData.sender = new TestRTPCommandSender();


        Connection connect = database.connect();
        Assertions.assertNotNull( connect );

        DatabaseAccessor.TableObj key = new DatabaseAccessor.TableObj( playerId.toString() );
        Assertions.assertNotNull( key );
        Assertions.assertNotNull( key.object );

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> testMap = new HashMap<>();
        for ( Map.Entry<String, Object> entry : DatabaseAccessor.toColumns( inputData ).entrySet() ) {
            String key1 = entry.getKey();
            Object value = entry.getValue();
            testMap.put( new DatabaseAccessor.TableObj( key1 ),new DatabaseAccessor.TableObj( value) );
        }
        testMap.put( new DatabaseAccessor.TableObj( "UUID" ), new DatabaseAccessor.TableObj( playerId) );
        database.write( connect, table,testMap );

        Optional<Map<String, Object>> read = database.read( connect,
                table,
                new AbstractMap.SimpleEntry<>( "UUID",key.object) );

        Assertions.assertNotNull( read );
        Assertions.assertTrue( read.isPresent() );

        Assertions.assertEquals( "2",read.get().get( "delay") );

        testMap.put( new DatabaseAccessor.TableObj( "delay" ),new DatabaseAccessor.TableObj( 5) );
        database.write( connect,table,testMap );

        database.disconnect( connect );

        connect = database.connect();
        read = database.read( connect,
                table,
                new AbstractMap.SimpleEntry<>( "UUID",key.object) );
        Assertions.assertNotNull( read );
        Assertions.assertTrue( read.isPresent() );

        Assertions.assertEquals( "5",read.get().get( "delay") );
        database.disconnect( connect );
    }
}
