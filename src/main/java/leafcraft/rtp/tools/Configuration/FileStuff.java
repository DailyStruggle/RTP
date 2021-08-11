package leafcraft.rtp.tools.Configuration;

import leafcraft.rtp.RTP;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;

public class FileStuff {

    public static void renameFiles(RTP plugin, String name) {
        int num = 0;
        //load up a list of files to rename
        ArrayList<File> toRename = new ArrayList<>();
        for(int i = 1; i < 1000; i++) {
            File file = new File(plugin.getDataFolder().getAbsolutePath() + File.separator + name + ".old"+i+".yml");
            if(!file.exists()) break;
            toRename.add(file);
        }
        //rename them top-down
        for(Integer i = toRename.size()-1; i >= 0; i--) {
            File oldFile = toRename.get(i);
            String fileName = oldFile.getName();
            Integer oldNum = i+1;
            Integer newNum = oldNum+1;
            String newFileName = fileName.replace(oldNum.toString(),newNum.toString());
            File newFile = new File(plugin.getDataFolder().getAbsolutePath() + File.separator + newFileName);
            try { //ensure can place
                Files.deleteIfExists(newFile.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
            oldFile.getAbsoluteFile().renameTo(newFile.getAbsoluteFile());
        }
        File oldFile = new File(plugin.getDataFolder().getAbsolutePath() + File.separator + name + ".yml");
        File newFile = new File(plugin.getDataFolder().getAbsolutePath() + File.separator + name + ".old1.yml");
        try {
            Files.deleteIfExists(newFile.toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        oldFile.getAbsoluteFile().renameTo(newFile.getAbsoluteFile());

        plugin.saveResource(name + ".yml", true);
    }
}
