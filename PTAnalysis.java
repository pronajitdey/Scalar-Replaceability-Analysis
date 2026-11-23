import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.jimple.internal.*;
import soot.toolkits.scalar.ForwardFlowAnalysis;
import soot.toolkits.graph.DirectedGraph;

public class PTAnalysis extends ForwardFlowAnalysis<Unit, PTGraph> {

    Set<Integer> negSet = new HashSet<>(Collections.singleton(-1));

    public PTAnalysis(DirectedGraph<Unit> graph) {
        super(graph);
        doAnalysis();
    }

    // Object ID IS the source line number of the allocation site.
    // Allocation site abstraction: all objects allocated at the same line
    // are represented by the same abstract object (the line number).
    protected int getObjID(Unit u) {
        return ((Stmt) u).getJavaSourceStartLineNumber();
    }

    @Override
    protected PTGraph newInitialFlow() {
        return new PTGraph();
    }

    @Override
    protected void copy(PTGraph src, PTGraph dest) {
        dest.stackMap = src.getSMapCopy();
        dest.heapMap  = src.getHMapCopy();
    }

    @Override
    protected void merge(PTGraph in1, PTGraph in2, PTGraph out) {
        out.clear();
        if (isEmpty(in1)) {
            out.stackMap = in2.getSMapCopy();
            out.heapMap  = in2.getHMapCopy();
        } else if (isEmpty(in2)) {
            out.stackMap = in1.getSMapCopy();
            out.heapMap  = in1.getHMapCopy();
        } else {
            out.stackMap = mergeSMap(in1.stackMap, in2.stackMap);
            out.heapMap  = mergeHMap(in1.heapMap,  in2.heapMap);
        }
    }

    @Override
    protected void flowThrough(PTGraph in, Unit unit, PTGraph out) {
        out.stackMap = in.getSMapCopy();
        out.heapMap  = in.getHMapCopy();

        Stmt stmt = (Stmt) unit;

        if (stmt instanceof JIdentityStmt) {
            handleIdentityStmt((JIdentityStmt) stmt, out);
        } else if (stmt instanceof JAssignStmt) {
            handleAssignStmt(unit, (JAssignStmt) stmt, out);
        }
        // JInvokeStmt / JReturnStmt: no effect on points-to graph
    }

    private void handleIdentityStmt(JIdentityStmt stmt, PTGraph out) {
        Value lhs = stmt.getLeftOp();
        out.stackMap.put(lhs, new HashSet<>(negSet));
    }

    private void handleAssignStmt(Unit unit, JAssignStmt stmt, PTGraph out) {
        Value lhs = stmt.getLeftOp();
        Value rhs = stmt.getRightOp();

        // x = new T()
        // ObjId = source line number of this allocation statement
        if (lhs instanceof Local && rhs instanceof NewExpr) {
            int id = getObjID(unit);
            out.stackMap.put(lhs, new HashSet<>(Collections.singleton(id)));
            out.heapMap.putIfAbsent(id, new LinkedHashMap<>());
            return;
        }

        // x = y
        if (lhs instanceof Local && rhs instanceof Local) {
            Set<Integer> rSet = out.stackMap.get(rhs);
            out.stackMap.put(lhs,
                (rSet == null || rSet.isEmpty())
                    ? new HashSet<>(negSet)
                    : new HashSet<>(rSet));
            return;
        }

        // x = y.f
        if (lhs instanceof Local && rhs instanceof InstanceFieldRef) {
            InstanceFieldRef fr    = (InstanceFieldRef) rhs;
            Value            base  = fr.getBase();
            SootField        field = fr.getField();
            Set<Integer>     bases = out.stackMap.getOrDefault(base, negSet);

            Set<Integer> result = new HashSet<>();
            for (int obj : bases) {
                if (obj == -1) { result.add(-1); continue; }
                Map<SootField, Set<Integer>> fmap = out.heapMap.get(obj);
                if (fmap != null && fmap.containsKey(field))
                    result.addAll(fmap.get(field));
                else
                    result.add(-1);
            }
            out.stackMap.put(lhs, result);
            return;
        }

        // x.f = y
        if (lhs instanceof InstanceFieldRef && rhs instanceof Local) {
            InstanceFieldRef fr     = (InstanceFieldRef) lhs;
            Value            base   = fr.getBase();
            SootField        field  = fr.getField();
            Set<Integer>     bases  = out.stackMap.getOrDefault(base, negSet);
            Set<Integer>     rhsSet = out.stackMap.getOrDefault(rhs,  negSet);

            boolean strong = (bases.size() == 1 && !bases.contains(-1));

            for (int obj : bases) {
                if (obj == -1) continue;
                out.heapMap.putIfAbsent(obj, new LinkedHashMap<>());
                Map<SootField, Set<Integer>> fmap = out.heapMap.get(obj);
                if (strong) {
                    fmap.put(field, new HashSet<>(rhsSet));
                } else {
                    Set<Integer> old = fmap.getOrDefault(field, new HashSet<>(negSet));
                    old.addAll(rhsSet);
                    fmap.put(field, old);
                }
            }
            return;
        }

        // x = StaticField
        if (lhs instanceof Local && rhs instanceof StaticFieldRef) {
            out.stackMap.put(lhs, new HashSet<>(negSet));
            return;
        }

        // x = invoke(...)
        if (lhs instanceof Local && rhs instanceof InvokeExpr) {
            out.stackMap.put(lhs, new HashSet<>(negSet));
            return;
        }

        // x = (T) y
        if (lhs instanceof Local && rhs instanceof CastExpr) {
            Value op = ((CastExpr) rhs).getOp();
            Set<Integer> s = out.stackMap.get(op);
            out.stackMap.put(lhs,
                (s == null || s.isEmpty()) ? new HashSet<>(negSet) : new HashSet<>(s));
            return;
        }

        // x = constant / primitive
        if (lhs instanceof Local) {
            out.stackMap.put(lhs, new HashSet<>(negSet));
        }
    }

