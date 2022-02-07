package leafcraft.rtp.tools.configuration;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.SendMessage;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;

public class FileStuff {

    public static void renameFiles(RTP plugin, String name) {
        //load up a list of files to rename
        ArrayList<File> toRename = new ArrayList<>();
        for(int i = 1; i < 1000; i++) {
            File file = new File(plugin.getDataFolder().getAbsolutePath() + File.separator + name + ".old"+i+".yml");
            if(!file.exists()) break;
            toRename.add(file);
        }
        //rename them top-down so as not to overwrite
        for(int i = toRename.size()-1; i >= 0; i--) {
            File oldFile = toRename.get(i);
            String fileName = oldFile.getName();
            int oldNum = i+1;
            int newNum = oldNum+1;
            String newFileName = fileName.replace(Integer.toString(oldNum), Integer.toString(newNum));
            File newFile = new File(plugin.getDataFolder().getAbsolutePath() + File.separator + newFileName);
            try { //ensure can place
                Files.deleteIfExists(newFile.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
            boolean b = oldFile.getAbsoluteFile().renameTo(newFile.getAbsoluteFile());
            if(!b) SendMessage.sendMessage(Bukkit.getConsoleSender(),
                    "RTP - unable to rename file:" + oldFile.getAbsoluteFile());
        }

        //rename the last one
        File oldFile = new File(plugin.getDataFolder().getAbsolutePath() + File.separator + name + ".yml");
        File newFile = new File(plugin.getDataFolder().getAbsolutePath() + File.separator + name + ".old1.yml");
        try {
            Files.deleteIfExists(newFile.toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        boolean b = oldFile.getAbsoluteFile().renameTo(newFile.getAbsoluteFile());
        if(!b) SendMessage.sendMessage(Bukkit.getConsoleSender(),
                "RTP - unable to rename file:" + oldFile.getAbsoluteFile());

        plugin.saveResource(name + ".yml", true);
    }
}
