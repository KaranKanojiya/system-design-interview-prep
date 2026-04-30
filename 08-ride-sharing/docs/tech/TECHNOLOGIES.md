# Technologies & Infrastructure for the Ride-Sharing System

> Interview-ready reference for a Senior Java developer.
> A ride-sharing system sits at the intersection of spatial computing, real-time streaming, and distributed systems.
> Know QuadTree internals, Haversine math, spatial database tradeoffs, and real-time protocols.

---

## Table of Contents

| Technology | Why It's Here | Interview Relevance |
|------------|--------------|---------------------|
| Spatial Indexing | QuadTree, R-Tree, KD-Tree, GeoHash, H3 | HIGH -- core of "find nearby drivers" |
| Haversine Formula | Distance calculation on Earth's surface | HIGH -- asked in every geo interview |
| PostGIS / MongoDB Geo | Production spatial databases | MEDIUM -- compare to our in-memory approach |
| Redis GEO | In-memory geospatial with GEORADIUS | HIGH -- common cache layer for locations |
| WebSocket / MQTT / gRPC | Real-time location streaming | MEDIUM -- how drivers send GPS updates |
| Kafka | Event streaming for ride events | MEDIUM -- async architecture |
| Java Data Structures | QuadTree, HashMap, ConcurrentHashMap, PriorityQueue | HIGH -- implementation details |

---

## 1. Spatial Indexing: The Core Problem

### The Problem

```
  Given: 100,000 available drivers in a city
  Query: "Find the 5 nearest drivers to (37.7749, -122.4194)"

  BRUTE FORCE:
  for each driver in 100,000:
      distance = haversine(rider, driver)
      if distance < 5km: add to candidates
  sort candidates by distance
  return top 5

  Time: O(n) for EVERY query
  At 1000 requests/second = 100,000,000 distance calculations/second
  This doesn't scale.

  SPATIAL INDEX:
  quadTree.query(boundingBox around 5km radius)
  -> returns ~50 drivers in that area (not 100,000)
  -> sort 50 by distance, return top 5

  Time: O(log n) per query for the tree traversal
  At 1000 requests/second = manageable
```

---

## 2. QuadTree (Our Implementation)

### What Is a QuadTree?

A QuadTree is a tree data structure where each internal node has exactly four children, representing the four quadrants of a 2D space (NW, NE, SW, SE). Points are stored in leaf nodes. When a leaf exceeds its capacity, it splits into four children.

### ASCII Diagram -- QuadTree Structure

```
  World Bounding Box: (-90, -180) to (90, 180)
  Capacity per node: 4 points

  Level 0 (root):
  +-----------------------------------+
  |                                   |
  |         ENTIRE WORLD              |
  |         (too many points)         |
  |                                   |
  +-----------------------------------+

  Level 1 (split into quadrants):
  +-----------------+-----------------+
  |                 |                 |
  |    NW           |    NE           |
  |  (Americas      |  (Europe/       |
  |   North)        |   Asia North)   |
  |                 |                 |
  +-----------------+-----------------+
  |                 |                 |
  |    SW           |    SE           |
  |  (Americas      |  (Africa/       |
  |   South)        |   Asia South)   |
  |                 |                 |
  +-----------------+-----------------+

  Level 2+ (keeps splitting where there are many drivers):
  +---------+-------+-----------------+
  |    |    |       |                 |
  | NW | NE |  NE   |                 |
  |----+----|       |                 |
  | SW | SE |       |                 |
  |    |    |       |                 |
  +---------+-------+-----------------+
  |                 |                 |
  |                 |                 |
  |    SW           |    SE           |
  |                 |                 |
  +---------+-------+-----------------+

  San Francisco downtown: many splits (dense area, many drivers)
  Rural Montana: no splits (sparse area, few drivers)
```

### QuadTree Implementation (Java)

