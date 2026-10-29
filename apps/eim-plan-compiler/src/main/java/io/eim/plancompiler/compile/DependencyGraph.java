package io.eim.plancompiler.compile;

import io.eim.plancompiler.model.PlanModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Component dependency graph (FR-406.1): Tarjan strongly-connected
 * components for publish-time cycle rejection, plus a deterministic
 * topological order that drives generated rule salience (NFR-08).
 */
public class DependencyGraph {

    public record GraphResult(List<List<String>> cycles, List<String> topological) {
    }

    public GraphResult analyze(PlanModel plan) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (PlanModel.Component c : plan.components()) {
            Set<String> deps = new LinkedHashSet<>();
            if (c.dependsOn() != null) {
                deps.addAll(c.dependsOn());
            }
            // Formula references are dependencies too, unioned with the
            // explicit list — one source of truth for cycles.
            if (c.formula() != null) {
                for (String token : c.formula().split("[^A-Za-z0-9_@]+")) {
                    if (token.startsWith("@") && token.length() > 1) {
                        String name = token.substring(1);
                        if (plan.component(name) != null) {
                            deps.add(name);
                        }
                    }
                }
            }
            edges.put(c.name(), deps.stream().filter(d -> plan.component(d) != null).toList());
        }
        List<List<String>> sccs = tarjan(edges);
        List<List<String>> cycles = sccs.stream()
                .filter(s -> s.size() > 1 || (s.size() == 1 && edges.get(s.get(0)).contains(s.get(0))))
                .toList();
        return new GraphResult(cycles, topoSort(edges));
    }

    /** Tarjan SCC (iterative-friendly sizes here; plans are small graphs). */
    private static List<List<String>> tarjan(Map<String, List<String>> edges) {
        Map<String, Integer> index = new LinkedHashMap<>();
        Map<String, Integer> low = new LinkedHashMap<>();
        Map<String, Boolean> onStack = new LinkedHashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        List<List<String>> sccs = new ArrayList<>();
        int[] counter = {0};
        for (String start : edges.keySet()) {
            if (!index.containsKey(start)) {
                strongConnect(start, edges, index, low, onStack, stack, sccs, counter);
            }
        }
        return sccs;
    }

    private static void strongConnect(String v, Map<String, List<String>> edges,
                                      Map<String, Integer> index, Map<String, Integer> low,
                                      Map<String, Boolean> onStack, Deque<String> stack,
                                      List<List<String>> sccs, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.put(v, true);
        for (String w : edges.getOrDefault(v, List.of())) {
            if (!index.containsKey(w)) {
                strongConnect(w, edges, index, low, onStack, stack, sccs, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (Boolean.TRUE.equals(onStack.get(w))) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            List<String> scc = new ArrayList<>();
            String w;
            do {
                w = stack.pop();
                onStack.put(w, false);
                scc.add(w);
            } while (!w.equals(v));
            sccs.add(scc);
        }
    }

    /**
     * Deterministic topological order (Kahn): dependencies first. The
     * frontier is sorted so two structurally equal plans always produce
     * the same order — that order drives rule salience (NFR-08).
     */
    private static List<String> topoSort(Map<String, List<String>> edges) {
        // dependents: dep -> who depends on it
        Map<String, java.util.List<String>> dependents = new java.util.HashMap<>();
        Map<String, Integer> remaining = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : edges.entrySet()) {
            remaining.put(e.getKey(), e.getValue().size());
            for (String dep : e.getValue()) {
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        java.util.PriorityQueue<String> ready = new java.util.PriorityQueue<>();
        remaining.forEach((node, left) -> {
            if (left == 0) {
                ready.add(node);
            }
        });
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String n = ready.poll();
            order.add(n);
            for (String m : dependents.getOrDefault(n, List.of())) {
                if (remaining.merge(m, -1, Integer::sum) == 0) {
                    ready.add(m);
                }
            }
        }
        return order;
    }
}
