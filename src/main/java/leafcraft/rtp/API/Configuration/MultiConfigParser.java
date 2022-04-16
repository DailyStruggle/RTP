package leafcraft.rtp.api.configuration;

import leafcraft.rtp.api.configuration.enums.LangKeys;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MultiConfigParser<E extends Enum<E>> {
    protected final Class<E> myClass;
    public final File myDirectory;
    protected final String name;
    protected final ConfigParser<LangKeys> lang;
    protected final Map<String, ConfigParser<E>> configs = new ConcurrentHashMap<>();

    public MultiConfigParser(Class<E> eClass, String name, File pluginDirectory, ConfigParser<LangKeys> lang) {
        myClass = eClass;
        this.name = name;
        this.lang = lang;
        this.myDirectory = new File(pluginDirectory.getAbsolutePath() + File.separator + name);
        if(!this.myDirectory.exists() && !myDirectory.mkdir()) return;
    }

    @Nullable
    public ConfigParser<E> getParser(String name) {
        //todo: check for additional file
        //todo: in worlds parser, check for additional worlds
        return configs.get(name);
    }

    public void addParser(ConfigParser<E> parser) {
        configs.put(parser.name,parser);
    }

}