```java
public class QuadTree {
    private final Bounds bounds;          // geographic bounding box
    private final int capacity;           // max points before split
    private final int maxDepth;           // prevent infinite subdivision
    private final List<Point> points;     // stored points (leaf node)
    private QuadTree nw, ne, sw, se;      // child quadrants
    private boolean divided;              // has this node split?
    private final int depth;              // current depth

    public QuadTree(double minLat, double minLng,
                    double maxLat, double maxLng, int capacity) {
        this.bounds = new Bounds(minLat, minLng, maxLat, maxLng);
        this.capacity = capacity;
        this.maxDepth = 20;               // ~1 meter resolution
        this.points = new ArrayList<>();
        this.divided = false;
        this.depth = 0;
    }

    // INSERT a driver location
    public boolean insert(Point point) {
        // (1) Point outside our bounds?
        if (!bounds.contains(point)) {
            return false;
        }

        // (2) Room in this leaf?
        if (!divided && points.size() < capacity) {
            points.add(point);
            return true;
        }

        // (3) Need to subdivide?
        if (!divided) {
            if (depth >= maxDepth) {
                points.add(point);  // can't split further
                return true;
            }
            subdivide();
        }

        // (4) Insert into correct child quadrant
        if (nw.insert(point)) return true;
        if (ne.insert(point)) return true;
        if (sw.insert(point)) return true;
        if (se.insert(point)) return true;

        return false; // should never reach here
    }

    // QUERY: find all points within a bounding box
    public List<Point> query(Bounds range) {
        List<Point> found = new ArrayList<>();

        // (1) Our bounds don't overlap the search range?
        if (!bounds.intersects(range)) {
            return found;  // prune this entire subtree
        }

        // (2) Check points in this node
        for (Point p : points) {
            if (range.contains(p)) {
                found.add(p);
            }
        }

        // (3) Recurse into children
        if (divided) {
            found.addAll(nw.query(range));
            found.addAll(ne.query(range));
            found.addAll(sw.query(range));
            found.addAll(se.query(range));
        }

        return found;
    }

    // REMOVE a point (for driver location updates)
    public boolean remove(String id) {
        // Check local points
        boolean removed = points.removeIf(p -> p.getId().equals(id));
        if (removed) return true;

        // Recurse into children
        if (divided) {
            if (nw.remove(id)) return true;
            if (ne.remove(id)) return true;
            if (sw.remove(id)) return true;
            if (se.remove(id)) return true;
        }
        return false;
    }

    private void subdivide() {
        double midLat = (bounds.minLat + bounds.maxLat) / 2;
        double midLng = (bounds.minLng + bounds.maxLng) / 2;

        nw = new QuadTree(midLat, bounds.minLng, bounds.maxLat, midLng,
                          capacity, maxDepth, depth + 1);
        ne = new QuadTree(midLat, midLng, bounds.maxLat, bounds.maxLng,
                          capacity, maxDepth, depth + 1);
        sw = new QuadTree(bounds.minLat, bounds.minLng, midLat, midLng,
                          capacity, maxDepth, depth + 1);
        se = new QuadTree(bounds.minLat, midLng, midLat, bounds.maxLng,
                          capacity, maxDepth, depth + 1);

        divided = true;

        // Re-insert existing points into children
        List<Point> existingPoints = new ArrayList<>(points);
        points.clear();
        for (Point p : existingPoints) {
            insert(p);
        }
    }
}
```

### QuadTree Operations: Complexity

| Operation | Average Case | Worst Case | Notes |
|-----------|-------------|-----------|-------|
| Insert | O(log n) | O(n) -- all points in one quadrant | Depth-limited to prevent degeneration |
| Query (range) | O(log n + k) | O(n) -- range covers entire tree | k = number of results |
| Remove | O(log n) | O(n) -- must search all nodes | Can be improved with ID index |
| Update | O(log n) | O(n) -- remove + insert | Two operations |
| Memory | O(n) | O(n * depth) | One point stored once |

### Numbered Flow -- Find Nearby Drivers

```
  Rider App          LocationService           QuadTree                  Haversine
     |                     |                      |                         |
     | (1) findNearby      |                      |                         |
     |   (37.77, -122.42,  |                      |                         |
     |    radius=5km)      |                      |                         |
     |------------------->|                      |                         |
     |                     | (2) convert radius   |                         |
     |                     |   to bounding box    |                         |
     |                     |   5km ~ 0.045 deg lat|                         |
     |                     |   -> (37.725,         |                         |
     |                     |       -122.465,       |                         |
     |                     |       37.815,         |                         |
     |                     |       -122.375)       |                         |
     |                     |                      |                         |
     |                     | (3) query(bbox)      |                         |
     |                     |--------------------->|                         |
     |                     |                      | (4) root.intersects?    |
     |                     |                      |   YES                   |
     |                     |                      |                         |
     |                     |                      | (5) NW.intersects?      |
     |                     |                      |   NO -> prune           |
     |                     |                      |                         |
     |                     |                      | (6) NE.intersects?      |
     |                     |                      |   NO -> prune           |
     |                     |                      |                         |
     |                     |                      | (7) SW.intersects?      |
     |                     |                      |   YES -> recurse        |
     |                     |                      |   found: [D1, D3, D7,   |
     |                     |                      |           D12, D45]     |
     |                     |                      |                         |
     |                     |                      | (8) SE.intersects?      |
     |                     |                      |   YES -> recurse        |
     |                     |                      |   found: [D22, D31]     |
     |                     |                      |                         |
     |                     | candidates=[D1,D3,   |                         |
     |                     |  D7,D12,D45,D22,D31] |                         |
     |                     |<---------------------|                         |
     |                     |                      |                         |
     |                     | (9) for each          |                         |
     |                     |   candidate, compute  |                         |
     |                     |   exact haversine     |                         |
     |                     |   distance            |                         |
     |                     |------------------------------------------>|
     |                     |                      |                         |
     |                     | (10) filter by        |                         |
     |                     |   actual radius       |                         |
     |                     |   (bbox is square,    |                         |
     |                     |    radius is circle)  |                         |
     |                     |                      |                         |
     |                     | (11) sort by distance |                         |
     |                     |   return top 5        |                         |
     |                     |                      |                         |
     | [D3: 1.2km,         |                      |                         |
     |  D7: 2.1km,         |                      |                         |
     |  D1: 2.8km,         |                      |                         |
     |  D22: 3.5km,        |                      |                         |
     |  D12: 4.1km]        |                      |                         |
     |<-------------------|                      |                         |
```

