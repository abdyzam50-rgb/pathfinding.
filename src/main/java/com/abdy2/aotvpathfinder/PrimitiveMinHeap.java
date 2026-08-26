package com.abdy2.aotvpathfinder;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

final class PrimitiveMinHeap {
    private final Long2IntOpenHashMap nodeToIndex;
    private long[] nodes;
    private double[] costs;
    private int size;

    PrimitiveMinHeap(int initialCapacity) {
        nodeToIndex = new Long2IntOpenHashMap(initialCapacity);
        nodeToIndex.defaultReturnValue(-1);
        nodes = new long[initialCapacity + 1];
        costs = new double[initialCapacity + 1];
        size = 0;
    }

    boolean isEmpty() { return size == 0; }

    int size() { return size; }

    boolean contains(long packedNode) { return nodeToIndex.containsKey(packedNode); }

    double getCost(long packedNode) {
        int index = nodeToIndex.get(packedNode);
        return index == -1 ? Double.MAX_VALUE : costs[index];
    }

    void insertOrUpdate(long packedNode, double cost) {
        int existingIndex = nodeToIndex.get(packedNode);
        if (existingIndex != -1) {
            if (cost < costs[existingIndex]) {
                costs[existingIndex] = cost;
                siftUp(existingIndex);
            }
        } else {
            ensureCapacity();
            size++;
            nodes[size] = packedNode;
            costs[size] = cost;
            nodeToIndex.put(packedNode, size);
            siftUp(size);
        }
    }

    long extractMin() {
        long minNode = nodes[1];
        nodeToIndex.remove(minNode);

        long lastNode = nodes[size];
        double lastCost = costs[size];
        nodes[1] = lastNode;
        costs[1] = lastCost;
        size--;

        if (size > 0) {
            nodeToIndex.put(lastNode, 1);
            siftDown(1);
        }
        return minNode;
    }

    private void ensureCapacity() {
        if (size >= nodes.length - 1) {
            int newCap = nodes.length * 2;
            long[] newNodes = new long[newCap];
            double[] newCosts = new double[newCap];
            System.arraycopy(nodes, 0, newNodes, 0, nodes.length);
            System.arraycopy(costs, 0, newCosts, 0, costs.length);
            nodes = newNodes;
            costs = newCosts;
        }
    }

    private void siftUp(int index) {
        int current = index;
        long nodeToMove = nodes[current];
        double costToMove = costs[current];

        while (current > 1) {
            int parentIndex = current >> 1;
            if (costToMove < costs[parentIndex]) {
                nodes[current] = nodes[parentIndex];
                costs[current] = costs[parentIndex];
                nodeToIndex.put(nodes[current], current);
                current = parentIndex;
            } else {
                break;
            }
        }

        nodes[current] = nodeToMove;
        costs[current] = costToMove;
        nodeToIndex.put(nodeToMove, current);
    }

    private void siftDown(int index) {
        int current = index;
        long nodeToMove = nodes[current];
        double costToMove = costs[current];
        int half = size >> 1;

        while (current <= half) {
            int childIndex = current << 1;
            double childCost = costs[childIndex];
            int rightIndex = childIndex + 1;

            if (rightIndex <= size && costs[rightIndex] < childCost) {
                childIndex = rightIndex;
                childCost = costs[rightIndex];
            }

            if (costToMove > childCost) {
                nodes[current] = nodes[childIndex];
                costs[current] = childCost;
                nodeToIndex.put(nodes[current], current);
                current = childIndex;
            } else {
                break;
            }
        }

        nodes[current] = nodeToMove;
        costs[current] = costToMove;
        nodeToIndex.put(nodeToMove, current);
    }
}
