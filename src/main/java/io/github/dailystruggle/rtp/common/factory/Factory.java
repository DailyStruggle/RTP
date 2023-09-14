package io.github.dailystruggle.rtp.common.factory;

import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On request, find a stored object with the correct name, clone it, and return it
 *
 * @param <T> type of values this factory will hold
 */
public class Factory<T extends FactoryValue<?>> {
    public final ConcurrentHashMap<String, T> map = new ConcurrentHashMap<>();

    public void add( String name, T value ) {
        map.put( name.toUpperCase(), value );
    }

    public Enumeration<String> list() {
        return map.keys();
    }

    public boolean contains( String name ) {
        name = name.toUpperCase();
        if ( !name.endsWith( ".YML") ) name = name + ".YML";
        return map.containsKey( name );
    }

    /**
     * @param name name of item
     * @return mutable copy of item
     */
    @Nullable
    public FactoryValue<?> construct( String name ) {
        String comparableName = name.toUpperCase();
        if ( !comparableName.endsWith( ".YML") ) comparableName = comparableName + ".YML";
        //guard constructor
        T value = map.get( comparableName );
        if ( value == null ) {
            if ( map.containsKey( "DEFAULT.YML" ) || !map.isEmpty() ) {
                value = map.getOrDefault( "DEFAULT.YML", map.values().stream().findAny().get() );
                T clone = ( T ) value.clone();
                clone.name = ( name.endsWith( ".yml") ) ? name : name + ".yml";

                if ( clone instanceof ConfigParser ) {
                    ConfigParser<?> configParser = ( ConfigParser<?> ) clone;
                    configParser.check( configParser.version, configParser.pluginDirectory, null );
                }
                value = clone;
            } else return null;
        }
        return value.clone();
    }

    @Nullable
    public FactoryValue<?> get( String name ) {
        T t = map.get( name.toUpperCase() );
        if ( t == null ) return null;
        return t.clone();
    }

    @NotNull
    public FactoryValue<?> getOrDefault( String name ) {
        name = name.toUpperCase();
        //guard constructor
        T value = ( T ) get( name );
        if ( value == null ) {
            if ( map.containsKey( "DEFAULT.YML") ) {
                value = ( T ) construct( name );
                map.put( name, value );
            } else return new ArrayList<>( map.values() ).get( 0 ).clone();
        }
        return value.clone();
    }
}