---

## 3. Haversine Formula

### What Is Haversine?

The Haversine formula calculates the great-circle distance between two points on a sphere given their latitudes and longitudes. It accounts for the curvature of the Earth.

### The Math

```
  Given two points:
  Point A: (lat1, lng1) in degrees
  Point B: (lat2, lng2) in degrees

  Convert to radians:
  lat1_r = lat1 * PI / 180
  lat2_r = lat2 * PI / 180
  dlat   = (lat2 - lat1) * PI / 180
  dlng   = (lng2 - lng1) * PI / 180

  Haversine formula:
  a = sin(dlat/2)^2 + cos(lat1_r) * cos(lat2_r) * sin(dlng/2)^2
  c = 2 * atan2(sqrt(a), sqrt(1-a))
  d = R * c

  Where R = 6371 km (Earth's mean radius)

  Result: d is the distance in kilometers
```

### Java Implementation

```java
public class Haversine {
    private static final double EARTH_RADIUS_KM = 6371.0;

    public static double distance(double lat1, double lng1,
                                   double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1))
                 * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng / 2) * Math.sin(dLng / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_KM * c;
    }
}

// Example:
// San Francisco to Oakland
// haversine(37.7749, -122.4194, 37.8044, -122.2712) = 13.1 km
```

### Why Haversine vs Euclidean?

```
  EUCLIDEAN (flat earth):
  distance = sqrt((lat2-lat1)^2 + (lng2-lng1)^2) * 111  // ~111 km per degree

  Problem: At latitude 60 (e.g., Helsinki), 1 degree of longitude = 55.5 km, not 111 km
  Error grows with latitude and distance.

  +------------------------------------------------------------------+
  | Latitude | 1 deg Lng (actual) | Euclidean estimate | Error       |
  |----------|-------------------|--------------------|-------------|
  | 0  (Equator) | 111.3 km     | 111.0 km           | 0.3%        |
  | 30 (Houston)  | 96.5 km     | 111.0 km           | 15%         |
  | 45 (Portland) | 78.8 km     | 111.0 km           | 41%         |
  | 60 (Helsinki) | 55.5 km     | 111.0 km           | 100%        |
  +------------------------------------------------------------------+

  For ride-sharing (short distances, <50km):
  - Haversine is accurate enough (error < 0.1%)
  - Vincenty formula is overkill (accounts for Earth's ellipsoid)
  - Euclidean is wrong at high latitudes
```

---

## 4. Spatial Indexing: Comparison of All Approaches

### QuadTree vs R-Tree vs KD-Tree vs GeoHash vs H3

```
  +------------------+--------------------------------------------------+
  | Data Structure   | How It Divides Space                             |
  +------------------+--------------------------------------------------+
  |                  |                                                  |
  | QuadTree         | +------+------+    Recursive quadrant splits    |
  |                  | | NW   | NE   |    Fixed at center point        |
  |                  | |------+------|    Good for point data          |
  |                  | | SW   | SE   |    Bad for overlapping regions  |
  |                  | +------+------+                                  |
  |                  |                                                  |
  | R-Tree           | +----+ +------+    Minimum bounding rectangles  |
  |                  | |    | |      |    Rectangles can OVERLAP       |
  |                  | +----+ |      |    Good for regions/polygons    |
  |                  |   +----+------+    Used in PostGIS, SQLite      |
  |                  |   |           |                                  |
  |                  |   +-----------+                                  |
  |                  |                                                  |
  | KD-Tree          |     |              Alternating axis splits      |
  |                  | ----+--------      (x, then y, then x...)      |
  |                  |     |    |          Good for nearest-neighbor   |
  |                  |     | ---+---       Bad for dynamic updates     |
  |                  |     |    |                                      |
  |                  |                                                  |
  | GeoHash          | +--+--+--+--+      Base32-encoded grid cells   |
  |                  | |9q|9r|  |  |      Prefix = coarser area       |
  |                  | +--+--+--+--+      "9q8yyk" = SF downtown      |
  |                  | |  |  |  |  |      Good for range queries      |
  |                  | +--+--+--+--+      Edge/boundary problems      |
  |                  |                                                  |
  | H3 (Uber)        |  /\  /\  /\        Hexagonal grid (icosahedron)|
  |                  | /  \/  \/  \       No edge/corner artifacts    |
  |                  | \  /\  /\  /       16 resolution levels        |
  |                  |  \/  \/  \/        Uniform neighbor distance   |
  |                  |                                                  |
  +------------------+--------------------------------------------------+
```