    // ------------------------------------------------------------------
    // Heap DFS — used by AnalysisTransformer post-analysis.
    // Collects all objIds (= line numbers) reachable from val transitively.
    // ------------------------------------------------------------------

    public void collectReachable(Value val, PTGraph pg, Set<Integer> result) {
        if (!(val instanceof Local)) return;
        Set<Integer> roots = pg.stackMap.get(val);
        if (roots == null) return;
        for (int id : roots) dfsHeap(id, pg, result);
    }

    public void dfsHeap(int node, PTGraph pg, Set<Integer> visited) {
        if (node == -1 || visited.contains(node)) return;
        visited.add(node);
        Map<SootField, Set<Integer>> children = pg.heapMap.get(node);
        if (children == null) return;
        for (Set<Integer> targets : children.values()) {
            if (targets == null) continue;
            for (int child : targets) dfsHeap(child, pg, visited);
        }
    }

    // ------------------------------------------------------------------
    // Merge helpers
    // ------------------------------------------------------------------

    private boolean isEmpty(PTGraph p) {
        return p.stackMap.isEmpty() && p.heapMap.isEmpty();
    }

    private Map<Value, Set<Integer>> mergeSMap(
            Map<Value, Set<Integer>> sm1, Map<Value, Set<Integer>> sm2) {
        Map<Value, Set<Integer>> merged = new LinkedHashMap<>();
        Set<Value> keys = new HashSet<>();
        if (sm1 != null) keys.addAll(sm1.keySet());
        if (sm2 != null) keys.addAll(sm2.keySet());
        for (Value k : keys) {
            Set<Integer> s1 = sm1 != null ? sm1.get(k) : null;
            Set<Integer> s2 = sm2 != null ? sm2.get(k) : null;
            Set<Integer> m  = new HashSet<>();
            if ((s1 == null || s1.isEmpty()) && (s2 == null || s2.isEmpty()))
                m.add(-1);
            else {
                if (s1 != null) m.addAll(s1);
                if (s2 != null) m.addAll(s2);
            }
            merged.put(k, m);
        }
        return merged;
    }

    private Map<Integer, Map<SootField, Set<Integer>>> mergeHMap(
            Map<Integer, Map<SootField, Set<Integer>>> hm1,
            Map<Integer, Map<SootField, Set<Integer>>> hm2) {
        Map<Integer, Map<SootField, Set<Integer>>> merged = new LinkedHashMap<>();
        Set<Integer> keys = new HashSet<>();
        if (hm1 != null) keys.addAll(hm1.keySet());
        if (hm2 != null) keys.addAll(hm2.keySet());
        for (int k : keys) {
            Map<SootField, Set<Integer>> fm1 = hm1 != null ? hm1.get(k) : null;
            Map<SootField, Set<Integer>> fm2 = hm2 != null ? hm2.get(k) : null;
            Map<SootField, Set<Integer>> inner = new LinkedHashMap<>();
            Set<SootField> fkeys = new HashSet<>();
            if (fm1 != null) fkeys.addAll(fm1.keySet());
            if (fm2 != null) fkeys.addAll(fm2.keySet());
            for (SootField f : fkeys) {
                Set<Integer> s1 = fm1 != null ? fm1.get(f) : null;
                Set<Integer> s2 = fm2 != null ? fm2.get(f) : null;
                Set<Integer> m  = new HashSet<>();
                if ((s1 == null || s1.isEmpty()) && (s2 == null || s2.isEmpty()))
                    m.add(-1);
                else {
                    if (s1 != null) m.addAll(s1);
                    if (s2 != null) m.addAll(s2);
                }
                inner.put(f, m);
            }
            merged.put(k, inner);
        }
        return merged;
    }
}