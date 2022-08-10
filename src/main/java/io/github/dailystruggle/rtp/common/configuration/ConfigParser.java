package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.ConfigurationSection;
import org.simpleyaml.configuration.file.YamlFile;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class ConfigParser<E extends Enum<E>> extends FactoryValue<E> implements ConfigLoader {
    public YamlFile yamlFile;

    public String version;
    public File pluginDirectory;

    public Map<String, Object> language_mapping = new ConcurrentHashMap<String, Object>();
    public Map<String,String> reverse_language_mapping = new ConcurrentHashMap<>();

    public ConfigParser(Class<E> eClass, final String name, final String version, final File pluginDirectory, File langFile) {
        super(eClass, name);
        this.name = (name.endsWith(".yml")) ? name : name + ".yml";
        this.version = version;
        this.pluginDirectory = pluginDirectory;
        check(version,pluginDirectory, langFile);
    }

    public ConfigParser(Class<E> eClass, final String name, final String version, final File pluginDirectory) {
        super(eClass, name);
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
        for (String key : keys()) { //default data, to guard exceptions
            language_mapping.put(key,key);
            reverse_language_mapping.put(key,key);
        }
        if(langFile.exists()) {
            langYaml.loadWithComments();
            language_mapping = langYaml.getMapValues(true);
        }
        else {
            for(var e : language_mapping.entrySet()) {
                langYaml.set(e.getKey(),e.getValue());
            }
            langYaml.save();
        }
        for(var e : language_mapping.entrySet()) {
            reverse_language_mapping.put(e.getValue().toString(),e.getKey());
        }
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
        loadResource(f);

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
            loadResource(f);
        }

        data.clear();
        for(var v : myClass.getEnumConstants()) {
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

        String pDirectory = RTP.getInstance().serverAccessor.getPluginDirectory().getAbsolutePath();
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
                Files.copy(source.toPath(),new FileOutputStream(target.getPath()));
            }
        }
    }

    public void loadResource(File f) {
        Map<String,Object> yamlMap;
        if(f.exists()) {
            yamlFile = new YamlFile(f.getPath());
            try {
                yamlFile.loadWithComments();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
            yamlMap = yamlFile.getMapValues(false);
        }
        else {
            new FileNotFoundException(f.getAbsolutePath()).printStackTrace();
            return;
        }

        for(var e : myClass.getEnumConstants()) {
            String name = e.name();
            if(yamlMap.containsKey(name)) {
                data.put(e,yamlMap.get(name));
            }
        }
    }

    public void update() {
        renameFiles();
        Map<E,Object> oldValues = getData();

        List<String> linesInDefaultConfig = new ArrayList<>();
        try {
            Scanner scanner = new Scanner(
                    new File(pluginDirectory.getAbsolutePath() + File.separator + name));
            while (scanner.hasNextLine()) {
                linesInDefaultConfig.add(scanner.nextLine() + "");
            }
            scanner.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        List<String> newLines = new ArrayList<>();
        final Map<String, Object> yamlMap = yamlFile.getMapValues(false);
        for (int i = 0; i < linesInDefaultConfig.size(); i++) {
            String line = linesInDefaultConfig.get(i);
            StringBuilder newline;

            //leading comments don't need to be changed
            if (line.startsWith("#") || line.isBlank()) {
                newLines.add(line);
                continue;
            }

            //tabbed stuff is a part of a list or map
            if (line.startsWith("  ")) {
                continue;
            }

            //check for colon, denoting a key/val
            String[] split = line.split(":");

            //not a key
            if(split.length < 1) {
                newLines.add(line);
                continue;
            }

            //key without a value denotes a list or map
            if (split.length < 2) {
                //check for a local value
                Object value = yamlMap.getOrDefault(split[0],null);
                if(value == null) continue;

                //if it's a map
                if(value instanceof Map map) {
                    StringBuilder out = new StringBuilder();
                    mapToStringRecursive(split[0],map,0,out);
                    newLines.add(out.toString());
                }

                //check for factory type
                //check for map type
                //check for list type

                continue;
            }

            //
            String node = split[0];
            E e;
            try {
                e = Enum.valueOf(myClass, node);
            } catch (IllegalArgumentException ignored) {
                //todo: find out if it would be removed here?
                //continue;
                e = null;
            }

            Object fromString = yamlMap.get(node); //todo: check this?
            if(fromString == null) {
                Object altName = language_mapping.get(node);
                if(altName!=null) {
                    fromString = yamlMap.get(altName);
                }
            }

            if (fromString == null) {
                newline = new StringBuilder();
            } else if (fromString instanceof Map map) {
                newline = new StringBuilder(node + ":\n");
                mapToStringRecursive(node, map, 0, newline);
            } else if (fromString instanceof List) {
                Set<Object> duplicateCheck = new HashSet<>();
                newline = new StringBuilder(node + ":");
                for (Object obj : (List<?>) fromString) {
                    if (duplicateCheck.contains(obj)) continue;
                    duplicateCheck.add(obj);
                    if (obj instanceof String) {
                        newline.append("\n  - " + "\"").append(obj).append("\"");
                    } else newline.append("\n  - ").append(obj);
                }
                newline.deleteCharAt(0);
            } else if (fromString instanceof String)
                newline = new StringBuilder(node + ": \"" + oldValues.get(e).toString() + "\"");
            else newline = new StringBuilder(node + ": " + oldValues.get(e).toString());
            newLines.add(newline.toString());
        }

        FileWriter fw;
        String[] linesArray = newLines.toArray(new String[linesInDefaultConfig.size()]);
        try {
            fw = new FileWriter(pluginDirectory.getAbsolutePath() + File.separator + name);
            for (String s : linesArray) {
                if(s==null) continue;
                fw.write(s + "\n");
            }
            fw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    protected void mapToStringRecursive(String node, Map<String,Object> map, int depth, StringBuilder newline) {
        newline.append("  ".repeat(Math.max(0, depth)));
        newline.append(node).append(": \n");
        depth++;

        for(Map.Entry<String,Object> entry : map.entrySet()) {
            if(entry.getValue() instanceof Map map1) mapToStringRecursive(node,map1,depth, newline);
            else if(entry.getValue() instanceof List list){
                Set<Object> duplicateCheck = new HashSet<>();
                for(Object obj : list) {
                    if(duplicateCheck.contains(obj)) continue;
                    duplicateCheck.add(obj);
                    newline.append("  ".repeat(Math.max(0, depth)));
                    if(obj instanceof String) {
                        newline.append("- \"").append(obj).append("\"\n");
                    }
                    else {
                        newline.append("  - ").append(obj).append("\n");
                    }
                }
            }
        }
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
        Object o = yamlFile.get(key.name());
        if(o instanceof ConfigurationSection section) {
            if(value instanceof FactoryValue<?> factoryValue) {
                EnumMap<?, Object> data = factoryValue.getData();
                Map<String,Object> map = new HashMap<>();
                for(var d : data.entrySet()) map.put(d.getKey().name(),d.getValue());
                setSection(section,map);
            }
            else if(value instanceof Map map) {
                setSection(section,map);
            }
            else throw new IllegalArgumentException();
            yamlFile.set(key.name(),section);
        }
        else {
            yamlFile.set(key.name(),value);
        }
    }

    private static void setSection(ConfigurationSection section, Map<String,Object> map) {
        Map<String, Object> mapValues = section.getMapValues(false);

        for(var e : mapValues.entrySet()) {
            Object o = e.getValue();
            if(!map.containsKey(e.getKey())) continue;
            Object value = map.get(e.getKey());
            if(o instanceof ConfigurationSection subSection) {
                if(value instanceof FactoryValue<?> factoryValue) {
                    EnumMap<?, Object> data = factoryValue.getData();
                    Map<String,Object> subMap = new HashMap<>();
                    for(var d : data.entrySet()) subMap.put(d.getKey().name(),d.getValue());
                    setSection(subSection,subMap);
                }
                else if(value instanceof Map subMap) {
                    setSection(subSection,subMap);
                }
                else throw new IllegalArgumentException();
            }
            else section.set(e.getKey(),o);
        }
    }

    public void save() throws IOException {
        yamlFile.save(new File(pluginDirectory.getAbsolutePath()+File.separator+name));
        yamlFile.loadWithComments();
    }

    @Override
    public File getMainDirectory() {
        return pluginDirectory;
    }
}