### Detailed Comparison Table

| Feature | QuadTree | R-Tree | KD-Tree | GeoHash | H3 |
|---------|----------|--------|---------|---------|-----|
| **Dimension** | 2D | N-D | N-D | 2D (lat/lng) | 2D (hexagonal) |
| **Insert** | O(log n) | O(log n) | O(log n) | O(1) encode | O(1) encode |
| **Range Query** | O(log n + k) | O(log n + k) | O(sqrt(n) + k) | O(prefix scan) | O(k-ring) |
| **Nearest Neighbor** | O(log n) approx | O(log n) | O(log n) exact | Must expand | k-ring expand |
| **Update/Move** | Remove + insert | Remove + insert | Rebuild needed | Re-encode | Re-encode |
| **Dynamic Data** | Good | Good | Poor | Excellent | Excellent |
| **Implementation** | Simple | Complex | Medium | Trivial | Library needed |
| **Used By** | Our project | PostGIS, SQLite | scikit-learn | Redis GEO, Elasticsearch | Uber, Facebook |
| **Best For** | Point queries, moderate data | Regions, polygons | Static datasets, NN | Caching, indexing | Production geo, hexagonal zones |
| **Weakness** | Unbalanced in dense areas | Complex rebalancing | Costly updates | Edge effects, Z-curve jumps | Requires library |

### GeoHash Deep Dive

```
  GeoHash encoding: (37.7749, -122.4194) -> "9q8yyk8yuv"

  Resolution levels:
  +--------+-------------+-----------+
  | Length | Cell Size   | Use Case  |
  +--------+-------------+-----------+
  | 1      | ~5000 km    | Continent |
  | 2      | ~1250 km    | Country   |
  | 3      | ~150 km     | State     |
  | 4      | ~40 km      | City      |
  | 5      | ~5 km       | District  |  <-- good for nearby driver queries
  | 6      | ~1.2 km     | Block     |  <-- good for surge zone
  | 7      | ~150 m      | Street    |
  | 8      | ~40 m       | Building  |
  | 9      | ~5 m        | Room      |
  +--------+-------------+-----------+

  Prefix property: "9q8yy" is a PARENT of "9q8yyk"
  All points starting with "9q8yy" are in the same ~5km cell

  PROBLEM: Edge effects
  +--------+--------+
  |  9q8yy | 9q8yz  |
  |  (A)   | (B)    |
  |     *  | *      |   <- A and B are 50 meters apart
  +--------+--------+      but in different GeoHash cells!

  Solution: Query the target cell AND its 8 neighbors
  Cells to query: 9q8yx, 9q8yy, 9q8yz, 9q8yw, 9q8yv, ...
```

### H3 Deep Dive (Uber's Approach)

```
  H3 is a hexagonal hierarchical spatial index developed by Uber.

  Why hexagons?
  +------+------+------+
  |      |      |      |
  |  Sq  |  Sq  |  Sq  |    Squares: corner neighbors are 41% farther
  |      |      |      |    than edge neighbors (sqrt(2) * edge)
  +------+------+------+
  |      |      |      |
  |  Sq  |  Sq  |  Sq  |    This creates non-uniform distances
  |      |      |      |
  +------+------+------+

    /\    /\    /\
   /  \  /  \  /  \
  / Hex\/ Hex\/ Hex\         Hexagons: ALL neighbors are equidistant
  \    /\    /\    /         6 neighbors, all at the same distance
   \  /  \  /  \  /          No corner artifacts
    \/    \/    \/
   /  \  /  \  /  \          This makes "nearby" queries uniform
  / Hex\/ Hex\/ Hex\
  \    /\    /\    /
   \  /  \  /  \  /

  H3 Resolution Levels:
  +------+----------------+-------------------+
  | Res  | Avg Hex Area   | Use Case          |
  +------+----------------+-------------------+
  | 0    | 4,357,449 km2  | Global            |
  | 4    | 1,770 km2      | Metropolitan area |
  | 7    | 5.16 km2       | Surge pricing zone|  <-- Uber uses this
  | 8    | 0.74 km2       | Pickup zone       |
  | 9    | 0.105 km2      | City block        |  <-- Uber uses this
  | 12   | 0.000307 km2   | Building          |
  | 15   | 0.0000009 km2  | Sub-meter         |
  +------+----------------+-------------------+
```

