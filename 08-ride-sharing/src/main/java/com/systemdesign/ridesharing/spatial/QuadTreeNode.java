package com.systemdesign.ridesharing.spatial;

import com.systemdesign.ridesharing.model.Location;

import java.util.HashMap;
import java.util.Map;

/**
 * QuadTreeNode — A single node in the QuadTree.
 *
 * Structure:
 *   - Leaf node: contains up to MAX_POINTS actual points (driver locations)
 *   - Internal node: has exactly 4 children (NW, NE, SW, SE), no direct points
 *
 * WHY a tree of rectangles:
 *   Each node represents a rectangular area of the map. When it gets too full
 *   (more than MAX_POINTS), we split it into 4 equal quadrants. This gives us
 *   O(log n) insertion and spatial queries that skip entire branches if they
 *   don't intersect the search area.
 *
 * Children layout:
 *   ┌───────┬───────┐
 *   │  NW   │  NE   │     NW = children[0] (higher lat, lower lng)
 *   │  [0]  │  [1]  │     NE = children[1] (higher lat, higher lng)
 *   ├───────┼───────┤     SW = children[2] (lower lat, lower lng)
 *   │  SW   │  SE   │     SE = children[3] (lower lat, higher lng)
 *   │  [2]  │  [3]  │
 *   └───────┴───────┘
 */
public class QuadTreeNode {

    // Index constants for readability
    public static final int NW = 0;
    public static final int NE = 1;
    public static final int SW = 2;
    public static final int SE = 3;

    private final BoundingBox bounds;
    private final Map<String, Location> points;  // id -> location (only used in leaf nodes)
    private QuadTreeNode[] children;              // null for leaf nodes, [4] for internal nodes
    private final int depth;                      // how deep in the tree (root = 0)

    public QuadTreeNode(BoundingBox bounds, int depth) {
        this.bounds = bounds;
        this.depth = depth;
        this.points = new HashMap<>();
        this.children = null;  // starts as leaf
    }

    /**
     * Is this a leaf node (no children)?
     * Leaf nodes store actual points. Internal nodes delegate to children.
     */
    public boolean isLeaf() {
        return children == null;
    }

    /**
     * Subdivide this node into 4 quadrants.
     *
     * Called when a leaf node exceeds MAX_POINTS capacity.
     * After subdivision, all existing points are redistributed
     * into the appropriate child node.
     *
     * The split happens at the center of the bounding box:
     *   midLat = (minLat + maxLat) / 2
     *   midLng = (minLng + maxLng) / 2
     *
     * Each child gets a quarter of the parent's area:
     *   NW: [midLat, maxLat] x [minLng, midLng]
     *   NE: [midLat, maxLat] x [midLng, maxLng]
     *   SW: [minLat, midLat] x [minLng, midLng]
     *   SE: [minLat, midLat] x [midLng, maxLng]
     */
    public void subdivide() {
        double midLat = bounds.getCenterLat();
        double midLng = bounds.getCenterLng();
        int childDepth = depth + 1;

        children = new QuadTreeNode[4];

        // NW: top-left (higher lat, lower lng)
        children[NW] = new QuadTreeNode(
                new BoundingBox(midLat, bounds.getMaxLat(), bounds.getMinLng(), midLng),
                childDepth);

        // NE: top-right (higher lat, higher lng)
        children[NE] = new QuadTreeNode(
                new BoundingBox(midLat, bounds.getMaxLat(), midLng, bounds.getMaxLng()),
                childDepth);

        // SW: bottom-left (lower lat, lower lng)
        children[SW] = new QuadTreeNode(
                new BoundingBox(bounds.getMinLat(), midLat, bounds.getMinLng(), midLng),
                childDepth);

        // SE: bottom-right (lower lat, higher lng)
        children[SE] = new QuadTreeNode(
                new BoundingBox(bounds.getMinLat(), midLat, midLng, bounds.getMaxLng()),
                childDepth);

        // Redistribute existing points into children
        // Each point goes to exactly one child (the one whose bounds contain it)
        for (Map.Entry<String, Location> entry : points.entrySet()) {
            insertIntoChild(entry.getKey(), entry.getValue());
        }

        // Clear points from this node — it's now an internal node
        points.clear();
    }

    /**
     * Insert a point into the appropriate child node.
     * Used during subdivision to redistribute points.
     */
    private void insertIntoChild(String id, Location loc) {
        for (QuadTreeNode child : children) {
            if (child.getBounds().contains(loc)) {
                child.getPoints().put(id, loc);
                return;
            }
        }
        // Edge case: point on boundary — put it in the first child that accepts it
        // This can happen due to floating-point precision at midpoints
        children[0].getPoints().put(id, loc);
    }

    // --- Getters ---

    public BoundingBox getBounds() {
        return bounds;
    }

    public Map<String, Location> getPoints() {
        return points;
    }

    public QuadTreeNode[] getChildren() {
        return children;
    }

    public int getDepth() {
        return depth;
    }

    /**
     * Total number of points in this subtree.
     */
    public int countPoints() {
        if (isLeaf()) {
            return points.size();
        }
        int count = 0;
        for (QuadTreeNode child : children) {
            count += child.countPoints();
        }
        return count;
    }

    /**
     * Check if all children are leaves and their combined points are below threshold.
     * Used to decide whether to merge children back into a leaf.
     */
    public boolean canMerge(int maxPoints) {
        if (isLeaf()) return false;
        int total = 0;
        for (QuadTreeNode child : children) {
            if (!child.isLeaf()) return false;  // can only merge if all children are leaves
            total += child.getPoints().size();
        }
        return total <= maxPoints;
    }

    /**
     * Merge children back into this node (reverse of subdivide).
     * Called when enough points are removed that children are sparse.
     */
    public void merge() {
        if (isLeaf() || children == null) return;
        for (QuadTreeNode child : children) {
            points.putAll(child.getPoints());
        }
        children = null;  // back to leaf
    }
}
