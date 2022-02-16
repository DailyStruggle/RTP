package leafcraft.rtp.tools.configuration;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.SendMessage;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class Config {
	private final RTP plugin;
	private FileConfiguration config;

	public final int teleportDelay, cancelDistance, teleportCooldown, maxAttempts, queuePeriod, minTPS, vd;
	public final double price;
	public final boolean rerollWorldGuard, rerollGriefDefender, rerollGriefPrevention, rerollTownyAdvanced,
			rerollHuskTowns, rerollFactions, rerollLands, rerollRedProtect;

	public final boolean postTeleportQueueing;

	public final double nearSelfPrice, nearOtherPrice;

	public final double biomePrice;

	public final int invulnerabilityTime;

	public final int safetyRadius, platformRadius, platformAirHeight, platformDepth;

	public Material platformMaterial;
	private final Set<Material> unsafeBlocks = new HashSet<>();
	private boolean checkWaterlogged = false;

	public final int blindnessDuration;

	public final String title, subTitle;
	public final int fadeIn, stay, fadeOut;

	public final boolean refund;

	public final boolean biomeWhitelist;
	public final Set<Biome> biomes;

	public final boolean syncLoading;

	public final boolean checkChunks;

	public final boolean onEventParsing;

	public final boolean effectParsing;

	public final boolean timeit;

	public Config(RTP plugin, Lang lang) {
		this.plugin = plugin;

		File f = new File(this.plugin.getDataFolder(), "config.yml");
		if(!f.exists())
		{
			plugin.saveResource("config.yml", false);
		}
		this.config = YamlConfiguration.loadConfiguration(f);

		String versionStr = this.config.getString("version");

		String[] versionArr = Objects.requireNonNull(versionStr).split("\\.");
		int version1 = Integer.parseInt(versionArr[0]);
		int version2 = Integer.parseInt(versionArr[1]);

		if( (version1 < 2) || (version1==2 && version2 < 10) ) {
			SendMessage.sendMessage(Bukkit.getConsoleSender(), lang.getLog("oldFile", "config.yml"));
			update();

			f = new File(this.plugin.getDataFolder(), "config.yml");
			this.config = YamlConfiguration.loadConfiguration(f);
		}

		this.rerollWorldGuard = config.getBoolean("rerollWorldGuard",true);
		this.rerollGriefDefender = config.getBoolean("rerollGriefDefender",true);
		this.rerollGriefPrevention = config.getBoolean("rerollGriefPrevention",true);
		this.rerollTownyAdvanced = config.getBoolean("rerollTownyAdvanced",true);
		this.rerollHuskTowns = config.getBoolean("rerollHuskTowns",true);
		this.rerollFactions = config.getBoolean("rerollFactions",true);
		this.rerollLands = config.getBoolean("rerollLands",true);
		this.rerollRedProtect = config.getBoolean("rerollRedProtect",true);

		this.teleportDelay = 20*config.getInt("teleportDelay",2);
		this.cancelDistance = config.getInt("cancelDistance",2);
		this.teleportCooldown = config.getInt("teleportCooldown",300);
		this.maxAttempts = config.getInt("maxAttempts",100);
		this.queuePeriod = config.getInt("queuePeriod",30);
		this.minTPS = config.getInt("minTPS",19);
		this.price = config.getDouble("price", Double.MAX_VALUE);

		this.nearSelfPrice = config.getDouble("nearSelfPrice", Double.MAX_VALUE);
		this.nearOtherPrice = config.getDouble("nearOtherPrice", Double.MAX_VALUE);

		this.biomePrice = config.getDouble("biomePrice", Double.MAX_VALUE);

		int vd = config.getInt("viewDistance",10);
		if(vd > Bukkit.getViewDistance()) vd = Bukkit.getViewDistance();
		this.vd = vd;

		int invulnerabilityTime = config.getInt("invulnerabilityTime", 0);
		if(invulnerabilityTime < 0) invulnerabilityTime = 0;
		this.invulnerabilityTime = invulnerabilityTime;

		int safetyRadius = config.getInt("safetyRadius", 0);
		if(safetyRadius<0) safetyRadius = 0;
		else if(safetyRadius>7) safetyRadius = 7;
		this.safetyRadius = safetyRadius;

		int platformRadius = config.getInt("platformRadius", 0);
		if(platformRadius > 7) platformRadius = 7;
		this.platformRadius = platformRadius;
		this.platformAirHeight = config.getInt("platformAirHeight", 2);
		this.platformDepth = config.getInt("platformDepth", 1);
		this.platformMaterial = Material.getMaterial(Objects.requireNonNull(config.getString("platformMaterial", "SMOOTH_STONE")));
		if(this.platformMaterial == null) this.platformMaterial = Material.getMaterial("COBBLESTONE");

		for(String material : config.getStringList("unsafeBlocks")) {
			if(material.equalsIgnoreCase("WATERLOGGED")) checkWaterlogged = true;
			else unsafeBlocks.add(Material.getMaterial(material));
		}

		this.blindnessDuration = config.getInt("blindnessDuration",0);

		this.title = ChatColor.translateAlternateColorCodes('&', Objects.requireNonNull(config.getString("title", "")));
		this.subTitle = ChatColor.translateAlternateColorCodes('&', Objects.requireNonNull(config.getString("subtitle", "")));
		this.fadeIn = config.getInt("stay",1);
		this.stay = config.getInt("fadeIn",1);
		this.fadeOut = config.getInt("fadeOut",1);

		this.postTeleportQueueing = config.getBoolean("postTeleportQueueing", false);

		this.refund = config.getBoolean("refundOnCancel", false);

		this.biomeWhitelist = config.getBoolean("biomeWhitelist",false);
		this.biomes = new HashSet<>();
		List<String> biomeNames = config.getStringList("biomes");
		for(String biomeName : biomeNames) {
			try{
				this.biomes.add(Biome.valueOf(biomeName.toUpperCase()));
			}
			catch (IllegalArgumentException exception) {
				Bukkit.getLogger().warning("[rtp] unknown biome: " + biomeName);
			}
		}

		this.syncLoading = config.getBoolean("syncLoading",false);

		this.checkChunks = config.getBoolean("checkChunks",false);

		this.onEventParsing = config.getBoolean("onEventParsing",false);

		this.effectParsing = config.getBoolean("effectParsing",false);

		this.timeit = config.getBoolean("timeit",false);
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
			StringBuilder newline = new StringBuilder(line);
			if (line.startsWith("version:")) {
				newline = new StringBuilder("version: \"2.10\"");
			}
			else if(newline.toString().startsWith("  -")) continue;
			else {
				for (String node : oldValues.keySet()) {
					if (line.startsWith(node + ":")) {
						if(config.get(node) instanceof List) {
							Set<Object> duplicateCheck = new HashSet<>();
							newline = new StringBuilder(node + ": ");
							for(Object obj : Objects.requireNonNull(config.getList(node))) {
								if(duplicateCheck.contains(obj)) continue;
								duplicateCheck.add(obj);
								if(obj instanceof String) {
									boolean doQuotes = Material.getMaterial((String) obj) == null;
									if(doQuotes)newline.append("\n  - " + "\"").append(obj).append("\"");
									else newline.append("\n  - ").append(obj);
								}
								else newline.append("\n  - ").append(obj);
							}
						}
						else if(config.get(node) instanceof String) newline = new StringBuilder(node + ": \"" + oldValues.get(node).toString() + "\"");
						else newline = new StringBuilder(node + ": " + oldValues.get(node).toString());
						break;
					}

				}
			}
			newLines.add(newline.toString());
		}

		FileWriter fw;
		String[] linesArray = newLines.toArray(new String[linesInDefaultConfig.size()]);
		try {
			fw = new FileWriter(plugin.getDataFolder().getAbsolutePath() + File.separator + "config.yml");
			for (String s : linesArray) {
				if(s==null) continue;
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

	public boolean isUnsafe(Block block) {
		if(unsafeBlocks.contains(block.getType())) return true;
		return checkWaterlogged
				&& RTPAPI.getServerIntVersion() > 12
				&& block.getBlockData() instanceof Waterlogged
				&& ((Waterlogged) block.getBlockData()).isWaterlogged();
	}
}