---

## 5. Spatial Databases: Production Choices

### PostGIS (PostgreSQL Extension)

```
  PostGIS adds spatial types and indexes to PostgreSQL.

  Schema:
  CREATE TABLE drivers (
      id          UUID PRIMARY KEY,
      name        TEXT NOT NULL,
      location    GEOMETRY(Point, 4326),  -- WGS84 coordinate system
      status      TEXT DEFAULT 'AVAILABLE',
      updated_at  TIMESTAMP DEFAULT NOW()
  );

  -- Spatial index (R-Tree via GiST)
  CREATE INDEX idx_drivers_location ON drivers USING GIST (location);

  -- Find drivers within 5km
  SELECT id, name,
         ST_Distance(
             location::geography,
             ST_SetSRID(ST_MakePoint(-122.4194, 37.7749), 4326)::geography
         ) AS distance_meters
  FROM drivers
  WHERE status = 'AVAILABLE'
    AND ST_DWithin(
        location::geography,
        ST_SetSRID(ST_MakePoint(-122.4194, 37.7749), 4326)::geography,
        5000  -- 5km in meters
    )
  ORDER BY distance_meters
  LIMIT 5;
```

### MongoDB Geospatial

```
  MongoDB has built-in geospatial support with 2dsphere indexes.

  // Document structure
  {
      "_id": "driver-123",
      "name": "Alice",
      "location": {
          "type": "Point",
          "coordinates": [-122.4194, 37.7749]  // [lng, lat] -- GeoJSON order
      },
      "status": "AVAILABLE"
  }

  // 2dsphere index
  db.drivers.createIndex({ "location": "2dsphere" })

  // Find drivers within 5km
  db.drivers.find({
      "status": "AVAILABLE",
      "location": {
          "$nearSphere": {
              "$geometry": {
                  "type": "Point",
                  "coordinates": [-122.4194, 37.7749]
              },
              "$maxDistance": 5000  // meters
          }
      }
  }).limit(5)
```

### Redis GEO

```
  Redis GEO uses a sorted set with GeoHash-encoded scores.
  Ultra-fast for real-time location queries.

  // Add driver locations
  GEOADD drivers -122.4194 37.7749 "driver-1"
  GEOADD drivers -122.4080 37.7850 "driver-2"
  GEOADD drivers -122.4300 37.7600 "driver-3"

  // Find drivers within 5km
  GEORADIUS drivers -122.4194 37.7749 5 km ASC COUNT 5

  // Result (sorted by distance):
  1) "driver-1"  0.0000 km
  2) "driver-2"  1.3200 km
  3) "driver-3"  1.8500 km

  // Get distance between two drivers
  GEODIST drivers "driver-1" "driver-2" km
  -> "1.3200"

  // Get position
  GEOPOS drivers "driver-1"
  -> 1) "-122.4194"
     2) "37.7749"

  Internal implementation:
  - Each member is stored in a sorted set
  - Score = 52-bit GeoHash of the coordinates
  - GEORADIUS: compute GeoHash range for bounding box, scan sorted set
  - Time complexity: O(N+log(M)) where N=results, M=total members
```

### Comparison: Spatial Database Options

| Feature | PostGIS | MongoDB | Redis GEO | Our QuadTree |
|---------|---------|---------|-----------|-------------|
| **Index Type** | R-Tree (GiST) | 2dsphere (B-tree on GeoHash) | Sorted set (GeoHash) | QuadTree |
| **Query Types** | Distance, within, intersects, contains | Near, within, intersects | Radius, distance, position | Range (bounding box) |
| **Persistence** | Disk (WAL) | Disk (WiredTiger) | Memory (optional RDB/AOF) | Memory only |
| **Scalability** | Single-node | Sharded | Clustered | Single-node |
| **Latency (radius query)** | 5-20ms | 5-15ms | 0.5-2ms | 0.1-1ms |
| **Throughput** | 5K qps | 10K qps | 100K qps | 500K qps (in-process) |
| **ACID** | Full | Document-level | None | None |
| **Best For** | Complex geo queries, polygons | Flexible schema, moderate scale | Hot data, real-time | Interviews, in-process |

---

## 6. Real-Time Location: Communication Protocols

### The Problem

```
  Drivers send GPS updates every 3-5 seconds.
  At scale: 500,000 active drivers * 0.33 updates/sec = 165,000 updates/sec

  Requirements:
  1. Low latency (< 100ms end-to-end)
  2. High throughput (100K+ messages/sec)
  3. Bi-directional (server pushes ride requests to driver)
  4. Battery-efficient on mobile
  5. Handles flaky mobile connections
```

