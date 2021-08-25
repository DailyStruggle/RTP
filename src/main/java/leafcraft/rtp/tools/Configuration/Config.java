package leafcraft.rtp.tools.Configuration;

import leafcraft.rtp.RTP;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class Config {
	private final RTP plugin;
	private FileConfiguration config;

	public int teleportDelay, cancelDistance, teleportCooldown, maxAttempts, queuePeriod, minTPS;
	public boolean rerollLiquid, rerollWorldGuard, rerollGriefPrevention;


	public Config(RTP plugin, Lang lang) {
		this.plugin = plugin;

		File f = new File(this.plugin.getDataFolder(), "config.yml");
		if(!f.exists())
		{
			plugin.saveResource("config.yml", false);
		}
		this.config = YamlConfiguration.loadConfiguration(f);

		if( 	(this.config.getDouble("version") < 1.5) ) {
			Bukkit.getLogger().log(Level.WARNING, lang.getLog("oldFile", "config.yml"));
			update();

			f = new File(this.plugin.getDataFolder(), "config.yml");
			this.config = YamlConfiguration.loadConfiguration(f);
		}

		this.rerollLiquid = config.getBoolean("rerollLiquid",true);
		this.rerollWorldGuard = config.getBoolean("rerollWorldGuard",true);
		this.rerollGriefPrevention = config.getBoolean("rerollGriefPrevention",true);

		this.teleportDelay = config.getInt("teleportDelay",2);
		this.cancelDistance = config.getInt("cancelDistance",2);
		this.teleportCooldown = config.getInt("teleportCooldown",300);
		this.maxAttempts = config.getInt("maxAttempts",100);
		this.queuePeriod = config.getInt("queuePeriod",30);
		this.minTPS = config.getInt("minTPS",19);
	}

	private void update() {
		FileStuff.renameFiles(plugin,"config");

		plugin.saveResource("config.yml", false);
		Map<String, Object> oldValues = this.config.getValues(false);
		// Read default config to keep comments
		ArrayList<String> linesInDefaultConfig = new ArrayList<>();
		try {
			Scanner scanner = new Scanner(
					new File(plugin.getDataFolder().getAbsolutePath() + File.separator + "config.yml"));
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
				newline = "version: 1.5";
			} else {
				for (String node : oldValues.keySet()) {
					if (line.startsWith(node + ":")) {
						String quotes = "";
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
			fw = new FileWriter(plugin.getDataFolder().getAbsolutePath() + File.separator + "config.yml");
			for (String s : linesArray) {
				fw.write(s + "\n");
			}
			fw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public Object getConfigValue(String name, Object def) {
		return this.config.get(name,def);
	}
}