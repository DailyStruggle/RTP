package io.github.dailystruggle.rtp_glide.configuration;

import io.github.dailystruggle.rtp_glide.RTP_Glide;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;

public class FileStuff {

    public static void renameFiles( RTP_Glide plugin, String name ) {
        //load up a list of files to rename
        ArrayList<File> toRename = new ArrayList<>();
        for( int i = 1; i < 1000; i++ ) {
            File file = new File( plugin.getDataFolder().getAbsolutePath() + File.separator + name + ".old"+i );
            if( !file.exists() ) break;
            toRename.add( file );
        }
        //rename them top-down
        for( int i = toRename.size()-1; i >= 0; i-- ) {
            File oldFile = toRename.get( i );
            String fileName = oldFile.getName();
            int oldNum = i+1;
            int newNum = oldNum+1;
            String newFileName = fileName.replace( Integer.toString( oldNum ), Integer.toString( newNum) );
            File newFile = new File( plugin.getDataFolder().getAbsolutePath() + File.separator + newFileName );
            try { //ensure can place
                Files.deleteIfExists( newFile.toPath() );
            } catch ( IOException e ) {
                RTP.log( Level.WARNING, e.getMessage(), e );
            }
            oldFile.getAbsoluteFile().renameTo( newFile.getAbsoluteFile() );
        }
        File oldFile = new File( plugin.getDataFolder().getAbsolutePath() + File.separator + name );
        File newFile = new File( plugin.getDataFolder().getAbsolutePath() + File.separator + name + ".old1" );
        try {
            Files.deleteIfExists( newFile.toPath() );
        } catch ( IOException e ) {
            RTP.log( Level.WARNING, e.getMessage(), e );
        }
        oldFile.getAbsoluteFile().renameTo( newFile.getAbsoluteFile() );

        plugin.saveResource( name, true );
    }
}
