package com.leslie.colorosnote.core.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Loose quadtree spatial index over world-coordinate bounding boxes, used by
 * the infinite canvas (spec 27) to render/query only visible objects.
 * Pure JVM; values are opaque ids (String object ids).
 */
public final class SpatialIndex {

    /** Axis-aligned box in world coordinates. */
    public static final class Box {
        public float left, top, right, bottom;

        public Box(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public boolean intersects(Box o) {
            return left <= o.right && right >= o.left && top <= o.bottom && bottom >= o.top;
        }

        public boolean contains(Box o) {
            return left <= o.left && right >= o.right && top <= o.top && bottom >= o.bottom;
        }

        public float centerX() { return (left + right) / 2f; }

        public float centerY() { return (top + bottom) / 2f; }
    }

    private static final int CAPACITY = 8;
    private static final int MAX_DEPTH = 12;

    private static final class Node {
        final Box bounds;
        final int depth;
        final List<String> ids = new ArrayList<>();
        final List<Box> boxes = new ArrayList<>();
        Node[] children; // NW, NE, SW, SE

        Node(Box bounds, int depth) {
            this.bounds = bounds;
            this.depth = depth;
        }
    }

    private final Node root;
    private int size;

    public SpatialIndex(Box worldBounds) {
        this.root = new Node(worldBounds, 0);
    }

    public int size() {
        return size;
    }

    public void insert(String id, Box box) {
        insert(root, id, box);
        size++;
    }

    public boolean remove(String id, Box box) {
        boolean removed = remove(root, id, box);
        if (removed) size--;
        return removed;
    }

    /** All ids whose boxes intersect the query region. */
    public List<String> query(Box region) {
        List<String> out = new ArrayList<>();
        query(root, region, out);
        return out;
    }

    private void insert(Node node, String id, Box box) {
        if (node.children != null) {
            int q = quadrant(node, box);
            if (q >= 0) {
                insert(node.children[q], id, box);
                return;
            }
        }
        node.ids.add(id);
        node.boxes.add(box);
        if (node.children == null && node.ids.size() > CAPACITY && node.depth < MAX_DEPTH) {
            split(node);
        }
    }

    private boolean remove(Node node, String id, Box box) {
        int idx = node.ids.indexOf(id);
        if (idx >= 0) {
            node.ids.remove(idx);
            node.boxes.remove(idx);
            return true;
        }
        if (node.children != null) {
            int q = quadrant(node, box);
            if (q >= 0) return remove(node.children[q], id, box);
            for (Node c : node.children) {
                if (remove(c, id, box)) return true;
            }
        }
        return false;
    }

    private void query(Node node, Box region, List<String> out) {
        if (!node.bounds.intersects(region)) return;
        for (int i = 0; i < node.ids.size(); i++) {
            if (node.boxes.get(i).intersects(region)) {
                out.add(node.ids.get(i));
            }
        }
        if (node.children != null) {
            for (Node c : node.children) {
                query(c, region, out);
            }
        }
    }

    private void split(Node node) {
        float midX = node.bounds.centerX();
        float midY = node.bounds.centerY();
        Box b = node.bounds;
        node.children = new Node[]{
                new Node(new Box(b.left, b.top, midX, midY), node.depth + 1),
                new Node(new Box(midX, b.top, b.right, midY), node.depth + 1),
                new Node(new Box(b.left, midY, midX, b.bottom), node.depth + 1),
                new Node(new Box(midX, midY, b.right, b.bottom), node.depth + 1)
        };
        List<String> oldIds = new ArrayList<>(node.ids);
        List<Box> oldBoxes = new ArrayList<>(node.boxes);
        node.ids.clear();
        node.boxes.clear();
        for (int i = 0; i < oldIds.size(); i++) {
            int q = quadrant(node, oldBoxes.get(i));
            if (q >= 0) {
                insert(node.children[q], oldIds.get(i), oldBoxes.get(i));
            } else {
                // straddles a boundary: stays on this node
                node.ids.add(oldIds.get(i));
                node.boxes.add(oldBoxes.get(i));
            }
        }
    }

    /** -1 when the box straddles a quadrant boundary. */
    private int quadrant(Node node, Box box) {
        float midX = node.bounds.centerX();
        float midY = node.bounds.centerY();
        boolean west = box.right <= midX;
        boolean east = box.left >= midX;
        boolean north = box.bottom <= midY;
        boolean south = box.top >= midY;
        if (west && north) return 0;
        if (east && north) return 1;
        if (west && south) return 2;
        if (east && south) return 3;
        return -1;
    }
}