### Protocol Comparison

```
  HTTP POLLING (bad)
  ==================
  Driver -> Server: POST /location {lat, lng}     every 3s
  Server -> Driver: GET /ride-requests             every 1s (polling!)

  Problem: 500K drivers * 1 poll/sec = 500K HTTP requests/sec just for polling
  Each HTTP request: TCP handshake + headers (~500 bytes overhead)
  Bandwidth: 500K * 500 bytes = 250 MB/sec just for overhead


  WEBSOCKET (common)
  ===================
  Driver <-> Server: persistent TCP connection

  Driver -> Server: {"type":"location","lat":37.77,"lng":-122.42}   (50 bytes)
  Server -> Driver: {"type":"ride_request","ride_id":"abc123"}       (push)

  Overhead: 2-6 bytes per frame (after initial HTTP upgrade handshake)
  Bandwidth: 500K * 50 bytes / 3s = 8.3 MB/sec (97% reduction)
  Server push: instant (no polling)


  MQTT (IoT-optimized)
  =====================
  Driver publishes to topic: drivers/{driver_id}/location
  Server subscribes to:      drivers/+/location (wildcard)
  Server publishes to:       drivers/{driver_id}/ride_requests

  Overhead: 2 bytes minimum per message
  QoS levels: 0 (fire-and-forget), 1 (at-least-once), 2 (exactly-once)
  Battery: very efficient (smaller packets, less CPU)
  Broker: Mosquitto, HiveMQ, AWS IoT Core


  gRPC STREAMING (modern)
  ========================
  service LocationService {
      rpc StreamLocation(stream LocationUpdate) returns (stream ServerEvent);
  }

  Driver -> Server: stream of LocationUpdate (protobuf, ~20 bytes each)
  Server -> Driver: stream of ServerEvent (ride requests, cancellations)

  Overhead: protobuf encoding (30-50% smaller than JSON)
  HTTP/2: multiplexing, header compression, binary framing
  Strongly typed: proto definitions = contract
```

### Protocol Comparison Table

| Feature | HTTP Polling | WebSocket | MQTT | gRPC Streaming |
|---------|-------------|-----------|------|----------------|
| **Connection** | New per request | Persistent | Persistent | Persistent (HTTP/2) |
| **Direction** | Client -> Server | Bidirectional | Pub/Sub | Bidirectional stream |
| **Overhead/msg** | ~500 bytes | 2-6 bytes | 2 bytes | ~5 bytes (protobuf) |
| **Server Push** | No (client polls) | Yes | Yes (subscribe) | Yes |
| **Battery** | Poor | Good | Excellent | Good |
| **Scale** | Poor (too many connections) | Good | Excellent | Good |
| **Reconnect** | N/A | Manual | Auto (clean/unclean session) | Auto (with keep-alive) |
| **Used By** | Legacy apps | Lyft, Grab | Uber (initially) | Uber (current), Google |
| **Best For** | Simple/infrequent | Web dashboards | IoT, mobile | Microservices |

---

## 7. Message Queues: Kafka for Ride Events

### Why Kafka for Ride-Sharing?

```
  Ride lifecycle generates a STREAM of events:

  ride.requested  ->  ride.matched  ->  ride.en_route  ->  ride.started
       |                   |                |                    |
       v                   v                v                    v
  [Kafka topic:     [Kafka topic:    [Kafka topic:        [Kafka topic:
   ride-events]      ride-events]     ride-events]         ride-events]

  Multiple consumers read the SAME stream independently:
  - Pricing Service: reads events to compute surge
  - Analytics: reads events for dashboards
  - Notification: reads events to send push notifications
  - Payment: reads ride.completed to charge rider
  - Fraud Detection: reads ALL events for anomaly detection

  Without Kafka: each service needs a direct connection to RideService
  With Kafka: RideService publishes once, N consumers read independently
```

### Kafka Topics for Ride-Sharing

```
  +-------------------------------------------------------------------+
  | Topic                    | Key          | Partitions | Retention  |
  +-------------------------------------------------------------------+
  | ride-events              | ride_id      | 64         | 7 days     |
  | driver-location-updates  | driver_id    | 128        | 1 hour     |
  | surge-pricing-updates    | zone_id      | 32         | 1 hour     |
  | payment-events           | rider_id     | 32         | 30 days    |
  | notification-requests    | user_id      | 16         | 1 day      |
  +-------------------------------------------------------------------+

  Partitioning by key ensures:
  - All events for ride-123 go to the SAME partition
  - Events within a ride are ORDERED (Kafka guarantees per-partition ordering)
  - ride.requested ALWAYS comes before ride.matched for the same ride
```

