package io.github.dailystruggle.rtp.common.tools;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class ParseString {
    public static Set<String> keywords(String input, Set<String> placeholders) {
        Set<String> res = new HashSet<>();
        StringBuilder builder = null;
        for(int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if(builder != null) {
                if(c == ']') {
                    String s = builder.toString();
                    builder = null;
                    if(placeholders.contains(s)) res.add(s);
                }
                else {
                    builder.append(c);
                }
            }
            else if(c == '[') {
                builder = new StringBuilder();
            }
        }
        return res;
    }
}
