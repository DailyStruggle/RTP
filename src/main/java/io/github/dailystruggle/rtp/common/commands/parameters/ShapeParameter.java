package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public class ShapeParameter extends CommandParameter {
    public ShapeParameter( String permission, String description, BiFunction<UUID, String, Boolean> isRelevant ) {
        super( permission, description, isRelevant );
        Factory<Shape<?>> factory = ( Factory<Shape<?>> ) RTP.factoryMap.get( RTP.factoryNames.shape );
        factory.map.forEach( (s, shape1 ) -> put( shape1.name, shape1.getParameters()) );
    }

    @Override
    public Set<String> values() {
        Factory<Shape<?>> factory = ( Factory<Shape<?>> ) RTP.factoryMap.get( RTP.factoryNames.shape );
        factory.map.forEach( (s, shape1 ) -> {
            if ( !subParamMap.containsKey( s.toUpperCase()) ) put( shape1.name, shape1.getParameters() );
        } );
        return RTP.factoryMap.get( RTP.factoryNames.shape ).map.keySet();
    }

    @Override
    public Map<String, CommandParameter> subParams( String parameter ) {
        parameter = parameter.toUpperCase();
        Factory<?> shapeFactory = RTP.factoryMap.get( RTP.factoryNames.shape );
        Map<String, CommandParameter> map = subParamMap.get( parameter );
        if ( map != null ) {
            return map;
        }
        Shape<?> shape = ( Shape<?> ) shapeFactory.get( parameter );
        if ( shape != null ) {
            map = shape.getParameters();
            subParamMap.put( parameter, map );
            return map;
        }
        return new ConcurrentHashMap<>();
    }

    public void put( String name, Map<String, CommandParameter> params ) {
        subParamMap.put( name.toUpperCase(), params );
    }
}
