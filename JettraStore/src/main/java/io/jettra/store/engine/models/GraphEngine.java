package io.jettra.store.engine.models;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class GraphEngine {
    private final String name;
    private final Set<String> vertices = ConcurrentHashMap.newKeySet();
    private final Map<String, List<Edge>> adjacencyList = new ConcurrentHashMap<>();

    public record Edge(String targetVertex, String label, Map<String, Object> properties) {}

    public GraphEngine(String name) {
        this.name = name;
    }

    public void addVertex(String vertexId) {
        vertices.add(vertexId);
        adjacencyList.computeIfAbsent(vertexId, k -> new CopyOnWriteArrayList<>());
    }

    public void addEdge(String fromVertex, String toVertex, String label, Map<String, Object> props) {
        addVertex(fromVertex);
        addVertex(toVertex);
        adjacencyList.get(fromVertex).add(new Edge(toVertex, label, props));
    }

    public List<Edge> getOutboundEdges(String vertexId) {
        return adjacencyList.getOrDefault(vertexId, Collections.emptyList());
    }

    public Map<String, List<Edge>> getAllEdges() {
        return Collections.unmodifiableMap(adjacencyList);
    }

    public Set<String> getVertices() { return vertices; }
    public String getName() { return name; }
    public int size() { return vertices.size(); }

    public void addEdgesBatch(Map<String, List<Edge>> batch) {
        for (var entry : batch.entrySet()) {
            vertices.add(entry.getKey());
            var list = adjacencyList.computeIfAbsent(entry.getKey(), k -> new CopyOnWriteArrayList<>());
            list.addAll(entry.getValue());
            for (Edge e : entry.getValue()) {
                vertices.add(e.targetVertex());
            }
        }
    }
}
