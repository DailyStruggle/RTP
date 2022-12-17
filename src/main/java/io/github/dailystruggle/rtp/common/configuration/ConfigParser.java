package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.ConfigurationSection;
import org.simpleyaml.configuration.file.YamlFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class ConfigParser<E extends Enum<E>> extends FactoryValue<E> implements ConfigLoader {
    public String version;
    public File pluginDirectory;

    public Map<String, Object> language_mapping = new ConcurrentHashMap<String, Object>();
    public Map<String,String> reverse_language_mapping = new ConcurrentHashMap<>();

    public final YamlFileDatabase fileDatabase;
    AtomicReference<Map<String, YamlFile>> cachedLookup;

    private ClassLoader classLoader = this.getClass().getClassLoader();

    public ConfigParser(Class<E> eClass, final String name, final String version, final File pluginDirectory, File langFile, YamlFileDatabase fileDatabase, ClassLoader classLoader) {
        super(eClass, name);
        this.fileDatabase = fileDatabase;
        this.name = (name.endsWith(".yml")) ? name : name + ".yml";
        this.version = version;
        this.pluginDirectory = pluginDirectory;
        this.classLoader = classLoader;
        check(version,pluginDirectory, langFile);
    }

    public ConfigParser(Class<E> eClass, final String name, final String version, final File pluginDirectory, File langFile, YamlFileDatabase fileDatabase) {
        super(eClass, name);
        this.fileDatabase = fileDatabase;
        this.name = (name.endsWith(".yml")) ? name : name + ".yml";
        this.version = version;
        this.pluginDirectory = pluginDirectory;
        check(version,pluginDirectory, langFile);
    }

    public ConfigParser(Class<E> eClass, final String name, final String version, final File pluginDirectory, YamlFileDatabase fileDatabase) {
        super(eClass, name);
        this.fileDatabase = fileDatabase;
        this.name = (name.endsWith(".yml")) ? name : name + ".yml";
        this.version = version;
        this.pluginDirectory = pluginDirectory;
        check(version,pluginDirectory, null);
    }

    protected void loadLangFile(@Nullable File langFile) throws IOException {
        if(langFile == null) {
            String langDirStr = pluginDirectory.getAbsolutePath() + File.separator
                    + "lang" + File.separator;
            File langDir = new File(langDirStr);
            if(!langDir.exists()) {
                boolean mkdir = langDir.mkdirs();
                if(!mkdir) throw new IllegalStateException();
            }

            String mapFileName = langDir + File.separator
                    + name.replace(".yml", ".lang.yml");
            langFile = new File(mapFileName);
        }

        YamlFile langYaml = new YamlFile(langFile.getPath());
        language_mapping.clear();
        reverse_language_mapping.clear();
        if (!langFile.exists()) {
            for (String key : keys()) { //default data, to guard exceptions
                langYaml.set(key,key);
                language_mapping.put(key,key);
                reverse_language_mapping.put(key,key);
            }
            langYaml.save();
        }

        langYaml.loadWithComments();
        language_mapping = langYaml.getMapValues(true);
        language_mapping.forEach((s, o) -> reverse_language_mapping.put(o.toString(),s));
    }

    public void check(final String version, final File pluginDirectory, @Nullable File langFile) {
        //construct language file from enum vals
        //todo: apply translation to loads and saves
        try {
            loadLangFile(langFile);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        File f = new File(pluginDirectory + File.separator + this.name);
        if(!f.exists())
        {
            try {
                saveResource(this.name,true);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
        }
//        loadResource(f);

        cachedLookup = fileDatabase.cachedLookup;
        if(cachedLookup.get() == null || cachedLookup.get().size()==0) fileDatabase.connect();
        YamlFile yamlFile = cachedLookup.get().get(name);

        String versionStr = yamlFile.getMapValues(false).getOrDefault("version", "1.0").toString();

        String[] versionArr = Objects.requireNonNull(versionStr).split("\\.");

        boolean update = false;
        String[] split = version.split("\\.");
        List<Integer> parsedVersion = Arrays.stream(split).map(Integer::parseUnsignedInt).collect(Collectors.toList());

        if(versionArr.length!=parsedVersion.size()) {
            update = true;
        }
        else {
            for (int i = 0; i < versionArr.length; i++) {
                int v = Integer.parseInt(versionArr[i]);
                int cv = parsedVersion.get(i);
                if(v != cv) {
                    update = true;
                    break;
                }
            }
        }

        if(update) {
            update();
            f = new File(pluginDirectory, this.name);
//            loadResource(f);
        }

        data.clear();
        for(E v : myClass.getEnumConstants()) {
            Object fromString = yamlFile.get(v.name());
            if(fromString!=null) {
                data.put(v, fromString);
            }
        }
    }

    public void renameFiles() {
        //load up a list of files to rename
        ArrayList<File> toRename = new ArrayList<>();
        for(int i = 1; i < 1000; i++) {
            File file = new File(pluginDirectory.getAbsolutePath() + File.separator + name + ".old"+i);
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
            File newFile = new File(pluginDirectory.getAbsolutePath() + File.separator + newFileName);
            try { //ensure can place
                Files.deleteIfExists(newFile.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
            boolean b = oldFile.getAbsoluteFile().renameTo(newFile.getAbsoluteFile());
            if(!b) RTP.log(Level.WARNING,
                    "RTP - unable to rename file:" + oldFile.getName() + " to: " + newFile.getName());
        }

        //rename the last one
        File oldFile = new File(pluginDirectory.getAbsolutePath() + File.separator + name);
        File newFile = new File(pluginDirectory.getAbsolutePath() + File.separator + name + ".old1");
        try {
            Files.deleteIfExists(newFile.toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        boolean b = oldFile.getAbsoluteFile().renameTo(newFile.getAbsoluteFile());
        if(!b) RTP.log(Level.WARNING,
                "RTP - unable to rename file:" + oldFile.getAbsoluteFile());

        saveResourceFromJar(this.name, true);
    }

    public Object getConfigValue(E key, Object def) {
        return data.getOrDefault(key, def);
    }

    /**
     * server function for saving a plugin config file from package
     * @param name file name, e.g. "config.yml"
     * @param overwrite whether to overwrite an existing file with that name
     */
    public void saveResource(String name, boolean overwrite) throws IOException {
        String myDirectory = pluginDirectory.getAbsolutePath();

        String pDirectory = RTP.serverAccessor.getPluginDirectory().getAbsolutePath();
        if(myDirectory.equals(pDirectory)) {
            saveResourceFromJar(name,overwrite);
        }
        else {
            String diff = myDirectory.substring(pDirectory.length()+1);
            if(name.equals("default.yml")) {
                saveResourceFromJar(diff + File.separator + name, overwrite);
            }
            else {
                File source = new File(myDirectory + File.separator + "default.yml");
                File target = new File(myDirectory + File.separator + name);
                FileOutputStream outputStream = new FileOutputStream(target.getPath());
                Files.copy(source.toPath(), outputStream);
                outputStream.close();
            }
        }
    }

    public void update() {
        renameFiles();
        Map<E,Object> oldValues = getData();
//        saveResourceFromJar();
    }

    @Override
    public ConfigParser<E> clone() {
        ConfigParser<E> clone = (ConfigParser<E>) super.clone();

        clone.language_mapping = this.language_mapping;
        clone.reverse_language_mapping = this.reverse_language_mapping;
        clone.name = name;
        clone.version = version;
        clone.pluginDirectory = pluginDirectory;
        clone.check(version,pluginDirectory,null);
        return clone;
    }

    @Override
    public void set(@NotNull E key, @NotNull Object value) throws IllegalArgumentException {
        super.set(key,value);

        YamlFile yamlFile = cachedLookup.get().get(name);
        Object o = yamlFile.get(key.name());
        if(o instanceof ConfigurationSection) {
            ConfigurationSection configurationSection = (ConfigurationSection) o;

            if(configurationSection.getName().equalsIgnoreCase("shape") && value instanceof String) {
                String shapeName = (String) value;
                value = RTP.factoryMap.get(RTP.factoryNames.shape).getOrDefault(shapeName);
            }

            if(configurationSection.getName().equalsIgnoreCase("vert") && value instanceof String) {
                String vertName = (String) value;
                value = RTP.factoryMap.get(RTP.factoryNames.vert).getOrDefault(vertName);
            }

            if(value instanceof FactoryValue<?>) {
                EnumMap<?, Object> data = ((FactoryValue<?>) value).getData();
                Map<String,Object> map = new HashMap<>();
                for(Map.Entry<? extends Enum<?>,Object> d : data.entrySet()) map.put(d.getKey().name(),d.getValue());
                setSection((ConfigurationSection) o,map);
            }
            else if(value instanceof Map) {
                setSection((ConfigurationSection) o,(Map<String, Object>) value);
            }
            else {
                IllegalArgumentException exception = new IllegalArgumentException();
                exception.printStackTrace();
                throw exception;
            }
            yamlFile.set(key.name(),o);
        }
        else {
            yamlFile.set(key.name(),value);
        }
    }

    public void set(String key, Object value) {
        String translate = reverse_language_mapping.get(key);
        if(translate!=null) key = translate;

        E k = enumLookup.get(key.toLowerCase());
        if(k == null) return;

        set(k,value);
    }

    private static void setSection(ConfigurationSection section, Map<String,Object> map) {
        Map<String, Object> mapValues = section.getMapValues(false);

        for(Map.Entry<String,Object> e : mapValues.entrySet()) {
            Object o = e.getValue();
            if(!map.containsKey(e.getKey())) continue;
            Object value = map.get(e.getKey());
            if(o instanceof ConfigurationSection) {
                if(value instanceof FactoryValue<?>) {
                    EnumMap<?, Object> data = ((FactoryValue<?>) value).getData();
                    Map<String,Object> subMap = new HashMap<>();
                    for(Map.Entry<? extends Enum<?>,?> d : data.entrySet()) subMap.put(d.getKey().name(),d.getValue());
                    setSection((ConfigurationSection) o,subMap);
                }
                else if(value instanceof Map) {
                    setSection((ConfigurationSection) o, (Map<String, Object>) value);
                }
                else throw new IllegalArgumentException();
            }
            else section.set(e.getKey(),o);
        }
    }

    public void save() throws IOException {
        YamlFile yamlFile = cachedLookup.get().get(name);
        yamlFile.save(new File(pluginDirectory.getAbsolutePath()+File.separator+name));
    }

    @Override
    public File getMainDirectory() {
        return pluginDirectory;
    }

    @Override
    public ClassLoader getClassLoader() {
        return classLoader;
    }
}
