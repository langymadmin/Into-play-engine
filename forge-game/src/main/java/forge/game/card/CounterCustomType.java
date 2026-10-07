package forge.game.card;

import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

public record CounterCustomType(String keyword) implements CounterType {
    // Thread-safe: shared across concurrent game threads in one JVM (Endstep) and
    // lazily populated during gameplay via CounterType.getType() for custom-named
    // counters. A plain HashMap can corrupt under concurrent put / values() iterate.
    // Mirrors the sibling CounterKeywordType fix in patch 18.
    private static final Map<String, CounterCustomType> sMap = new java.util.concurrent.ConcurrentHashMap<>();

    public static CounterCustomType get(String s) {
        if (!sMap.containsKey(s)) {
            sMap.put(s, new CounterCustomType(s));
        }
        return sMap.get(s);
    }

    public static Set<CounterType> getValues() {
        return new LinkedHashSet<CounterType>(sMap.values());
    }
    
    @Override
    public String toString() {
        return keyword;
    }

    public String getName() {
        return keyword;
    }
}
