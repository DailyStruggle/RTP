package leafcraft.rtp.tools;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.RTP;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.opentest4j.TestAbortedException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;

public class Config {
	private SplittableRandom r;
	private final RTP plugin;
	private FileConfiguration config;
	private FileConfiguration worlds;
	private FileConfiguration lang;
	private Cache cache;
	public String version;

	private Set<Material> acceptableAir;

	private enum Shapes {
		SQUARE,
		CIRCLE
	}

	public Config(RTP plugin, Cache cache) {
		this.acceptableAir = new HashSet<>();
		this.acceptableAir.add(Material.AIR);
		this.acceptableAir.add(Material.CAVE_AIR);
		this.acceptableAir.add(Material.VOID_AIR);

		this.cache = cache;

		this.plugin = plugin;
		String s = this.plugin.getServer().getClass().getPackage().getName();
		this.version = s.substring(s.lastIndexOf('.')+1);
		this.refreshConfigs();
	}
	
	
	public void refreshConfigs() {
		this.r = new SplittableRandom();

		//load lang.yml file first
		File f = new File(this.plugin.getDataFolder(), "lang.yml");
		if(!f.exists())
		{
			plugin.saveResource("lang.yml", false);
		}
		this.lang = YamlConfiguration.loadConfiguration(f);

		if( 	(this.lang.getDouble("version") < 1.2) ) {
			Bukkit.getLogger().log(Level.WARNING, this.getLog("oldFile", "lang.yml"));
			updateLang();

			f = new File(this.plugin.getDataFolder(), "lang.yml");
			this.lang = YamlConfiguration.loadConfiguration(f);;
		}

		//load config.yml file
		f = new File(this.plugin.getDataFolder(), "config.yml");
		if(!f.exists())
		{
			plugin.saveResource("config.yml", false);
		}
		this.config = YamlConfiguration.loadConfiguration(f);

		if( 	(this.config.getDouble("version") < 1.1) ) {
			Bukkit.getLogger().log(Level.WARNING, this.getLog("oldFile", "config.yml"));

			updateConfig();

			f = new File(this.plugin.getDataFolder(), "config.yml");
			this.config = YamlConfiguration.loadConfiguration(f);
		}

		//load worlds.yml file
		f = new File(this.plugin.getDataFolder(), "worlds.yml");
		if(!f.exists())
		{
			plugin.saveResource("worlds.yml", false);
		}
		this.worlds = YamlConfiguration.loadConfiguration(f);

		if( 	(this.worlds.getDouble("version") < 1.2) ) {
			Bukkit.getLogger().log(Level.WARNING, this.getLog("oldFile", "worlds.yml"));
			this.renameFileInPluginDir("worlds.yml","worlds.old.yml");

			this.plugin.saveResource("worlds.yml", false);
			this.worlds = YamlConfiguration.loadConfiguration(f);
		}

		//update world list and save
		this.fillWorldsFile();
	}

