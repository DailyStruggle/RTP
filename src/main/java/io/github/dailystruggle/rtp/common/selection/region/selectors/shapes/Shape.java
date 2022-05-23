package io.github.dailystruggle.rtp.common.selection.region.selectors.shapes;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.substitutions.RTPLocation;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;


public abstract class Shape<E extends Enum<E>> extends FactoryValue<E> {
    public final String name;

    protected final List<BiPredicate<UUID,RTPLocation>> verifiers = new ArrayList<>();

    /**
     * @param name - unique name of shape
     */
    public Shape(Class<E> eClass, String name, EnumMap<E,Object> data) throws IllegalArgumentException {
        super(eClass);
        this.name = name;
        this.data = data;
        for (E val : myClass.getEnumConstants()) {
            if(!data.containsKey(val)) throw new IllegalArgumentException(
                    "All values must be filled out on shape instantiation");
        }
        Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.getInstance().factoryMap.get(RTP.factoryNames.shape);
        if (!factory.contains(name)) factory.add(name,this);
        loadLangFile();
    }

    @Override
    public @NotNull EnumMap<E, Object> getData() {
        return data.clone();
    }

    public abstract int[] select(); //todo

    public abstract long rand();

    public abstract Map<String, CommandParameter> getParameters();

    @Override
    public Shape<E> clone() {
        return (Shape<E>) super.clone();
    }

    public Map<String,String> language_mapping = new ConcurrentHashMap<>();
    public Map<String,String> reverse_language_mapping = new ConcurrentHashMap<>();

    protected void loadLangFile() {
        File langFile;
        String langDirStr = RTP.getInstance().serverAccessor.getPluginDirectory().getAbsolutePath()
                + File.separator
                + "lang"
                + File.separator
                + "shape";
        File langDir = new File(langDirStr);
        if(!langDir.exists()) {
            boolean mkdir = langDir.mkdirs();
            if(!mkdir) throw new IllegalStateException();
        }

        String mapFileName = langDir + File.separator
                + name.replace(".yml", ".lang.yml");
        langFile = new File(mapFileName);

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
}
