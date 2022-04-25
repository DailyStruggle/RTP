package leafcraft.rtp.api.configuration;

import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.configuration.enums.LangKeys;
import leafcraft.rtp.api.factory.FactoryValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public abstract class ConfigParser<E extends Enum<E>> extends FactoryValue<E> {
    public String name;
    public String version;
    protected File pluginDirectory;

    //todo: internal language mapping
    public Map<String,String> language_mapping = new HashMap<>();
    public Map<String,String> reverse_language_mapping = new HashMap<>();

    public ConfigParser(Class<E> eClass, final String name, final String version, final File pluginDirectory, File langFile) {
        super(eClass);
        check(name,version,pluginDirectory, langFile);
    }

    public ConfigParser(Class<E> eClass, final String name, final String version, final File pluginDirectory) {
        super(eClass);
        check(name,version,pluginDirectory, null);
    }

    protected void check(final String name, final String version, final File pluginDirectory, @Nullable File langFile) {
        //construct language file from enum vals
        //todo: apply translation to loads and saves
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


        if(!langFile.exists()) {
            StringBuilder mapFileLines = new StringBuilder();
            for (String key : keys()) {
                mapFileLines.append(key).append(":\"").append(key).append("\"\n");
            }

            try {
                Files.writeString(langFile.toPath(), mapFileLines);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(langFile.toPath());
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        for(String line : lines) {
            if(line.isBlank()) continue;
            String[] split = line.split(":");
            language_mapping.put(split[0],split[1]);
            reverse_language_mapping.put(split[1],split[0]);
        }

        this.name = (name.endsWith(".yml")) ? name : name + ".yml";
        this.version = version;
        this.pluginDirectory = pluginDirectory;
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
            if(!b) RTPAPI.log(Level.WARNING,
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
        if(!b) RTPAPI.log(Level.WARNING,
                "RTP - unable to rename file:" + oldFile.getAbsoluteFile());

        saveResource(this.name, true);
    }

    public Object getConfigValue(E key, Object def) {
        return data.getOrDefault(key, def);
    }

    /**
     * server function for saving a plugin config file
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
        for (String line : linesInDefaultConfig) {
            StringBuilder newline;
            if (line.startsWith("version:")) {
                newline = new StringBuilder("version: \""+ version + "\"");
            }
            else if(line.startsWith("  ")) {
                newline = new StringBuilder(line);
            }
            else {
                String[] split = line.split(":");
                if(split.length<2) {
                    newLines.add(split[0]);
                    continue;
                }
                String node = split[0];
                E e;
                try {
                    e = Enum.valueOf(myClass,node);
                }
                catch (IllegalArgumentException ignored) {
                    //todo: find out if it would be removed here?
                    //continue;
                    e = null;
                }

                Object fromString = getFromString(node, null); //todo: check this
                if(fromString == null) {
                    newline = new StringBuilder();
                }
                else if(fromString instanceof Map map) {
                    newline = new StringBuilder(node + ":\n");
                    mapToStringRecursive(node, map, 0, newline);
                }
                else if(fromString instanceof List) {
                    Set<Object> duplicateCheck = new HashSet<>();
                    newline = new StringBuilder(node + ":");
                    for(Object obj : (List<?>)fromString) {
                        if(duplicateCheck.contains(obj)) continue;
                        duplicateCheck.add(obj);
                        if(obj instanceof String) {
                            newline.append("\n  - " + "\"").append(obj).append("\"");
                        }
                        else newline.append("\n  - ").append(obj);
                    }
                    newline.deleteCharAt(0);
                }
                else if(fromString instanceof String) newline = new StringBuilder(node + ": \"" + oldValues.get(e).toString() + "\"");
                else newline = new StringBuilder(node + ": " + oldValues.get(e).toString());
            }
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
    public FactoryValue<E> clone() {
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
