package com.bpms.core.definition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Format-agnostic process definition (pivot model for Camunda + future native format).
 *
 * <p>Plan 34 Phase 1: {@code nodes}/{@code flows} only hold the TOP-LEVEL elements — a {@link SubProcessNode}
 * carries its own children in {@code childNodes}/{@code childFlows}. {@link #node}, {@link #outgoing} and
 * {@link #containingSubProcess} all search recursively into nested subprocesses so the engine can address
 * an embedded subprocess's inner elements by id, exactly like top-level ones.
 */
public record ProcessDefinition(
        String id,
        String name,
        String processId,
        List<FlowNode> nodes,
        List<SequenceFlow> flows,
        List<MessageDef> messages,
        List<LaneSet> laneSets,
        Map<String, Object> metadata
) {
    /** Depth-first: top-level nodes first, then recurses into every {@link SubProcessNode}'s children. */
    public Optional<FlowNode> node(String id) {
        for (FlowNode n : nodes) {
            if (n.id().equals(id)) {
                return Optional.of(n);
            }
            if (n instanceof SubProcessNode sub) {
                Optional<FlowNode> found = nodeIn(sub, id);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<FlowNode> nodeIn(SubProcessNode sub, String id) {
        for (FlowNode n : sub.childNodes()) {
            if (n.id().equals(id)) {
                return Optional.of(n);
            }
            if (n instanceof SubProcessNode nested) {
                Optional<FlowNode> found = nodeIn(nested, id);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /** Top-level flows out of {@code nodeId}; if none, searches each {@link SubProcessNode}'s own flows (recursively). */
    public List<SequenceFlow> outgoing(String nodeId) {
        List<SequenceFlow> top = flows.stream().filter(f -> f.sourceRef().equals(nodeId)).toList();
        if (!top.isEmpty()) {
            return top;
        }
        for (FlowNode n : nodes) {
            if (n instanceof SubProcessNode sub) {
                List<SequenceFlow> found = outgoingIn(sub, nodeId);
                if (!found.isEmpty()) {
                    return found;
                }
            }
        }
        return List.of();
    }

    private static List<SequenceFlow> outgoingIn(SubProcessNode sub, String nodeId) {
        List<SequenceFlow> local = sub.childFlows().stream().filter(f -> f.sourceRef().equals(nodeId)).toList();
        if (!local.isEmpty()) {
            return local;
        }
        for (FlowNode n : sub.childNodes()) {
            if (n instanceof SubProcessNode nested) {
                List<SequenceFlow> found = outgoingIn(nested, nodeId);
                if (!found.isEmpty()) {
                    return found;
                }
            }
        }
        return List.of();
    }

    /** The {@link SubProcessNode} that directly owns {@code nodeId} as one of its {@code childNodes} (recursive). */
    public Optional<SubProcessNode> containingSubProcess(String nodeId) {
        for (FlowNode n : nodes) {
            if (n instanceof SubProcessNode sub) {
                Optional<SubProcessNode> found = containingIn(sub, nodeId);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<SubProcessNode> containingIn(SubProcessNode sub, String nodeId) {
        for (FlowNode n : sub.childNodes()) {
            if (n.id().equals(nodeId)) {
                return Optional.of(sub);
            }
            if (n instanceof SubProcessNode nested) {
                Optional<SubProcessNode> found = containingIn(nested, nodeId);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /** Every {@link SequenceFlow} in the definition: top-level plus every nested {@link SubProcessNode}'s own flows. */
    public List<SequenceFlow> allFlows() {
        List<SequenceFlow> all = new ArrayList<>(flows);
        collectNestedFlows(nodes, all);
        return List.copyOf(all);
    }

    private static void collectNestedFlows(List<FlowNode> candidates, List<SequenceFlow> out) {
        for (FlowNode n : candidates) {
            if (n instanceof SubProcessNode sub) {
                out.addAll(sub.childFlows());
                collectNestedFlows(sub.childNodes(), out);
            }
        }
    }

    /**
     * Every {@link FlowNode} in the definition: top-level plus every nested {@link SubProcessNode}'s own
     * children (plan 32 Phase 3 — lets {@code BoundarySupport} find {@code BoundaryEventNode}s attached to
     * an activity that lives inside an embedded subprocess).
     */
    public List<FlowNode> allNodes() {
        List<FlowNode> all = new ArrayList<>(nodes);
        collectNestedNodes(nodes, all);
        return List.copyOf(all);
    }

    private static void collectNestedNodes(List<FlowNode> candidates, List<FlowNode> out) {
        for (FlowNode n : candidates) {
            if (n instanceof SubProcessNode sub) {
                out.addAll(sub.childNodes());
                collectNestedNodes(sub.childNodes(), out);
            }
        }
    }
}
