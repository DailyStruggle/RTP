package leafcraft.rtp.common.configuration;

import leafcraft.rtp.common.factory.Factory;
import leafcraft.rtp.common.factory.FactoryValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.EnumMap;

public class MultiConfigParser<E extends Enum<E>>  extends FactoryValue<E> {
    public Factory<ConfigParser<E>> configParserFactory = new Factory<>();
    public final File myDirectory;
    public final String name;
    protected final File langMap;

    public MultiConfigParser(Class<E> eClass, String name, File pluginDirectory) {
        super(eClass);
        this.name = name;
        this.myDirectory = new File(pluginDirectory.getAbsolutePath() + File.separator + name);
        this.langMap = new File(pluginDirectory.getAbsolutePath() + File.separator
                + "lang" + File.separator + name + ".lang.yml");
        if(!this.myDirectory.exists() && !myDirectory.mkdir()) return;
    }

    @NotNull
    public ConfigParser<E> getParser(String name) {
        name = name.toUpperCase();
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
        ConfigParser<E> value = (ConfigParser<E>) configParserFactory.construct(name, data);
        configParserFactory.add(name, value);
    }

    public void addParser(ConfigParser<E> parser) {
        configParserFactory.add(parser.name,parser);
    }

    public void addAll(Iterable<String> keys) {
        for(String key : keys) {
            ConfigParser<?> value = getParser(key);
            addParser(name,null);
        }
    }
}
