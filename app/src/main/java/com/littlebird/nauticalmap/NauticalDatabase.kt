package com.littlebird.nauticalmap

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PortRecord(
    val id: Long,
    val name: String,
    val country: String,
    val unlocode: String?,
    val latitude: Double,
    val longitude: Double
)

data class RouteGeometry(val id: Long, val points: List<Pair<Double, Double>>)

data class RouteSummary(
    val id: Long,
    val origin: String,
    val destination: String,
    val variant: String,
    val distanceNm: Double?,
    val sourceName: String,
    val qualityStatus: String,
    val sourceLicense: String?,
    val notes: String?,
    val vesselCategories: String?,
    val aisSampleCount: Int,
    val observationStart: String?,
    val observationEnd: String?
)

/**
 * Local catalogue for ports and AIS-derived port-to-port commercial route variants.
 * Route points are stored in sequence and use decimal degrees (WGS84).
 * GeoJSON interchange uses longitude, latitude coordinate order.
 */
class NauticalDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE ports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                country TEXT NOT NULL,
                unlocode TEXT UNIQUE,
                latitude REAL NOT NULL CHECK(latitude BETWEEN -90 AND 90),
                longitude REAL NOT NULL CHECK(longitude BETWEEN -180 AND 180),
                source_name TEXT,
                source_date TEXT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE routes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                origin_port_id INTEGER NOT NULL REFERENCES ports(id),
                destination_port_id INTEGER NOT NULL REFERENCES ports(id),
                variant_name TEXT NOT NULL DEFAULT 'Main corridor',
                distance_nm REAL,
                vessel_categories TEXT,
                ais_sample_count INTEGER NOT NULL DEFAULT 0,
                observation_start TEXT,
                observation_end TEXT,
                source_name TEXT NOT NULL,
                source_license TEXT,
                quality_status TEXT NOT NULL DEFAULT 'UNVERIFIED',
                notes TEXT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(origin_port_id, destination_port_id, variant_name)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE route_points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                route_id INTEGER NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
                sequence_no INTEGER NOT NULL,
                latitude REAL NOT NULL CHECK(latitude BETWEEN -90 AND 90),
                longitude REAL NOT NULL CHECK(longitude BETWEEN -180 AND 180),
                point_type TEXT NOT NULL DEFAULT 'AIS_DERIVED',
                UNIQUE(route_id, sequence_no)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_routes_origin ON routes(origin_port_id)")
        db.execSQL("CREATE INDEX idx_routes_destination ON routes(destination_port_id)")
        db.execSQL("CREATE INDEX idx_route_points_route_sequence ON route_points(route_id, sequence_no)")
        db.execSQL("CREATE INDEX idx_ports_name ON ports(name COLLATE NOCASE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versioned migrations will be added as the route and import formats evolve.
    }

    fun getPorts(): List<PortRecord> {
        val result = mutableListOf<PortRecord>()
        readableDatabase.rawQuery(
            "SELECT id, name, country, unlocode, latitude, longitude FROM ports ORDER BY name COLLATE NOCASE",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PortRecord(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getDouble(4), cursor.getDouble(5)
                )
            }
        }
        return result
    }

    fun getRoutes(): List<RouteSummary> {
        val result = mutableListOf<RouteSummary>()
        readableDatabase.rawQuery("""
            SELECT r.id, p1.name, p2.name, r.variant_name, r.distance_nm, r.source_name,
                   r.quality_status, r.source_license, r.notes, r.vessel_categories,
                   r.ais_sample_count, r.observation_start, r.observation_end
            FROM routes r
            JOIN ports p1 ON p1.id = r.origin_port_id
            JOIN ports p2 ON p2.id = r.destination_port_id
            ORDER BY p1.name, p2.name, r.variant_name
        """.trimIndent(), null).use { cursor ->
            while (cursor.moveToNext()) {
                result += RouteSummary(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), if (cursor.isNull(4)) null else cursor.getDouble(4),
                    cursor.getString(5), cursor.getString(6), cursor.getString(7),
                    cursor.getString(8), cursor.getString(9), cursor.getInt(10),
                    cursor.getString(11), cursor.getString(12)
                )
            }
        }
        return result
    }

    fun getRouteGeometries(): List<RouteGeometry> {
        val result = mutableListOf<RouteGeometry>()
        val routeIds = mutableListOf<Long>()
        readableDatabase.rawQuery("SELECT id FROM routes ORDER BY id", null).use { c ->
            while (c.moveToNext()) routeIds += c.getLong(0)
        }
        for (routeId in routeIds) {
            val points = mutableListOf<Pair<Double, Double>>()
            readableDatabase.rawQuery(
                "SELECT latitude, longitude FROM route_points WHERE route_id=? ORDER BY sequence_no",
                arrayOf(routeId.toString())
            ).use { c -> while (c.moveToNext()) points += c.getDouble(0) to c.getDouble(1) }
            if (points.size >= 2) result += RouteGeometry(routeId, points)
        }
        return result
    }

    fun addPort(
        name: String, country: String, unlocode: String?,
        latitude: Double, longitude: Double, sourceName: String?, sourceDate: String?
    ): Long {
        require(name.isNotBlank() && country.isNotBlank())
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        val values = ContentValues().apply {
            put("name", name.trim()); put("country", country.trim())
            put("unlocode", unlocode?.trim()?.takeIf { it.isNotEmpty() })
            put("latitude", latitude); put("longitude", longitude)
            put("source_name", sourceName); put("source_date", sourceDate)
        }
        return writableDatabase.insertOrThrow("ports", null, values)
    }

    fun addRoute(
        originPortId: Long, destinationPortId: Long, variantName: String,
        distanceNm: Double?, vesselCategories: String?, aisSampleCount: Int,
        observationStart: String?, observationEnd: String?, sourceName: String,
        sourceLicense: String?, qualityStatus: String, notes: String?
    ): Long {
        require(originPortId != destinationPortId)
        require(variantName.isNotBlank() && sourceName.isNotBlank())
        val values = ContentValues().apply {
            put("origin_port_id", originPortId); put("destination_port_id", destinationPortId)
            put("variant_name", variantName); if (distanceNm != null) put("distance_nm", distanceNm)
            put("vessel_categories", vesselCategories); put("ais_sample_count", aisSampleCount)
            put("observation_start", observationStart); put("observation_end", observationEnd)
            put("source_name", sourceName); put("source_license", sourceLicense)
            put("quality_status", qualityStatus); put("notes", notes)
        }
        return writableDatabase.insertOrThrow("routes", null, values)
    }

    fun addRoutePoint(routeId: Long, sequence: Int, latitude: Double, longitude: Double, pointType: String = "AIS_DERIVED") {
        require(sequence >= 0 && latitude in -90.0..90.0 && longitude in -180.0..180.0)
        val values = ContentValues().apply {
            put("route_id", routeId); put("sequence_no", sequence)
            put("latitude", latitude); put("longitude", longitude); put("point_type", pointType)
        }
        writableDatabase.insertOrThrow("route_points", null, values)
    }

    companion object {
        private const val DATABASE_NAME = "nautical_routes.db"
        private const val DATABASE_VERSION = 1
    }
}