### Event Schema

```java
public class RideEvent {
    private String eventId;          // UUID for idempotency
    private String rideId;           // partition key
    private String eventType;        // REQUESTED, MATCHED, EN_ROUTE, etc.
    private Instant timestamp;
    private String riderId;
    private String driverId;         // null for REQUESTED
    private Location pickup;
    private Location dropoff;
    private Money fare;
    private double surgeMultiplier;
    private Map<String, String> metadata;
}

// Publishing:
kafkaProducer.send(new ProducerRecord<>(
    "ride-events",
    rideEvent.getRideId(),    // key = ride_id for ordering
    serialize(rideEvent)
));

// Consuming (Notification Service):
@KafkaListener(topics = "ride-events", groupId = "notification-service")
public void handleRideEvent(RideEvent event) {
    switch (event.getEventType()) {
        case "MATCHED":
            sendPush(event.getRiderId(), "Driver assigned!");
            sendPush(event.getDriverId(), "New ride request!");
            break;
        case "COMPLETED":
            sendEmailReceipt(event.getRiderId(), event.getFare());
            break;
    }
}
```

---

## 8. Java Data Structures Used

### Data Structure Mapping

| Data Structure | Where Used | Why This Structure |
|---------------|-----------|-------------------|
| `QuadTree` (custom) | `LocationService` -- spatial index for driver positions | O(log n) range queries for nearby drivers |
| `HashMap<String, Ride>` | `InMemoryRideRepository` -- ride storage | O(1) lookup by ride ID |
| `HashMap<String, Driver>` | `InMemoryDriverRepository` -- driver storage | O(1) lookup by driver ID |
| `ConcurrentHashMap` | Thread-safe variant for concurrent access | Multiple threads request rides simultaneously |
| `PriorityQueue<Driver>` | `NearestDriverStrategy` -- sort candidates by distance | O(n log k) to find k nearest among n candidates |
| `ArrayList<Point>` | QuadTree leaf nodes -- point storage | Dynamic size, good for small capacity (4-8 points) |
| `TreeMap<Long, String>` | Consistent hashing ring (if scaling LocationService) | O(log n) ceiling lookup for ring position |
| `EnumSet<RideStatus>` | `RideStatus.allowedTransitions()` -- state machine | O(1) contains check, memory-efficient for enums |

### ConcurrentHashMap vs HashMap

```
  In a ride-sharing system, multiple threads handle concurrent requests:

  Thread 1: Rider A requests ride  ->  reads drivers HashMap
  Thread 2: Rider B requests ride  ->  reads drivers HashMap
  Thread 3: Location update        ->  writes to drivers HashMap

  HashMap: NOT thread-safe
  - Thread 1 reads while Thread 3 writes = ConcurrentModificationException
  - Or worse: silent data corruption (partially updated bucket)

  ConcurrentHashMap: thread-safe without full locking
  - Uses bucket-level locking (lock striping)
  - Readers never block (volatile reads)
  - Writers only lock the bucket they're modifying
  - No ConcurrentModificationException during iteration

  public class InMemoryDriverRepository implements DriverRepository {
      // ConcurrentHashMap because:
      // - Ride requests read driver data (concurrent reads)
      // - Location updates write driver data (concurrent writes)
      // - Both happen simultaneously
      private final Map<String, Driver> store = new ConcurrentHashMap<>();
  }
```

### PriorityQueue for Nearest-K

```java
// Finding K nearest drivers efficiently

// Approach 1: Sort all N candidates, take first K
// Time: O(N log N)  -- sorts everything, wasteful if N >> K
List<Driver> sorted = candidates.stream()
    .sorted(Comparator.comparingDouble(d -> distance(pickup, d.getLocation())))
    .limit(5)
    .collect(Collectors.toList());

// Approach 2: Max-heap of size K (PriorityQueue)
// Time: O(N log K)  -- only maintains K elements in heap
PriorityQueue<Driver> nearest = new PriorityQueue<>(5,
    Comparator.comparingDouble(d ->
        -distance(pickup, d.getLocation())));  // MAX heap (negate distance)

for (Driver d : candidates) {
    double dist = distance(pickup, d.getLocation());
    if (nearest.size() < 5) {
        nearest.offer(d);
    } else if (dist < distance(pickup, nearest.peek().getLocation())) {
        nearest.poll();     // remove farthest
        nearest.offer(d);   // add closer driver
    }
}

// When N = 1000 and K = 5:
// Sort all:    O(1000 * log(1000)) = ~10,000 comparisons
// PriorityQueue: O(1000 * log(5))   = ~2,300 comparisons
// 4x improvement
```

---

## 9. Our Java Implementation vs Production

### What We Implement vs What We Reference

