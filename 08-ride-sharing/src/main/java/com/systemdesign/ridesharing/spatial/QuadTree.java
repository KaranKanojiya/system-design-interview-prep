package com.systemdesign.ridesharing.spatial;

import com.systemdesign.ridesharing.model.Location;

import java.util.*;

/**
 * QuadTree — THE key spatial data structure for ride-sharing.
 *
 * WHY QuadTree for Uber/Lyft:
 *   We need to answer "find the 5 nearest drivers to this pickup location"
 *   in real-time. A brute-force scan of all drivers is O(n). A QuadTree
 *   reduces this to O(sqrt(n) + k) by partitioning 2D space into a tree
 *   where we can skip entire regions that are too far away.
 *
 * HOW IT WORKS (the algorithm, step by step):
 *
 * INSERT:
 *   1. Start at root. Does the node's bounding box contain the point? If no, reject.
 *   2. Is the node a leaf?
 *      a. Yes, and it has room: add the point directly.
 *      b. Yes, but it's full: SUBDIVIDE into 4 quadrants, redistribute existing
 *         points into children, then insert the new point into the correct child.
 *   3. Is the node internal (has children)? Recurse into the child whose bounding
 *      box contains the point.
 *
 * FIND NEARBY (range query + distance filter):
 *   1. Create a bounding box around the search center (using the radius).
 *   2. Recursively check each node:
 *      a. If node's bounds don't intersect the search box: SKIP (this is the speedup!)
 *      b. If leaf: check each point's actual Haversine distance to center.
 *      c. If internal: recurse into all children that intersect the search box.
 *   3. Sort results by distance, return top maxResults.
 *
 * REMOVE:
 *   1. Find the leaf containing the point (using the allPoints HashMap for O(1) lookup).
 *   2. Remove from the leaf.
 *   3. If the parent's children are now sparse enough, MERGE them back into a single leaf.
 *
 * Complexity:
 *   Insert:  O(log n) average, O(n) worst case (all points in same quadrant)
 *   Query:   O(sqrt(n) + k) where k = number of results
 *   Remove:  O(log n) average
 *   Space:   O(n)
 *
 * INTERVIEW TIP:
 *   "In production Uber, they use H3 (hexagonal hierarchical index) + Google S2.
 *   QuadTree is the classic interview answer. The key insight is spatial partitioning:
 *   we avoid checking drivers that are obviously too far away by organizing them
 *   into a tree of geographic regions."
 */
public class QuadTree implements SpatialIndex {

    /** Maximum points in a leaf before subdivision. */
    private static final int MAX_POINTS = 4;

    /** Maximum depth to prevent infinite subdivision (e.g., many points at same location). */
    private static final int MAX_DEPTH = 10;

    /** Root node — covers the entire search area. */
    private QuadTreeNode root;

    /**
     * allPoints — HashMap for O(1) lookup of any point by ID.
     *
     * WHY we need this:
     *   The QuadTree structure is great for spatial queries, but finding a
     *   specific point by ID requires traversing the tree. Since drivers
     *   update their location every few seconds (remove + reinsert), we need
     *   O(1) lookup to make update() efficient.
     *
     * This is a common pattern: use the tree for spatial queries, use the
     * HashMap for point-level operations.
     */
    private final HashMap<String, Location> allPoints;

