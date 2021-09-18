package leafcraft.rtp.tools.configuration;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.SendMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.logging.Level;

public class Lang {
    private FileConfiguration config;

    public Lang(RTP plugin) {
        File f = new File(plugin.getDataFolder(), "lang.yml");
        if(!f.exists())
        {
            plugin.saveResource("lang.yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration(f);

        if( 	(this.config.getDouble("version") < 1.9) ) {
            Bukkit.getLogger().log(Level.WARNING, getLog("oldFile", "lang.yml"));
            update(plugin);

            f = new File(plugin.getDataFolder(), "lang.yml");
            this.config = YamlConfiguration.loadConfiguration(f);
        }
    }

    public String getLog(String key) {
        String msg = this.config.getString(key,"");
        if(msg == null) return "";
        msg = SendMessage.format(Bukkit.getOfflinePlayer(new UUID(0,0)).getPlayer(),msg);
        return msg;
    }

    public String getLog(String key, String placeholder) {
        String msg = this.getLog(key);

        String replace;
        switch(key) {
            case "oldFile": replace = "[filename]"; break;
            case "newWorld":
            case "invalidWorld":
            case "noGlobalPerms": replace = "[worldName]"; break;
            case "cooldownMessage" :
            case "delayMessage": replace = "[time]"; break;
            case "unsafe":
            case "teleportMessage": replace = "[numAttempts]"; break;
            case "badArg":
            case "noPerms": replace = "[arg]"; break;
            case "notEnoughMoney": replace = "[money]"; break;
            case "startFill" :
            case "fillCancel" :
            case "fillNotRunning" :
            case "fillStatus" :
            case "fillRunning": replace = "[region]"; break;
            default: replace = "[placeholder]";
        }

        return msg.replace(replace, placeholder);
    }

    //update config files based on version number
    private void update(RTP plugin) {
        FileStuff.renameFiles(plugin,"lang");
        Map<String, Object> oldValues = this.config.getValues(false);
        // Read default config to keep comments
        ArrayList<String> linesInDefaultConfig = new ArrayList<>();
        try {
            Scanner scanner = new Scanner(
                    new File(plugin.getDataFolder().getAbsolutePath() + File.separator + "lang.yml"));
            while (scanner.hasNextLine()) {
                linesInDefaultConfig.add(scanner.nextLine() + "");
            }
            scanner.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        ArrayList<String> newLines = new ArrayList<>();
        for (String line : linesInDefaultConfig) {
            String newline = line;
            if (line.startsWith("version:")) {
                newline = "version: 1.9";
            } else {
                for (String node : oldValues.keySet()) {
                    if (line.startsWith(node + ":")) {
                        String quotes = "\"";
                        newline = node + ": " + quotes + oldValues.get(node).toString() + quotes;
                        break;
                    }
                }
            }
            newLines.add(newline);
        }

        FileWriter fw;
        String[] linesArray = newLines.toArray(new String[linesInDefaultConfig.size()]);
        try {
            fw = new FileWriter(plugin.getDataFolder().getAbsolutePath() + File.separator + "lang.yml");
            for (String s : linesArray) {
                fw.write(s + "\n");
            }
            fw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
