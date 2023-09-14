package io.github.dailystruggle.rtp.common.tools;

import java.util.HashSet;
import java.util.Set;

public class ParseString {
    public static Set<String> keywords( String input, Set<String> placeholders, Set<Character> front, Set<Character> back ) {
        Set<String> res = new HashSet<>();
        StringBuilder builder = null;
        for ( int i = 0; i < input.length(); i++ ) {
            char c = input.charAt( i );
            if ( builder != null ) {
                if ( back.contains( c) ) {
                    String s = builder.toString();
                    builder = null;
                    if ( placeholders.contains( s) ) res.add( s );
                } else {
                    builder.append( c );
                }
            } else if ( front.contains( c) ) {
                builder = new StringBuilder();
            }
        }
        return res;
    }
}