```
  +------------------------------------------------------------------+
  |                    OUR IMPLEMENTATION (Interview)                 |
  +------------------------------------------------------------------+
  | Component           | Implementation        | Why                |
  |---------------------+-----------------------+--------------------|
  | Spatial index       | QuadTree (custom)     | Shows understanding|
  | Distance calc       | Haversine (custom)    | Core algorithm     |
  | Storage             | ConcurrentHashMap     | In-memory, simple  |
  | Location updates    | Direct method call    | No network layer   |
  | Notifications       | System.out.println    | Demonstrates pattern|
  | Payment             | Interface + mock      | Not core to design |
  +------------------------------------------------------------------+

  +------------------------------------------------------------------+
  |                    PRODUCTION SYSTEM (Reference)                  |
  +------------------------------------------------------------------+
  | Component           | Technology            | Why                |
  |---------------------+-----------------------+--------------------|
  | Spatial index       | Redis GEO + PostGIS   | Scale + persistence|
  | Distance calc       | Google Maps Distance  | Traffic-aware ETA  |
  |                     |   Matrix API          |                    |
  | Storage             | PostgreSQL + Cassandra| ACID + scale       |
  | Location updates    | gRPC streaming        | Low overhead       |
  | Notifications       | Firebase + APNs       | Push notifications |
  | Payment             | Stripe / Braintree    | PCI compliance     |
  | Message queue       | Kafka                 | Event streaming    |
  | Caching             | Redis                 | Hot data layer     |
  | Service mesh        | Envoy / Istio         | Service-to-service |
  | Monitoring          | Prometheus + Grafana  | Observability      |
  +------------------------------------------------------------------+
```

### Interview Talking Points

```
  "In my implementation, I used a custom QuadTree for spatial indexing
   because it demonstrates the core algorithm. In production, I'd use
   Redis GEO for the hot cache layer (sub-millisecond radius queries)
   backed by PostGIS for durable storage and complex polygon queries
   (e.g., airport geofences, surge zones).

   For real-time location, I'd use gRPC bidirectional streaming --
   the driver streams GPS updates, the server streams ride requests
   back. This is what Uber migrated to from MQTT.

   For ride events, Kafka provides ordered, durable event streaming
   that multiple consumers (pricing, notifications, analytics) can
   read independently without affecting each other."
```

---

## 10. Technology Decision Matrix

### When the Interviewer Asks "Why Did You Choose X?"

| Decision | Options Considered | Chose | Why |
|----------|-------------------|-------|-----|
| Spatial index | QuadTree, R-Tree, GeoHash | QuadTree | Simplest to implement, good for point queries, demonstrates spatial partitioning |
| Distance formula | Euclidean, Haversine, Vincenty | Haversine | Accounts for Earth curvature, accurate for short distances, Vincenty overkill |
| Storage | HashMap, TreeMap, Database | ConcurrentHashMap | Thread-safe, O(1) lookup, sufficient for interview demo |
| State machine | if-else, State pattern, enum | Enum with transitions | Compile-time safety, self-documenting, all transitions in one place |
| Pricing extension | Subclass, if-else, Decorator | Decorator | Stack surge + tolls + discounts without class explosion |
| Object creation | Manual new, Spring DI, Factory | Factory (AppConfig) | No framework dependency, clear composition root |

### What to Say in Different Interview Contexts

| If Asked About... | Lead With | Then Mention |
|-------------------|-----------|-------------|
| "How do you find nearby drivers?" | QuadTree + Haversine | Production: Redis GEO, H3 hexagons |
| "How do you handle 1M GPS updates/sec?" | Kafka for ingestion, Redis for hot storage | Cassandra for persistence |
| "How do you compute ETA?" | Haversine for straight-line, then multiply by road factor | Google Distance Matrix API in production |
| "How do you scale the location service?" | Shard by geohash prefix, each shard owns a region | H3 cells for uniform distribution |
| "How do you handle driver crossing shard boundaries?" | Cell-based ownership with handoff protocol | Uber's Ringpop approach |

---

## Cross-Reference to Other Projects

| Project | Key Technology | Parallel to Ride-Sharing |
|---------|---------------|------------------------|
| 01 - URL Shortener | Base62 encoding, hash functions | Haversine: domain-specific math |
| 02 - Rate Limiter | Sliding window, token bucket | QuadTree: specialized data structure |
| 04 - Chat System | WebSocket for real-time | WebSocket/gRPC for location streaming |
| 05 - Social Media Feed | Kafka for fan-out | Kafka for ride event streaming |
| 07 - Distributed Cache | Consistent hashing, ConcurrentHashMap | Spatial hashing, ConcurrentHashMap |
| **08 - Ride Sharing** | **QuadTree, Haversine, Redis GEO, Kafka** | **Spatial computing + real-time streaming** |