    /**
     * Construct a QuadTree covering the given bounding box.
     *
     * For a city-scale system, the bounds might be:
     *   San Francisco: BoundingBox(37.7, 37.85, -122.52, -122.35)
     *   Manhattan:     BoundingBox(40.7, 40.9, -74.02, -73.92)
     *   Global:        BoundingBox(-90, 90, -180, 180)
     */
    public QuadTree(BoundingBox bounds) {
        this.root = new QuadTreeNode(bounds, 0);
        this.allPoints = new HashMap<>();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  INSERT — Add a driver location to the tree
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Insert a point into the QuadTree.
     *
     * Algorithm:
     *   1. Check if the point is within the tree's bounds
     *   2. Recursively find the correct leaf
     *   3. If leaf is full and depth allows, subdivide
     *   4. Track in allPoints for O(1) lookup
     */
    @Override
    public void insert(String id, Location loc) {
        if (!root.getBounds().contains(loc)) {
            // Point is outside the tree's coverage area — can't insert
            // In production, you'd expand the tree or use a different region's tree
            System.out.printf("  [QuadTree] WARNING: %s is outside tree bounds, skipping%n", loc);
            return;
        }

        // If already exists, update instead of inserting duplicate
        if (allPoints.containsKey(id)) {
            update(id, loc);
            return;
        }

        insertRecursive(root, id, loc);
        allPoints.put(id, loc);
    }

    /**
     * Recursive insertion — the heart of the QuadTree.
     *
     * Walk down the tree until we reach a leaf:
     *   - If the leaf has room, add the point.
     *   - If the leaf is full and we haven't hit max depth, subdivide and retry.
     *   - If we've hit max depth, just add anyway (allows more than MAX_POINTS).
     */
    private void insertRecursive(QuadTreeNode node, String id, Location loc) {
        // CASE 1: Leaf node with room — just add the point
        if (node.isLeaf() && node.getPoints().size() < MAX_POINTS) {
            node.getPoints().put(id, loc);
            return;
        }

        // CASE 2: Leaf node that's FULL — subdivide into 4 quadrants
        if (node.isLeaf()) {
            // Check if we've hit max depth — if so, just overflow this leaf
            // WHY: prevents infinite subdivision when many points share the same
            // or very close locations (e.g., drivers parked at an airport)
            if (node.getDepth() >= MAX_DEPTH) {
                node.getPoints().put(id, loc);
                return;
            }

            // Subdivide: create 4 children and redistribute existing points
            node.subdivide();
            // Fall through to CASE 3 — now it's an internal node
        }

        // CASE 3: Internal node — find the correct child and recurse
        for (QuadTreeNode child : node.getChildren()) {
            if (child.getBounds().contains(loc)) {
                insertRecursive(child, id, loc);
                return;
            }
        }

        // Edge case: floating-point precision — point doesn't fit exactly
        // in any child. Put it in the first child (NW by convention).
        insertRecursive(node.getChildren()[QuadTreeNode.NW], id, loc);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  REMOVE — Remove a driver from the tree
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Remove a point from the QuadTree.
     *
     * Uses allPoints for O(1) lookup of the point's location,
     * then walks the tree to find and remove it from the correct leaf.
     * After removal, checks if parent nodes can be merged.
     */
    @Override
    public void remove(String id) {
        Location loc = allPoints.remove(id);
        if (loc == null) {
            return; // point not in tree
        }
        removeRecursive(root, id, loc);
    }

    /**
     * Recursive removal — find the leaf containing the point and remove it.
     * After removal, merge sparse children back into a leaf if possible.
     *
     * @return true if the point was found and removed
     */
    private boolean removeRecursive(QuadTreeNode node, String id, Location loc) {
        if (node.isLeaf()) {
            // Found the leaf — remove the point
            return node.getPoints().remove(id) != null;
        }

        // Internal node — recurse into the child that contains the location
        for (QuadTreeNode child : node.getChildren()) {
            if (child.getBounds().contains(loc)) {
                boolean removed = removeRecursive(child, id, loc);
                if (removed) {
                    // After removal, check if we can merge children back
                    // WHY merge: keeps the tree balanced and prevents unnecessary depth.
                    // If all 4 children are leaves and their total points fit in one leaf,
                    // merge them back into a single leaf node.
                    if (node.canMerge(MAX_POINTS)) {
                        node.merge();
                    }
                }
                return removed;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  UPDATE — Move a driver to a new location
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Update a point's location — remove from old position, insert at new.
     *
     * WHY remove + insert instead of in-place update:
     *   The point might move to a different quadrant. In-place update would
     *   leave the point in the wrong leaf, corrupting spatial queries.
     *   remove + insert guarantees correctness.
     *
     * In production Uber, driver location updates happen every 4 seconds.
     * For 1M active drivers, that's 250K updates/second — the O(1) HashMap
     * lookup in allPoints makes this feasible.
     */
    @Override
    public void update(String id, Location newLoc) {
        remove(id);
        // Insert directly (not calling this.insert() to avoid duplicate allPoints check)
        if (root.getBounds().contains(newLoc)) {
            insertRecursive(root, id, newLoc);
            allPoints.put(id, newLoc);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  FIND NEARBY — The money query for ride-sharing
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Find points near a center location within a given radius.
     *
     * Algorithm:
     *   1. Create a bounding box from the center + radius (approximate, fast check)
     *   2. Run a RANGE QUERY on the tree — collect all points whose leaf intersects the box
     *   3. Filter results by actual Haversine distance (exact check)
     *   4. Sort by distance, return top maxResults
     *
     * WHY two-stage filtering:
     *   Stage 1 (bounding box): O(sqrt(n)) — quickly eliminates most of the tree
     *   Stage 2 (Haversine):    O(k) — precise check only on candidates
     *
     *   A bounding box is a square, but we want a circle (radius). Points in the
     *   corners of the box are outside the radius. The Haversine check fixes this.
     *
     *   ┌────────────────┐
     *   │  ╱          ╲  │  <- corners are outside radius
     *   │╱              ╲│
     *   │     center     │
     *   │╲              ╱│
     *   │  ╲          ╱  │
     *   └────────────────┘
     */
    @Override
    public List<Map.Entry<String, Location>> findNearby(Location center, double radiusKm, int maxResults) {
        // Stage 1: Create bounding box for the search area
        BoundingBox searchBox = BoundingBox.fromCenter(center, radiusKm);

        // Stage 2: Collect candidates from tree (range query)
        List<Map.Entry<String, Location>> candidates = new ArrayList<>();
        rangeQuery(root, searchBox, candidates);

        // Stage 3: Filter by actual Haversine distance and compute distances
        List<Map.Entry<String, Location>> results = new ArrayList<>();
        for (Map.Entry<String, Location> candidate : candidates) {
            double dist = Location.distanceKm(center, candidate.getValue());
            if (dist <= radiusKm) {
                results.add(candidate);
            }
        }

        // Stage 4: Sort by distance (nearest first) and limit results
        results.sort((a, b) -> {
            double distA = Location.distanceKm(center, a.getValue());
            double distB = Location.distanceKm(center, b.getValue());
            return Double.compare(distA, distB);
        });

        // Return at most maxResults
        if (results.size() > maxResults) {
            return results.subList(0, maxResults);
        }
        return results;
    }

    /**
     * Range query — recursive search through the tree.
     *
     * This is the core spatial query algorithm:
     *   1. If node's bounds don't intersect the search box: SKIP entirely
     *      (this is the key optimization — we prune entire subtrees)
     *   2. If leaf: check each point against the search box
     *   3. If internal: recurse into all children that might intersect
     *
     * Complexity: O(sqrt(n) + k) where k = number of results
     *   WHY sqrt(n): in the worst case, the search box intersects O(sqrt(n))
     *   leaf nodes along the boundary of the box. Points inside the box
     *   are found quickly; the work is at the boundary.
     */
    private void rangeQuery(QuadTreeNode node, BoundingBox searchBox,
                            List<Map.Entry<String, Location>> results) {
        // PRUNING: if this node's bounds don't intersect the search area, skip it.
        // This is the entire reason QuadTree is fast — we avoid checking points
        // in regions that can't possibly be within the search radius.
        if (!node.getBounds().intersects(searchBox)) {
            return;
        }

        if (node.isLeaf()) {
            // Leaf node: check each point against the search box
            for (Map.Entry<String, Location> entry : node.getPoints().entrySet()) {
                if (searchBox.contains(entry.getValue())) {
                    results.add(entry);
                }
            }
            return;
        }

        // Internal node: recurse into all children
        // (only children whose bounds intersect the search box will actually be visited,
        //  because of the pruning check at the top of this method)
        for (QuadTreeNode child : node.getChildren()) {
            rangeQuery(child, searchBox, results);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public int size() {
        return allPoints.size();
    }

    @Override
    public void clear() {
        allPoints.clear();
        root = new QuadTreeNode(root.getBounds(), 0);
    }

    /** Get a point's location by ID — O(1) via HashMap. */
    public Location getLocation(String id) {
        return allPoints.get(id);
    }

    /** Get the root node (for debugging/visualization). */
    public QuadTreeNode getRoot() {
        return root;
    }

    /**
     * Count the total number of nodes in the tree (for debugging).
     */
    public int countNodes() {
        return countNodesRecursive(root);
    }

    private int countNodesRecursive(QuadTreeNode node) {
        if (node.isLeaf()) return 1;
        int count = 1; // this node
        for (QuadTreeNode child : node.getChildren()) {
            count += countNodesRecursive(child);
        }
        return count;
    }

    /**
     * Get the maximum depth of the tree (for debugging).
     */
    public int getMaxDepth() {
        return getMaxDepthRecursive(root);
    }

    private int getMaxDepthRecursive(QuadTreeNode node) {
        if (node.isLeaf()) return node.getDepth();
        int maxChildDepth = 0;
        for (QuadTreeNode child : node.getChildren()) {
            maxChildDepth = Math.max(maxChildDepth, getMaxDepthRecursive(child));
        }
        return maxChildDepth;
    }
}
