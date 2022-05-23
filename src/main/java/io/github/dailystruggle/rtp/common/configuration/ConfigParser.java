package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public abstract class ConfigParser<E extends Enum<E>> extends FactoryValue<E> {
    public String name;
    public String version;
    protected File pluginDirectory;

    public ConfigParser(Class<E> eClass, final String name, final String version, final File pluginDirectory, File langFile) {
        super(eClass);
        this.name = (name.endsWith(".yml")) ? name : name + ".yml";
        this.version = version;
        this.pluginDirectory = pluginDirectory;
        check(version,pluginDirectory, langFile);
    }

    public ConfigParser(Class<E> eClass, final String name, final String version, final File pluginDirectory) {
        super(eClass);
        this.name = (name.endsWith(".yml")) ? name : name + ".yml";
        this.version = version;
        this.pluginDirectory = pluginDirectory;
        check(version,pluginDirectory, null);
    }

    public Map<String,String> language_mapping = new ConcurrentHashMap<>();
    public Map<String,String> reverse_language_mapping = new ConcurrentHashMap<>();

    protected void loadLangFile(@Nullable File langFile) {
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

        Yaml langYaml = new Yaml();
        for (String key : keys()) { //default data, to guard exceptions
            language_mapping.put(key,key);
            reverse_language_mapping.put(key,key);
        }
        if(langFile.exists()) {
            InputStream inputStream;
            try {
                inputStream = new FileInputStream(langFile);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                return;
            }
            language_mapping = langYaml.load(inputStream);
        }
        else {
            PrintWriter writer;
            try {
                writer = new PrintWriter(langFile);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                return;
            }
            langYaml.dump(language_mapping,writer);
        }
    }

    protected void check(final String version, final File pluginDirectory, @Nullable File langFile) {
        //construct language file from enum vals
        //todo: apply translation to loads and saves
        loadLangFile(langFile);

        File f = new File(pluginDirectory + File.separator + this.name);
        if(!f.exists())
        {
            saveResource(this.name,true);
        }
        loadResource(f);

        String versionStr = this.getFromString("version", "0.0").toString();

        String[] versionArr = Objects.requireNonNull(versionStr).split("\\.");

        boolean update = false;
        String[] split = version.split("\\.");
        List<Integer> parsedVersion = Arrays.stream(split).map(Integer::parseUnsignedInt).collect(Collectors.toList());

        if(versionArr.length!=parsedVersion.size()) {
            update = true;
        }

        if(!update) {
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
            Object fromString = getFromString(v.name(), null);
            data.put(v, fromString);
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
                    "RTP - unable to rename file:" + oldFile.getAbsoluteFile());
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

        saveResource(this.name, true);
    }

    public Object getConfigValue(E key, Object def) {
        return data.getOrDefault(key, def);
    }

    /**
     * server function for saving a plugin config file from package
     * @param name file name, e.g. "config.yml"
     * @param overwrite whether to overwrite an existing file with that name
     */
    public abstract void saveResource(String name, boolean overwrite);

    public abstract void loadResource(File f);

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
                Object value = getFromString(split[0],null);
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

            Object fromString = getFromString(node, null); //todo: check this
            if(fromString == null) {
                String altName = language_mapping.get(node);
                if(altName!=null) {
                    fromString = getFromString(altName,null);
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

    protected abstract Object getFromString(String val, @Nullable Object def);

    @Override
    public ConfigParser<E> clone() {
        ConfigParser<E> clone = (ConfigParser<E>) super.clone();
        clone.language_mapping = this.language_mapping;
        clone.reverse_language_mapping = this.reverse_language_mapping;
        clone.name = name;
        clone.version = version;
        clone.pluginDirectory = pluginDirectory;
        return clone;
    }

    public void set(@NotNull E key, @NotNull Object o) {
        data.put(key,o);
    }

    //todo: write runtime updates to file
}