	//update config files based on version number
	private void updateLang() {
		this.renameFileInPluginDir("lang.yml","lang.old.yml");
		plugin.saveResource("lang.yml", false);
		Map<String, Object> oldValues = this.lang.getValues(false);
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
				newline = "version: 1.2";
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

	private void updateConfig() {
		this.renameFileInPluginDir("config.yml","config.old.yml");
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
				newline = "version: 1.1";
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

	public void fillWorldsFile() {
		renameFileInPluginDir("worlds.yml","worlds.temp.yml");

		final String quotes = "\"";

		ArrayList<String> linesInWorlds = new ArrayList<>();
		String defaultShape = this.worlds.getConfigurationSection("default").getString("shape", "SQUARE");
		Integer defaultRadius = this.worlds.getConfigurationSection("default").getInt("radius", 4096);
		Integer defaultCenterRadius = this.worlds.getConfigurationSection("default").getInt("centerRadius", 1024);
		Integer defaultCenterX = this.worlds.getConfigurationSection("default").getInt("centerX", 0);
		Integer defaultCenterZ = this.worlds.getConfigurationSection("default").getInt("centerZ", 0);
		Double defaultWeight = this.worlds.getConfigurationSection("default").getDouble("weight", 1.0);
		Integer defaultMinY = this.worlds.getConfigurationSection("default").getInt("minY", 48);
		Integer defaultMaxY = this.worlds.getConfigurationSection("default").getInt("maxY", 128);
		Boolean defaultRequireDaylight = this.worlds.getConfigurationSection("default").getBoolean("requireSkyLight", true);
		Boolean defaultRequirePermission = this.worlds.getConfigurationSection("default").getBoolean("requirePermission",true);
		String defaultOverride = this.worlds.getConfigurationSection("default").getString("override","world");

		for(World w : Bukkit.getWorlds()) {
			String permName = "rtp.worlds." + w.getName();
			if(Bukkit.getPluginManager().getPermission(permName) == null) {
				Permission permission = new Permission(permName);
				permission.setDefault(PermissionDefault.OP);
				permission.addParent("rtp.worlds.*",true);
				Bukkit.getPluginManager().addPermission(permission);
			}
		}

		try {
			Scanner scanner = new Scanner(
					new File(plugin.getDataFolder().getAbsolutePath() + File.separator + "worlds.temp.yml"));
			//for each line in original messages file
			String currWorldName = "default";
			while (scanner.hasNextLine()) {
				String s = scanner.nextLine();

				//append at first blank line
				if(!(s.matches(".*[a-z].*")||s.matches(".*[A-Z].*"))) {
					//for each missing world, add some default data
					for(World w : Bukkit.getWorlds()) {
						String worldName = w.getName();
						if(this.worlds.contains(worldName)) continue;
						this.worlds.set(worldName, this.worlds.getConfigurationSection("default"));

						if(linesInWorlds.get(linesInWorlds.size()-1).length() < 4)
							linesInWorlds.set(linesInWorlds.size()-1,"    " + worldName + ":");
						else linesInWorlds.add(worldName + ":");
						linesInWorlds.add("    shape: \"" + defaultShape + "\"");
						linesInWorlds.add("    radius: " + defaultRadius);
						linesInWorlds.add("    centerRadius: " + defaultCenterRadius);
						linesInWorlds.add("    centerX: " + defaultCenterX);
						linesInWorlds.add("    centerZ: " + defaultCenterZ);
						linesInWorlds.add("    minY: " + defaultMinY);
						linesInWorlds.add("    maxY: " + defaultMaxY);
						linesInWorlds.add("    weight: " + defaultWeight);
						if(w.getEnvironment().equals(World.Environment.NORMAL))
							linesInWorlds.add("    requireSkyLight: " + defaultRequireDaylight);
						else
							linesInWorlds.add("    requireSkyLight: " + false);
						if(worldName.equalsIgnoreCase("world"))
							linesInWorlds.add("    requirePermission: " + false);
						else
							linesInWorlds.add("    requirePermission: " + defaultRequirePermission);
						linesInWorlds.add("    override: " + quotes + defaultOverride + quotes);
					}
				}
				else { //if not a blank line
					if(s.startsWith("    shape:"))
						s = "    shape: " + quotes + this.worlds.getConfigurationSection(currWorldName).getString("shape","SQUARE") + quotes;
					else if(s.startsWith("    radius:"))
						s = "    radius: " + this.worlds.getConfigurationSection(currWorldName).getInt("radius",defaultRadius);
					else if(s.startsWith("    centerRadius:"))
						s = "    centerRadius: " + this.worlds.getConfigurationSection(currWorldName).getInt("centerRadius",defaultCenterRadius);
					else if(s.startsWith("    centerX:"))
						s = "    centerX: " + this.worlds.getConfigurationSection(currWorldName).getInt("centerX",defaultCenterX);
					else if(s.startsWith("    centerZ:"))
						s = "    centerZ: " + this.worlds.getConfigurationSection(currWorldName).getInt("centerZ",defaultCenterZ);
					else if(s.startsWith("    weight:"))
						s = "    weight: " + this.worlds.getConfigurationSection(currWorldName).getDouble("weight",defaultWeight);
					else if(s.startsWith("    minY:"))
						s = "    minY: " + this.worlds.getConfigurationSection(currWorldName).getInt("minY",defaultMinY);
					else if(s.startsWith("    maxY:"))
						s = "    maxY: " + this.worlds.getConfigurationSection(currWorldName).getInt("maxY", defaultMaxY);
					else if(s.startsWith("    requireSkyLight:"))
						s = "    requireSkyLight: " + this.worlds.getConfigurationSection(currWorldName).getBoolean("requireSkyLight",defaultRequireDaylight);
					else if(s.startsWith("    requirePermission:"))
						s = "    requirePermission: " + this.worlds.getConfigurationSection(currWorldName).getBoolean("requirePermission",defaultRequirePermission);
					else if(s.startsWith("    override:"))
						s = "    override: " + quotes + this.worlds.getConfigurationSection(currWorldName).getString("override",defaultOverride) + quotes;
					else if(!s.startsWith("#") && !s.startsWith("  ") && !s.startsWith("version") && (s.matches(".*[a-z].*") || s.matches(".*[A-Z].*")))
					{
						currWorldName = s.replace(":","");
					}
				}

				//add line
				linesInWorlds.add(s);
			}
			scanner.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

		FileWriter fw;
		String[] linesArray = linesInWorlds.toArray(new String[linesInWorlds.size()]);
		try {
			fw = new FileWriter(plugin.getDataFolder().getAbsolutePath() + File.separator + "worlds.yml");
			for (String s : linesArray) {
				fw.write(s + "\n");
			}
			fw.close();
			File f = new File(this.plugin.getDataFolder().getAbsolutePath() + File.separator + "worlds.temp.yml");
			f.delete();
		} catch (IOException e) {
			e.printStackTrace();
		}

		//-------------UPDATE INTERNAL VERSION ACCORDINGLY-------------
		this.worlds = YamlConfiguration.loadConfiguration(new File(this.plugin.getDataFolder(), "worlds.yml"));

		//table of worlds ordered by dimension for quick lookup
		Map<World.Environment,List<String>> dimWorldList = new HashMap<>();
		for(World.Environment e : World.Environment.values()) {
			dimWorldList.put(e, new ArrayList<>());
		}
		for(String worldName : this.worlds.getKeys(false)) {
			if(worldName.equals("default") || worldName.equals("version")) continue;
			if(this.checkWorldExists(worldName))
				dimWorldList.get(Bukkit.getWorld(worldName).getEnvironment()).add(worldName);
		}
	}
	
	private void renameFileInPluginDir(String oldName, String newName) {
		File oldFile = new File(this.plugin.getDataFolder().getAbsolutePath() + File.separator + oldName);
		File newFile = new File(this.plugin.getDataFolder().getAbsolutePath() + File.separator + newName);
		try {
			Files.deleteIfExists(newFile.toPath());
		} catch (IOException e) {
			e.printStackTrace();
		}
		oldFile.getAbsoluteFile().renameTo(newFile.getAbsoluteFile());
	}

	public String getWorldPlaceholder(String worldName) {
		return this.worlds.getConfigurationSection(worldName).getString("name");
	}

	public Boolean getWorldPermReq(String worldName) {
		return this.worlds.getConfigurationSection(worldName).getBoolean("requirePermission");
	}

	public String getWorldOverride(String worldName) {
		return this.worlds.getConfigurationSection(worldName).getString("override");
	}

	public String getLog(String key) {
		String msg = this.lang.getString(key);
		msg = ChatColor.translateAlternateColorCodes('&',msg);
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
			default: replace = "[placeholder]";
		}

		return msg.replace(replace, placeholder);
	}

	public Object getConfigValue(String name, Object def) {
		return this.config.get(name,def);
	}

	public Boolean checkWorldExists(String worldName) {
		Boolean bukkitWorldExists = Bukkit.getWorld(worldName)!=null;
		Boolean worldKnown = this.worlds.contains(worldName);
		if( !bukkitWorldExists ) {
			return false;
		}
		else if( !worldKnown ) {
			Bukkit.getLogger().log(Level.INFO,this.getLog("newWorld",worldName));
			Bukkit.getLogger().log(Level.INFO,this.getLog("updatingWorlds"));
			this.fillWorldsFile(); //not optimal but it works
			Bukkit.getLogger().log(Level.INFO,this.getLog("updatedWorlds"));
		}
		return true;
	}

	public Location getRandomLocation(World world) {
		Location res;

		String shapeStr = this.worlds.getConfigurationSection(world.getName()).getString("shape", "CIRCLE");
		Integer radius = this.worlds.getConfigurationSection(world.getName()).getInt("radius", 4096);
		Integer centerRadius = this.worlds.getConfigurationSection(world.getName()).getInt("centerRadius", 1024);
		Integer centerX = this.worlds.getConfigurationSection(world.getName()).getInt("centerX", 0);
		Double weight = this.worlds.getConfigurationSection(world.getName()).getDouble("weight", 1.0);

		Integer centerZ = this.worlds.getConfigurationSection(world.getName()).getInt("centerZ", 0);

		Boolean rerollLiquid = this.config.getBoolean("rerollLiquid",true);

		Shapes shape;
		try{
			shape = Shapes.valueOf(shapeStr.toUpperCase(Locale.ENGLISH));
		}
		catch (IllegalArgumentException exception) {
			shape = Shapes.CIRCLE;
		}

		Double x;
		Double z;
		final double totalSpace;

		switch (shape) {
			case SQUARE: { //square spiral
				totalSpace = (radius - centerRadius) * (radius + centerRadius);
				Double rSpace = totalSpace*Math.pow(r.nextDouble(),weight);

				Double rDistance = Math.sqrt(rSpace+centerRadius*centerRadius);
				Double perimeterStep = 8*(rDistance*(rDistance-rDistance.intValue()));
				Double shortStep = perimeterStep%rDistance;

				if(perimeterStep<rDistance*4) {
					if(perimeterStep<rDistance*2) {
						if(perimeterStep<rDistance) {
							x = rDistance;
							z = shortStep;
						}
						else {
							x = rDistance - shortStep;
							z = rDistance;
						}
					}
					else {
						if(perimeterStep<rDistance*3) {
							x = -shortStep;
							z = rDistance;
						}
						else {
							x = -rDistance;
							z = rDistance - shortStep;
						}
					}
				}
				else {
					if(perimeterStep<rDistance*6) {
						if(perimeterStep<rDistance*5) {
							x = -rDistance;
							z = -shortStep;
						}
						else {
							x = -(rDistance - shortStep);
							z = -rDistance;
						}
					}
					else {
						if(perimeterStep<rDistance*7) {
							x = shortStep;
							z = -rDistance;
						}
						else {
							x = rDistance;
							z = -(rDistance-shortStep);
						}
					}
				}
				break;
			}
			default: {
				totalSpace = Math.PI * (radius - centerRadius) * (radius + centerRadius);
				Double rSpace = totalSpace*Math.pow(r.nextDouble(),weight);
				Double rDistance = Math.sqrt(rSpace/Math.PI + centerRadius*centerRadius);

				Double rotation = (rDistance - rDistance.intValue())*2*Math.PI;

				x = rDistance * Math.cos(rotation);
				z = rDistance * Math.sin(rotation);
			}
		}
		res = new Location(world,x+centerX,0,z+centerZ);

		if(res.getChunk() == null) res.getChunk().load(true);

		res = this.getLastNonAir(res);

		Integer numAttempts = 1;
		Integer maxAttempts = this.config.getInt("maxAttempts",100);
		while(numAttempts < maxAttempts &&
				(	this.acceptableAir.contains(res.getBlock().getType())
					|| res.getBlockY() >= this.worlds.getConfigurationSection(world.getName()).getInt("maxY",128)
					|| (rerollLiquid && res.getBlock().isLiquid()))) {
			switch (shape) {
				case SQUARE: { //square spiral
					Double rSpace = r.nextDouble(totalSpace);

					Double rDistance = Math.sqrt(rSpace+centerRadius*centerRadius);
					Double perimeterStep = 8*(rDistance*(rDistance-rDistance.intValue()));
					Double shortStep = perimeterStep%rDistance;

					if(perimeterStep<rDistance*4) {
						if(perimeterStep<rDistance*2) {
							if(perimeterStep<rDistance) {
								x = rDistance;
								z = shortStep;
							}
							else {
								x = rDistance - shortStep;
								z = rDistance;
							}
						}
						else {
							if(perimeterStep<rDistance*3) {
								x = -shortStep;
								z = rDistance;
							}
							else {
								x = -rDistance;
								z = rDistance - shortStep;
							}
						}
					}
					else {
						if(perimeterStep<rDistance*6) {
							if(perimeterStep<rDistance*5) {
								x = -rDistance;
								z = -shortStep;
							}
							else {
								x = -(rDistance - shortStep);
								z = -rDistance;
							}
						}
						else {
							if(perimeterStep<rDistance*7) {
								x = shortStep;
								z = -rDistance;
							}
							else {
								x = rDistance;
								z = -(rDistance-shortStep);
							}
						}
					}
					break;
				}
				default: {
					Double rSpace = r.nextDouble(totalSpace);
					Double rDistance = Math.sqrt(rSpace/Math.PI + centerRadius*centerRadius);

					Double rotation = (rDistance - rDistance.intValue())*2*Math.PI;

					x = rDistance * Math.cos(rotation);
					z = rDistance * Math.sin(rotation);
				}
			}
			res = new Location(world,x+centerX,0,z+centerZ);
			if(res.getChunk() == null) res.getChunk().load(true);

			res = this.getLastNonAir(res);
			numAttempts++;
		}

		res.setY(res.getBlockY()+1);
		this.cache.setNumTeleportAttempts(res, numAttempts);
		return res;
	}

	private Location getLastNonAir(Location start) {
		World world = start.getWorld();
		Integer minY = this.worlds.getConfigurationSection(world.getName()).getInt("minY", 48);
		Integer maxY = this.worlds.getConfigurationSection(world.getName()).getInt("maxY",128);
		Integer oldY;
		start.setY(minY);
		Boolean requireSkyLight = this.worlds.getConfigurationSection(start.getWorld().getName()).getBoolean("requireSkyLight");

		//iterate over a larger distance first, then fine-tuneD
		for(Integer it_length = (maxY-minY)/2; it_length > 0; it_length/=2) {
			for(int i = minY; i <= maxY; i+=it_length) {
				oldY = start.getBlockY();
				start.setY(i);
				byte skyLight;
				try{
					skyLight = start.getBlock().getLightFromSky();
				}
				catch (TestAbortedException ex) {
					if(this.acceptableAir.contains(start.getBlock().getType())) skyLight = 15;
					else skyLight = 0;
				}
				if(this.acceptableAir.contains(start.getBlock().getType())
					&& this.acceptableAir.contains(start.getBlock().getRelative(BlockFace.UP).getType())
					&& !(requireSkyLight && skyLight==0)) {
					start.setY(oldY);
					minY = oldY;
					maxY = i;
					break;
				}
			}
		}
		start.setX(((Integer)start.getBlockX()).doubleValue()+0.5);
		start.setZ(((Integer)start.getBlockZ()).doubleValue()+0.5);
		return start;
	}

	public Integer updateWorldSetting(World world, String setting, String value){
		String worldName = world.getName();
		if(!this.worlds.getConfigurationSection(worldName).contains(setting)) {
			return -1;
		}

		if(this.worlds.getConfigurationSection(worldName).isString(setting)) {
			Boolean goodEnum = true;
			try {
				Shapes.valueOf(value.toUpperCase(Locale.ROOT));
			}
			catch (IllegalArgumentException ex) {
				goodEnum = false;
			}

			if(!goodEnum && Bukkit.getWorld(value) == null)
				return -2;
			this.worlds.getConfigurationSection(worldName).set(setting,value);
		}
		else if(this.worlds.getConfigurationSection(worldName).isInt(setting)) {
			Integer num;
			try {
				num = Integer.valueOf(value);
			}
			catch (Exception exception) {
				return -3;
			}
			this.worlds.getConfigurationSection(worldName).set(setting,num);
		}
		else if(this.worlds.getConfigurationSection(worldName).isDouble(setting)) {
			Double num;
			try {
				num = Double.valueOf(value);
			}
			catch (Exception exception) {
				return -3;
			}
			this.worlds.getConfigurationSection(worldName).set(setting,num);
		}
		else if(this.worlds.getConfigurationSection(worldName).isBoolean(setting)) {
			Boolean num;
			try {
				num = Boolean.valueOf(value);
			}
			catch (Exception exception) {
				return -3;
			}
			this.worlds.getConfigurationSection(worldName).set(setting,num);
		}

		return 0;
	}
}
