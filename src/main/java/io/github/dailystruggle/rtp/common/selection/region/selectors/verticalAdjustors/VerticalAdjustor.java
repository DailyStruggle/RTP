package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPBlock;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public abstract class VerticalAdjustor<E extends Enum<E>> extends FactoryValue<E> {
    protected final List<Predicate<RTPBlock>> verifiers;

    public String name;

    protected VerticalAdjustor(Class<E> eClass, String name, List<Predicate<RTPBlock>> verifiers, EnumMap<E,Object> def) {
        super(eClass, name);
        this.verifiers = verifiers;
        setData(def);
        Factory<VerticalAdjustor<?>> vertAdjustorFactory = (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
        this.name = name;
        if(!vertAdjustorFactory.contains(name))
            vertAdjustorFactory.add(name,this);
        loadLangFile();
    }

    public abstract @Nullable
    RTPLocation adjust(@NotNull RTPChunk input);
    public abstract boolean testPlacement(@NotNull RTPBlock location);

    public abstract int minY();
    public abstract int maxY();

    public Map<String,String> language_mapping = new ConcurrentHashMap<>();
    public Map<String,String> reverse_language_mapping = new ConcurrentHashMap<>();
    
    protected void loadLangFile() {
        File langFile;
        String langDirStr = RTP.serverAccessor.getPluginDirectory().getAbsolutePath()
                + File.separator
                + "lang"
                + File.separator
                + "vert";
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
