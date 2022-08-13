package io.github.dailystruggle.rtp.common.selection.region.selectors.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import org.jetbrains.annotations.NotNull;
import org.simpleyaml.configuration.file.YamlFile;

import java.io.File;
import java.io.IOException;
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
        super(eClass, name);
        this.name = name;
        this.data.putAll(data);
        for (E val : myClass.getEnumConstants()) {
            if(!data.containsKey(val)) throw new IllegalArgumentException(
                    "All values must be filled out on shape instantiation");
        }
        Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        if(factory == null) throw new IllegalStateException("shape factory doesn't exist");
        if (!factory.contains(name)) factory.add(name,this);
        try {
            loadLangFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
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

    public Map<String, Object> language_mapping = new ConcurrentHashMap<>();
    public Map<String,String> reverse_language_mapping = new ConcurrentHashMap<>();

    protected void loadLangFile() throws IOException {
        String name = this.name;
        if(!name.endsWith(".yml")) name = name + ".yml";
        File langFile;
        String langDirStr = RTP.serverAccessor.getPluginDirectory().getAbsolutePath()
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

        YamlFile langYaml = new YamlFile(langFile);
        for (String key : keys()) { //default data, to guard exceptions
            language_mapping.put(key,key);
            reverse_language_mapping.put(key,key);
        }
        if(langFile.exists()) {
            langYaml.loadWithComments();
        }
        else {
            langYaml.save(langFile);
        }
    }
}
