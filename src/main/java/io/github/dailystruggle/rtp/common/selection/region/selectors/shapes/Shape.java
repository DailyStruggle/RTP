package io.github.dailystruggle.rtp.common.selection.region.selectors.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.logging.Level;


public abstract class Shape<E extends Enum<E>> extends FactoryValue<E> {
    public final String name;

    protected final List<BiPredicate<UUID, RTPLocation>> verifiers = new ArrayList<>();

    /**
     * @param name - unique name of shape
     */
    public Shape( Class<E> eClass, String name, EnumMap<E, Object> data ) throws IllegalArgumentException {
        super( eClass, name );
        this.name = name;
        this.data.putAll( data );
        for ( E val : myClass.getEnumConstants() ) {
            if ( !data.containsKey( val) ) throw new IllegalArgumentException(
                    "All values must be filled out on shape instantiation" );
        }
        try {
            loadLangFile( "shape" );
        } catch ( IOException e ) {
            RTP.log( Level.WARNING, e.getMessage(), e );
        }
    }

    public int[] rotate( int[] input, long degrees ) {
        double angle = Math.toRadians( degrees );

        double s = Math.sin( angle );
        double c = Math.cos( angle );

        int x = input[0];
        int z = input[1];

        // generate new point
        input[0] = ( int ) ( x * c - z * s );
        input[1] = ( int ) ( x * s + z * c );

        return input;
    }

    @Override
    public @NotNull EnumMap<E, Object> getData() {
        return data.clone();
    }

    public abstract int[] select();

    public abstract Map<String, CommandParameter> getParameters();

    @Override
    public Shape<E> clone() {
        return ( Shape<E> ) super.clone();
    }

    @Override
    public boolean equals( Object o ) {
        if( !o.getClass().equals( getClass() ) || !( (Shape<?> ) o ).myClass.equals( myClass) ) return false;
        EnumMap<E, Object> data1 = getData();
        EnumMap<E, Object> data2 = ( (Shape<E> ) o ).getData();
        for( Map.Entry<E,Object> entry : data1.entrySet() ) {
            E key = entry.getKey();
            Object value = entry.getValue();
            try {
                Number number1 = getNumber( key, 0 );
                try {
                    Number number2 = ( (Shape<E> ) o ).getNumber( key, 0 );
                    if( number1.doubleValue()!=number2.doubleValue() ) return false;
                } catch ( IllegalArgumentException ignored ) {
                    return false;
                }
            } catch ( IllegalArgumentException ignored ) {
                if( !value.toString().equals( data2.get( key ).toString()) ) return false;
            }
        }
        return true;
    }
}
