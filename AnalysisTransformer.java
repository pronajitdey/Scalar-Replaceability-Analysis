import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.jimple.internal.*;
import soot.jimple.Stmt;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.toolkits.graph.*;

public class AnalysisTransformer extends SceneTransformer {

    static CallGraph cg;

    // Global allocInfos: line -> AllocInfo, populated across all methods.
    // Line number is unique per allocation site across the whole program
    // because each source file has unique line numbers.
    Map<Integer, AllocInfo> allocInfos = new LinkedHashMap<>();

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        cg = Scene.v().getCallGraph();

        var entrypoints = Scene.v().getEntryPoints();
        assert entrypoints.size() == 1;
        SootMethod mainMethod = entrypoints.get(0);

        // -----------------------------------------------------------------------
        // Step 1: Collect ALL reachable application methods via CHA call graph
        // BFS from main, visiting every concrete application method.
        // -----------------------------------------------------------------------
        Set<SootMethod> reachable = new LinkedHashSet<>();
        Queue<SootMethod> worklist = new ArrayDeque<>();
        worklist.add(mainMethod);
        reachable.add(mainMethod);

        while (!worklist.isEmpty()) {
            SootMethod m = worklist.poll();
            if (!m.isConcrete()) continue;
            try { m.getActiveBody(); } catch (Exception e) { continue; }

            Iterator<Edge> edges = cg.edgesOutOf(m);
            while (edges.hasNext()) {
                SootMethod tgt = edges.next().tgt();
                if (!tgt.getDeclaringClass().isApplicationClass()) continue;
                if (reachable.add(tgt)) worklist.add(tgt);
            }
        }

        // -----------------------------------------------------------------------
        // Step 2: For each reachable method, run PTAnalysis and collect
        // allocation sites into the global allocInfos map.
        // Also store the PTAnalysis result for use in escape analysis.
        // -----------------------------------------------------------------------
        Map<SootMethod, PTAnalysis> ptaMap = new LinkedHashMap<>();

        for (SootMethod m : reachable) {
            if (!m.isConcrete()) continue;
            Body body;
            try { body = m.getActiveBody(); } catch (Exception e) { continue; }

            UnitGraph cfg = new BriefUnitGraph(body);
            PTAnalysis pta = new PTAnalysis(cfg);
            ptaMap.put(m, pta);

            // Collect allocation sites from this method's body
            for (Unit u : body.getUnits()) {
                Stmt stmt = (Stmt) u;
                if (!(stmt instanceof JAssignStmt)) continue;
                if (!(((JAssignStmt) stmt).getRightOp() instanceof NewExpr)) continue;
                int line = stmt.getJavaSourceStartLineNumber();
                allocInfos.putIfAbsent(line, new AllocInfo(line));
            }
        }

        // -----------------------------------------------------------------------
        // Step 3: Escape analysis — scan every reachable method's body using
        // its converged PTGraph. Same logic as before but now applied to all
        // methods, not just main.
        // -----------------------------------------------------------------------
        for (SootMethod m : reachable) {
            if (!m.isConcrete()) continue;
            Body body;
            try { body = m.getActiveBody(); } catch (Exception e) { continue; }
            PTAnalysis pta = ptaMap.get(m);
            if (pta == null) continue;

            analyzeMethod(m, body, pta);
        }

        // -----------------------------------------------------------------------
        // Step 4: Output sorted by allocation line
        // -----------------------------------------------------------------------
        List<AllocInfo> sorted = new ArrayList<>(allocInfos.values());
        sorted.sort(Comparator.comparingInt(a -> a.line));

