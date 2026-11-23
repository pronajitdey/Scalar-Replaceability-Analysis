import java.util.*;
import soot.*;

public class PTGraph {
    Map<Value, Set<Integer>> stackMap;
    Map<Integer, Map<SootField, Set<Integer>>> heapMap;

    public PTGraph() {
        this.stackMap = new LinkedHashMap<>();
        this.heapMap = new LinkedHashMap<>();
    }

    public void clear() {
        this.stackMap.clear();
        this.heapMap.clear();
    }

    public Map<Value, Set<Integer>> getSMapCopy() {
        return deepCopySMap(stackMap);
    }

    public Map<Integer, Map<SootField, Set<Integer>>> getHMapCopy() {
        return deepCopyHMap(heapMap);
    }

    public Map<Value, Set<Integer>> deepCopySMap(Map<Value, Set<Integer>> sMap) {
        if (sMap == null) return null;
        Map<Value, Set<Integer>> copy = new LinkedHashMap<>();
        for (Map.Entry<Value, Set<Integer>> entry : sMap.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() != null ? new HashSet<>(entry.getValue()) : null);
        }
        return copy;
    }

    public Map<Integer, Map<SootField, Set<Integer>>> deepCopyHMap(Map<Integer, Map<SootField, Set<Integer>>> hMap) {
        if (hMap == null) return null;
        Map<Integer, Map<SootField, Set<Integer>>> copy = new LinkedHashMap<>();

        for (Map.Entry<Integer, Map<SootField, Set<Integer>>> entry : hMap.entrySet()) {
            Map<SootField, Set<Integer>> innerCopy = new LinkedHashMap<>();

            if (entry.getValue() != null) {
                for (Map.Entry<SootField, Set<Integer>> innerEntry : entry.getValue().entrySet()) {
                    innerCopy.put(innerEntry.getKey(), innerEntry.getValue() !=  null ? new HashSet<>(innerEntry.getValue()) : null);
                }
            }

            copy.put(entry.getKey(), innerCopy);
        }
        return copy;
    }

    public PTGraph deepCopy() {
        PTGraph copy = new PTGraph();
        copy.stackMap = deepCopySMap(stackMap);
        copy.heapMap = deepCopyHMap(heapMap);
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PTGraph)) return false;
        PTGraph other = (PTGraph) o;
        return stackEquals(other.stackMap, stackMap) && heapEquals(other.heapMap, heapMap);
    }

    private boolean stackEquals(Map<Value, Set<Integer>> sMap1, Map<Value, Set<Integer>> sMap2) {
        if (sMap1 == null && sMap2 == null) return true;
        if (sMap1 == null || sMap2 == null || sMap1.size() != sMap2.size()) return false;
        for (Map.Entry<Value, Set<Integer>> entry : sMap1.entrySet()) {
            Set<Integer> s2 = sMap2.get(entry.getValue());
            if (s2 == null || !entry.getValue().equals(s2)) return false;
        }
        return true;
    }

    private boolean heapEquals(Map<Integer, Map<SootField, Set<Integer>>> h1, Map<Integer, Map<SootField, Set<Integer>>> h2) {
        if (h1 == null && h2 == null) return true;
        if (h1 == null || h2 == null || h1.size() != h2.size()) return false;
        for (Map.Entry<Integer, Map<SootField, Set<Integer>>> entry : h1.entrySet()) {
            Map<SootField, Set<Integer>> m2 = h2.get(entry.getValue());
            if (m2 == null || !fieldEquals(h1.get(entry.getValue()), m2)) return false;
        }
        return true;
    }

    private boolean fieldEquals(Map<SootField, Set<Integer>> f1, Map<SootField, Set<Integer>> f2) {
        if (f1 == null && f2 == null) return true;
        if (f1 == null || f2 == null || f1.size() != f2.size()) return false;
        for (Map.Entry<SootField, Set<Integer>> entry : f1.entrySet()) {
            Set<Integer> s2 = f2.get(entry.getValue());
            if (s2 == null || !entry.getValue().equals(s2)) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + deepStackHash(stackMap);
        result = 31 * result + deepHeapHash(heapMap);
        return result;
    }

    private int deepStackHash(Map<Value, Set<Integer>> sMap) {
        if (sMap == null) return 0;
        int hash = 0;
        for (Map.Entry<Value, Set<Integer>> entry : sMap.entrySet()) {
            hash += entry.getKey().hashCode() * 31 + entry.getValue().hashCode();
        }
        return hash;
    }

    private int deepHeapHash(Map<Integer, Map<SootField, Set<Integer>>> hMap) {
        if (hMap == null) return 0;
        int hash = 0;
        for (Map.Entry<Integer, Map<SootField, Set<Integer>>> entry : hMap.entrySet()) {
            hash += entry.getKey().hashCode() * 31 + deepFieldHash(entry.getValue());
        }
        return hash;
    }

    private int deepFieldHash(Map<SootField, Set<Integer>> fMap) {
        if (fMap == null) return 0;
        int hash = 0;
        for (Map.Entry<SootField, Set<Integer>> entry : fMap.entrySet()) {
            hash += entry.getKey().hashCode() * 31 + entry.getValue().hashCode();
        }
        return hash;
    }

    @Override
    public String toString() {
        return "sMap: " + stackMap + "\nhMap: " + heapMap + "\n";
    }
}