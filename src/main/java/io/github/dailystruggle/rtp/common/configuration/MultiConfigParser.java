package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.EnumMap;

public class MultiConfigParser<E extends Enum<E>>  extends FactoryValue<E> implements ConfigLoader {
    public Factory<ConfigParser<E>> configParserFactory = new Factory<>();
    public final File pluginDirectory;
    public final File myDirectory;
    public final String name;
    public final String version;
    protected final File langMap;
    private ClassLoader classLoader = this.getClass().getClassLoader();

    public MultiConfigParser(Class<E> eClass, String name, String version, File pluginDirectory, ClassLoader classLoader) {
        super(eClass, name);
        this.classLoader = classLoader;
        this.pluginDirectory = pluginDirectory;
        this.name = name;
        this.version = version;
        this.myDirectory = new File(pluginDirectory.getAbsolutePath() + File.separator + name);
        this.langMap = new File(pluginDirectory.getAbsolutePath() + File.separator
                + "lang" + File.separator + name + ".lang.yml");
        if(!this.myDirectory.exists() && !myDirectory.mkdir()) return;

        File d = new File(myDirectory.getAbsolutePath() + File.separator + "default.yml");
        if(!d.exists()) {
            saveResourceFromJar(name + File.separator + "default.yml",true);
        }

        File langMap = new File(RTP.serverAccessor.getPluginDirectory() + File.separator + "lang" + File.separator + name + ".lang.yml");

        File[] files = myDirectory.listFiles();
        if(files == null) return;
        for(File file : files) {
            String fileName = file.getName();
            if(!fileName.endsWith(".yml")) continue;
            if(fileName.contains("old")) continue;

            fileName = fileName.replace(".yml","");

            ConfigParser<E> parser = new ConfigParser<>(
                    eClass,fileName,version,myDirectory,langMap);
            addParser(parser);
        }
    }

    public MultiConfigParser(Class<E> eClass, String name, String version, File pluginDirectory) {
        super(eClass, name);
        this.pluginDirectory = pluginDirectory;
        this.name = name;
        this.version = version;
        this.myDirectory = new File(pluginDirectory.getAbsolutePath() + File.separator + name);
        this.langMap = new File(pluginDirectory.getAbsolutePath() + File.separator
                + "lang" + File.separator + name + ".lang.yml");
        if(!this.myDirectory.exists() && !myDirectory.mkdir()) return;

        File d = new File(myDirectory.getAbsolutePath() + File.separator + "default.yml");
        if(!d.exists()) {
            saveResourceFromJar(name + File.separator + "default.yml",true);
        }

        File langMap = new File(RTP.serverAccessor.getPluginDirectory() + File.separator + "lang" + File.separator + name + ".lang.yml");

        File[] files = myDirectory.listFiles();
        if(files == null) return;
        for(File file : files) {
            String fileName = file.getName();
            if(!fileName.endsWith(".yml")) continue;
            if(fileName.contains("old")) continue;

            fileName = fileName.replace(".yml","");

            ConfigParser<E> parser = new ConfigParser<>(
                    eClass,fileName,version,myDirectory,langMap);
            addParser(parser);
        }
    }

    @NotNull
    public ConfigParser<E> getParser(String name) {
        name = name.toUpperCase();
        if(!name.endsWith(".YML")) name = name + ".YML";
        //todo: check for additional file
        //todo: in worlds parser, check for additional worlds
        ConfigParser<E> parser;
        if(configParserFactory.contains(name)) { // old file
            parser = configParserFactory.map.get(name);
        }
        else { //new file
            parser = (ConfigParser<E>) configParserFactory.getOrDefault(name);
            parser.check(parser.version, parser.pluginDirectory,langMap);
            addParser(parser);
        }
        return parser;
    }

    public void addParser(String name, @Nullable EnumMap<E,Object> data) {
        name = name.toUpperCase();
        if(!name.endsWith(".YML")) name = name + ".YML";
        ConfigParser<E> value = (ConfigParser<E>) configParserFactory.construct(name, data);
        configParserFactory.add(name, value);
    }

    public void addParser(ConfigParser<?> parser) {
        if(!parser.myClass.equals(myClass)) throw new IllegalStateException("mismatched parser class");
        ConfigParser<E> eConfigParser = (ConfigParser<E>) parser;
        configParserFactory.add(parser.name,eConfigParser);
    }

    public void addAll(Iterable<String> keys) {
        for(String key : keys) {
            ConfigParser<?> value = getParser(key);
            addParser(name,null);
        }
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
