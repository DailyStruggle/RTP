package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.EnumMap;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;

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
    public Set<String> listParsers() {
        return configParserFactory.map.values().stream()
                .map(eConfigParser -> StringUtils.replaceIgnoreCase(eConfigParser.name,".yml",""))
                .collect(Collectors.toSet());
    }

    @NotNull
    public ConfigParser<E> getParser(String name) {
        return (ConfigParser<E>) configParserFactory.getOrDefault(name);
    }

    public void addParser(String name) {
        ConfigParser<E> value = (ConfigParser<E>) configParserFactory.construct(name);
        configParserFactory.add(name, value);
    }

    public void addParser(ConfigParser<?> parser) {
        if(!parser.myClass.equals(myClass)) throw new IllegalStateException("mismatched parser class");
        ConfigParser<E> eConfigParser = (ConfigParser<E>) parser;
        configParserFactory.add(parser.name,eConfigParser);
    }

    public void addAll(String... keys) {
        for(String key : keys) {
            addParser(key);
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