        for (AllocInfo info : sorted) {
            if (info.globalEscape || info.fieldWritten) {
                System.out.println("O" + info.line + " = N");
            } else {
                List<Integer> escLines = new ArrayList<>(info.escapeLines);
                Collections.sort(escLines);
                System.out.println("O" + info.line + " = Y"
                        + escLines.toString().replace(" ", ""));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Escape analysis for one method body using its converged PTGraph.
    // -----------------------------------------------------------------------
    void analyzeMethod(SootMethod m, Body body, PTAnalysis pta) {
        for (Unit u : body.getUnits()) {
            Stmt stmt = (Stmt) u;
            if (stmt instanceof IdentityStmt) continue;

            PTGraph pg   = pta.getFlowBefore(u);
            int     line = stmt.getJavaSourceStartLineNumber();

            // --- Static field store: global escape (transitive) ---
            if (stmt instanceof AssignStmt) {
                AssignStmt a   = (AssignStmt) stmt;
                Value      lhs = a.getLeftOp();
                Value      rhs = a.getRightOp();
                if (lhs instanceof StaticFieldRef && rhs instanceof Local) {
                    Set<Integer> escaped = new HashSet<>();
                    pta.collectReachable(rhs, pg, escaped);
                    for (int id : escaped)
                        if (allocInfos.containsKey(id))
                            allocInfos.get(id).globalEscape = true;
                }
            }

            // --- Return statement: escape via return ---
            // An object returned from a method escapes to the caller.
            // We don't know what the caller does with it, so we mark
            // globalEscape = true (conservative) unless this is main
            // (main's return value is never used).
            if (stmt instanceof ReturnStmt && !m.equals(
                    Scene.v().getEntryPoints().get(0))) {
                Value retVal = ((ReturnStmt) stmt).getOp();
                if (retVal instanceof Local) {
                    Set<Integer> escaped = new HashSet<>();
                    pta.collectReachable(retVal, pg, escaped);
                    for (int id : escaped)
                        if (allocInfos.containsKey(id))
                            allocInfos.get(id).globalEscape = true;
                }
            }

            if (!stmt.containsInvokeExpr()) continue;
            InvokeExpr invoke = stmt.getInvokeExpr();
            if (isInitOrSpecial(invoke)) continue;

            List<SootMethod> targets = new ArrayList<>();
            Iterator<Edge> edges = cg.edgesOutOf(stmt);
            while (edges.hasNext()) targets.add(edges.next().tgt());

            // --- Receiver ---
            if (invoke instanceof InstanceInvokeExpr) {
                Value base = ((InstanceInvokeExpr) invoke).getBase();

                Set<Integer> allEscaped = new HashSet<>();
                pta.collectReachable(base, pg, allEscaped);
                for (int id : allEscaped)
                    if (allocInfos.containsKey(id))
                        allocInfos.get(id).escapeLines.add(line);

                Set<Integer> direct = pg.stackMap.getOrDefault(
                        base, Collections.emptySet());
                for (int id : direct) {
                    if (!allocInfos.containsKey(id)) continue;
                    for (SootMethod t : targets)
                        analyzeCallee(t, -1, allocInfos.get(id),
                                allEscaped, new HashSet<>());
                }
            }

            // --- Arguments ---
            for (int i = 0; i < invoke.getArgCount(); i++) {
                Value arg = invoke.getArg(i);

                Set<Integer> allEscaped = new HashSet<>();
                pta.collectReachable(arg, pg, allEscaped);
                for (int id : allEscaped)
                    if (allocInfos.containsKey(id))
                        allocInfos.get(id).escapeLines.add(line);

                Set<Integer> direct = pg.stackMap.getOrDefault(
                        arg, Collections.emptySet());
                for (int id : direct) {
                    if (!allocInfos.containsKey(id)) continue;
                    for (SootMethod t : targets)
                        analyzeCallee(t, i, allocInfos.get(id),
                                allEscaped, new HashSet<>());
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Recursive callee field-write and transitive escape-line detection.
    //
    // paramIdx   : -1 = this, 0..N = parameter locals (0-based)
    // info       : AllocInfo for the DIRECT (stackMap-level) tracked object
    // allEscaped : all transitively heap-reachable objIds at this call site
    //
    // aliases   — locals that ARE the tracked object (copies/casts)
    // fieldRefs — locals loaded from a field of an alias (x = alias.f),
    //             transitively: if y = fieldRef.f then y is also a fieldRef
    //
    // Write on aliases   → info.fieldWritten  (direct object mutated)
    // Write on fieldRefs → mark fieldWritten on every object in allEscaped
    //                      that is NOT the direct object (heap-reachable
    //                      object mutated; conservative over the set)
    // -----------------------------------------------------------------------
    void analyzeCallee(SootMethod method, int paramIdx,
                       AllocInfo info,
                       Set<Integer> allEscaped,
                       Set<String> visited) {
        if (!method.isConcrete()) return;
        String key = method.getSignature() + "#" + paramIdx + "#" + info.line;
        if (visited.contains(key)) return;
        visited.add(key);

        Body body;
        try { body = method.getActiveBody(); }
        catch (Exception e) { info.fieldWritten = true; return; }

        Local tracked;
        try {
            if (paramIdx == -1) {
                if (method.isStatic()) return;
                tracked = body.getThisLocal();
            } else {
                List<Local> params = body.getParameterLocals();
                if (paramIdx >= params.size()) return;
                tracked = params.get(paramIdx);
            }
        } catch (Exception e) { info.fieldWritten = true; return; }

        if (!(tracked.getType() instanceof RefType)) return;

        // Build aliases and fieldRefs to fixpoint
        Set<Local> aliases   = new HashSet<>();
        Set<Local> fieldRefs = new HashSet<>();
        aliases.add(tracked);

        boolean changed = true;
        while (changed) {
            changed = false;
            for (Unit u : body.getUnits()) {
                if (!(u instanceof AssignStmt)) continue;
                AssignStmt a = (AssignStmt) u;
                if (!(a.getLeftOp() instanceof Local)) continue;
                Local lhsL = (Local) a.getLeftOp();
                Value rhs  = a.getRightOp();

                if (rhs instanceof Local && aliases.contains(rhs))
                    changed |= aliases.add(lhsL);
                else if (rhs instanceof CastExpr) {
                    Value op = ((CastExpr) rhs).getOp();
                    if (op instanceof Local && aliases.contains(op))
                        changed |= aliases.add(lhsL);
                } else if (rhs instanceof InstanceFieldRef) {
                    Value base = ((InstanceFieldRef) rhs).getBase();
                    if (base instanceof Local) {
                        if (aliases.contains(base))
                            changed |= fieldRefs.add(lhsL);
                        else if (fieldRefs.contains(base))
                            changed |= fieldRefs.add(lhsL);
                    }
                }
            }
        }

        for (Unit u : body.getUnits()) {
            Stmt stmt = (Stmt) u;
            if (stmt instanceof IdentityStmt) continue;
            int stmtLine = stmt.getJavaSourceStartLineNumber();

            // Field write detection
            if (stmt instanceof AssignStmt) {
                AssignStmt a = (AssignStmt) stmt;
                Value lhs = a.getLeftOp();
                if (lhs instanceof InstanceFieldRef) {
                    Value base = ((InstanceFieldRef) lhs).getBase();
                    if (base instanceof Local) {
                        if (aliases.contains(base)) {
                            info.fieldWritten = true;
                        } else if (fieldRefs.contains(base)) {
                            // Write to a heap-reachable object's field
                            for (int id : allEscaped) {
                                if (id == info.line) continue;
                                if (allocInfos.containsKey(id))
                                    allocInfos.get(id).fieldWritten = true;
                            }
                        }
                    }
                }
            }

            if (!stmt.containsInvokeExpr()) continue;
            InvokeExpr invoke = stmt.getInvokeExpr();
            if (isInitOrSpecial(invoke)) continue;

            List<SootMethod> targets = new ArrayList<>();
            Iterator<Edge> edges = cg.edgesOutOf(stmt);
            while (edges.hasNext()) targets.add(edges.next().tgt());

            if (invoke instanceof InstanceInvokeExpr) {
                Value base = ((InstanceInvokeExpr) invoke).getBase();
                if (base instanceof Local && aliases.contains((Local) base)) {
                    info.escapeLines.add(stmtLine);
                    for (SootMethod t : targets)
                        analyzeCallee(t, -1, info, allEscaped, visited);
                }
            }

            for (int i = 0; i < invoke.getArgCount(); i++) {
                Value arg = invoke.getArg(i);
                if (arg instanceof Local && aliases.contains((Local) arg)) {
                    info.escapeLines.add(stmtLine);
                    for (SootMethod t : targets)
                        analyzeCallee(t, i, info, allEscaped, visited);
                }
            }
        }
    }

    boolean isInitOrSpecial(InvokeExpr invoke) {
        return (invoke instanceof SpecialInvokeExpr)
                || invoke.getMethod().getName().equals("<init>");
    }

    static class AllocInfo {
        int          line;
        boolean      globalEscape = false;
        boolean      fieldWritten = false;
        Set<Integer> escapeLines  = new TreeSet<>();
        AllocInfo(int line) { this.line = line; }
    }
}