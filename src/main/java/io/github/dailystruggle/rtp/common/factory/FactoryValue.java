package io.github.dailystruggle.rtp.common.factory;

import com.google.common.base.Function;
import io.github.dailystruggle.rtp.common.RTP;
import org.jetbrains.annotations.NotNull;
import org.simpleyaml.configuration.file.YamlFile;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * this exists solely because java is so stubborn about constructors and generics.
 * rather than calling a constructor, the factory value will be copied from a value in the factory
 * @param <E> enum of available parameters
 */
@SuppressWarnings("unchecked")
public abstract class FactoryValue<E extends Enum<E>> implements Cloneable {

    //hacky way just to do Enum.valueOf on the correct enum
    public final Class<E> myClass;

    /**
     * data - container for arbitrary data values
     *      mapped enum value to object to stay organized
     */
    public String name;
    protected final EnumMap<E,Object> data;
    protected final EnumMap<E,String[]> desc;

    protected final Map<String,E> enumLookup;

    protected FactoryValue(Class<E> myClass, String name) {
        this.myClass = myClass;
        enumLookup = new ConcurrentHashMap<>();
        E[] enumConstants = myClass.getEnumConstants();
        for(E constant : enumConstants) {
            enumLookup.put(constant.name().toLowerCase(),constant);
        }
        data = new EnumMap<>(myClass);
        desc = new EnumMap<>(myClass);
        this.name = name;
    }

    /**
     * generic getter
     * @return copy of data, to prevent editing
     */
    @NotNull
    public EnumMap<E,Object> getData() {
        return data.clone();
    }

    /**
     * @param data - data to apply.
     */
    public void setData(final EnumMap<? extends Enum<?>,?> data) throws IllegalArgumentException {
        data.forEach((key, value) -> {
            if(key == null) throw new IllegalArgumentException("null key");
            if(value == null) throw new IllegalArgumentException("null value");
            if (!myClass.isAssignableFrom(key.getClass())) {
                throw new IllegalArgumentException("invalid assignment"
                        + "\nexpected:" + myClass.getSimpleName()
                        + "\nreceived:" + key.getClass().getSimpleName()
                );
            }
            this.data.put((E) key, value);
        });
    }

    public void setDesc(@NotNull E key, @NotNull String[] desc) throws IllegalArgumentException {
        if(key == null) throw new IllegalArgumentException("null key");
        if(desc == null) throw new IllegalArgumentException("null desc");
        this.desc.put(key,desc.clone());
    }

    public void set(@NotNull E key, @NotNull Object value) throws IllegalArgumentException {
        if(key == null) throw new IllegalArgumentException("null key");
        if(value == null) throw new IllegalArgumentException("null value");
        this.data.put(key, value);
    }

    /**
     * todo: guard value types??
     * @param data - data to apply. key is case-sensitive
     */
    public void setData(final Map<String,Object> data) throws IllegalArgumentException {
        data.forEach((keyStr, value) -> {
            if(keyStr == null) return;
            if(value == null) return;

            if(language_mapping.containsKey(keyStr)) keyStr = language_mapping.get(keyStr).toString();

            try {
                E key = Enum.valueOf(myClass, keyStr);
                this.data.put(key, value);
            } catch (IllegalArgumentException ignored) {

            }
        });
    }

