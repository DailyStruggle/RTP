package leafcraft.rtp.tools.Configuration;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.selection.TeleportRegion;
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

	public final int teleportDelay, cancelDistance, teleportCooldown, maxAttempts, queuePeriod, minTPS, vd;
	public final double price;
	public final boolean rerollLiquid, rerollWorldGuard, rerollGriefPrevention;

	public final int nearSelfPrice, nearOtherPrice;

	public Config(RTP plugin, Lang lang) {
		this.plugin = plugin;

		File f = new File(this.plugin.getDataFolder(), "config.yml");
		if(!f.exists())
		{
			plugin.saveResource("config.yml", false);
		}
		this.config = YamlConfiguration.loadConfiguration(f);

		if( 	(this.config.getDouble("version") < 1.8) ) {
			Bukkit.getLogger().log(Level.WARNING, lang.getLog("oldFile", "config.yml"));
			update();

			f = new File(this.plugin.getDataFolder(), "config.yml");
			this.config = YamlConfiguration.loadConfiguration(f);
		}

		this.rerollLiquid = config.getBoolean("rerollLiquid",true);
		this.rerollWorldGuard = config.getBoolean("rerollWorldGuard",true);
		this.rerollGriefPrevention = config.getBoolean("rerollGriefPrevention",true);

		this.teleportDelay = 20*config.getInt("teleportDelay",2);
		this.cancelDistance = config.getInt("cancelDistance",2);
		this.teleportCooldown = config.getInt("teleportCooldown",300);
		this.maxAttempts = config.getInt("maxAttempts",100);
		this.queuePeriod = config.getInt("queuePeriod",30);
		this.minTPS = config.getInt("minTPS",19);
		this.price = config.getDouble("price", Double.MAX_VALUE);

		this.nearSelfPrice = config.getInt("nearSelfPrice", Integer.MAX_VALUE);
		this.nearOtherPrice = config.getInt("nearOtherPrice", Integer.MAX_VALUE);

		int vd = config.getInt("viewDistance",10);
		if(vd > Bukkit.getViewDistance()) vd = Bukkit.getViewDistance();
		this.vd = vd;
	}

	private void update() {
		FileStuff.renameFiles(plugin,"config");
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
				newline = "version: 1.8";
			} else {
				for (String node : oldValues.keySet()) {
					if (line.startsWith(node + ":")) {
						newline = node + ": " + oldValues.get(node).toString();
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

	public List<String> getConsoleCommands() {
		return config.getStringList("consoleCommands");
	}

	public List<String> getPlayerCommands() {
		return config.getStringList("playerCommands");
	}
}