    @Override
    public FactoryValue<E> clone() {
        try {
            FactoryValue<E> clone = (FactoryValue<E>) super.clone();
            clone.setData(getData());
            return clone;
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static final Map<Class<? extends Number>, Function<String, Number>> numberParsers = new ConcurrentHashMap<>();
    static {
        numberParsers.put(Double.class,Double::parseDouble);
        numberParsers.put(Float.class,Float::parseFloat);
        numberParsers.put(Long.class,Long::parseLong);
        numberParsers.put(Integer.class,Integer::parseInt);
        numberParsers.put(Short.class,Short::parseShort);
        numberParsers.put(Byte.class,Byte::parseByte);
    }

    public Number getNumber(E key, Number def) throws NumberFormatException {
        Number res = def;

        Object resObj = data.getOrDefault(key,def);
        if(resObj instanceof Number) {
            res = (Number) resObj;
        }
        else if(resObj instanceof String) {
            res = numberParsers.get(def.getClass()).apply((String) resObj);
        } else if(resObj instanceof Character) {
            res = Integer.parseInt(((Character) resObj).toString());
        }
        data.put(key,res);
        return res;
    }

    private Set<String> keys = null;
    public Collection<String> keys() {
        if(keys == null) keys = Arrays.stream(myClass.getEnumConstants()).map(Enum::name).collect(Collectors.toSet());
        return keys;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        data.forEach((e, o) -> builder.append("\n").append(e).append(": ").append(o.toString()));
        return builder.toString();
    }

    public String toYAML() {
        StringBuilder res = new StringBuilder();
        for(Map.Entry<? extends Enum<?>, Object> e : data.entrySet()) {
            String[] desc = this.desc.get(e.getKey());
            if(desc!=null) {
                for(String d : desc) {
                    res.append(d).append("\n");
                }
            }

            res.append(e.getKey().name()).append(": ");

            Object value = e.getValue();
            if(value instanceof FactoryValue<?>) {
                res.append("\n");
                String s = ((FactoryValue<?>) value).toYAML();
                s = s.replaceAll("\n","  \n");
                res.append(s);
            }
            else if(value instanceof Map) {
                ((Map<?, ?>) value).forEach((o, o2) -> res.append("\n").append(o.toString()).append(": ").append(o2.toString()));
            }
            else if(value instanceof List) {
                ((List<?>) value).forEach(o -> res.append("\n").append(o.toString()));
            }
            else {
                res.append(value.toString());
            }
        }
        return res.toString();
    }

    public Map<String, Object> language_mapping = new ConcurrentHashMap<>();
    public Map<String,String> reverse_language_mapping = new ConcurrentHashMap<>();

    public void loadLangFile(String subDir) throws IOException {
        String name = this.name;
        if(!name.endsWith(".yml")) name = name + ".yml";
        File langFile;
        String langDirStr = RTP.serverAccessor.getPluginDirectory().getAbsolutePath()
                + File.separator
                + "lang"
                + File.separator
                + subDir;
        File langDir = new File(langDirStr);
        if(!langDir.exists()) {
            boolean mkdir = langDir.mkdirs();
            if(!mkdir) throw new IllegalStateException();
        }

        String mapFileName = langDir + File.separator
                + name.replace(".yml", ".lang.yml");
        langFile = new File(mapFileName);

        YamlFile langYaml = new YamlFile(langFile);
        if (!langFile.exists()) {
            for (String key : keys()) { //default data, to guard exceptions
                langYaml.set(key, key);
            }
            langYaml.save(langFile);
        }

        langYaml.loadWithComments();
        Map<String, Object> map = langYaml.getMapValues(true);
        language_mapping.clear();
        language_mapping.putAll(map);
        reverse_language_mapping.clear();
        for (Map.Entry<String, Object> e : language_mapping.entrySet()) {
            reverse_language_mapping.put(e.getValue().toString(), e.getKey());
        }
    }

    @Override
    public boolean equals(Object other) {
        if(!(other instanceof FactoryValue)) return false;
        if(!(this.getClass().isAssignableFrom(other.getClass()))) return false;
        if(!(((FactoryValue<?>) other).myClass.equals(myClass))) return false;
        EnumMap<E,Object> data = (EnumMap<E, Object>) ((FactoryValue<?>) other).getData();
        for (Map.Entry<? extends Enum<?>, Object> e : this.data.entrySet()) {
            Object mine = e.getValue();
            Object theirs = data.get(e.getKey());
            if(mine.getClass().equals(theirs.getClass())) {
                if(!mine.equals(theirs)) return false;
            }
            else if(!mine.toString().equalsIgnoreCase(theirs.toString())) return false;
        }
        return true;
    }
}
