package com.littlebird.nauticalmap

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/**
 * Imports a deliberately explicit GeoJSON route format. Every route must carry
 * provenance metadata and port endpoint coordinates; geometry is never inferred.
 */
object GeoJsonRouteImporter {
    fun import(database: NauticalDatabase, jsonText: String): Int {
        val root = JSONObject(jsonText)
        require(root.optString("type") == "FeatureCollection") { "Expected a GeoJSON FeatureCollection." }
        val features = root.optJSONArray("features") ?: error("GeoJSON has no features array.")
        require(features.length() > 0) { "GeoJSON contains no route features." }

        val db = database.writableDatabase
        var imported = 0
        db.beginTransaction()
        try {
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")
                require(geometry.optString("type") == "LineString") { "Feature ${i + 1}: route geometry must be a LineString." }
                val properties = feature.getJSONObject("properties")
                val coordinates = geometry.getJSONArray("coordinates")
                require(coordinates.length() >= 2) { "Feature ${i + 1}: at least two route points are required." }

                val source = properties.optString("source_name").trim()
                require(source.isNotEmpty() && source != "null") { "Feature ${i + 1}: source_name is required." }
                val originId = upsertPort(db, properties, "origin")
                val destinationId = upsertPort(db, properties, "destination")
                require(originId != destinationId) { "Feature ${i + 1}: origin and destination cannot be the same port." }
                val variant = properties.optString("variant_name", "Main corridor").ifBlank { "Main corridor" }
                val routeValues = android.content.ContentValues().apply {
                    put("origin_port_id", originId)
                    put("destination_port_id", destinationId)
                    put("variant_name", variant)
                    if (properties.has("distance_nm")) put("distance_nm", properties.getDouble("distance_nm"))
                    put("vessel_categories", properties.optString("vessel_categories").takeIf { it.isNotBlank() })
                    put("ais_sample_count", properties.optInt("ais_sample_count", 0))
                    put("observation_start", properties.optString("observation_start").takeIf { it.isNotBlank() })
                    put("observation_end", properties.optString("observation_end").takeIf { it.isNotBlank() })
                    put("source_name", source)
                    put("source_license", properties.optString("source_license").takeIf { it.isNotBlank() })
                    put("quality_status", properties.optString("quality_status", "UNVERIFIED"))
                    put("notes", properties.optString("notes").takeIf { it.isNotBlank() })
                }
                val routeId = db.insertWithOnConflict("routes", null, routeValues, SQLiteDatabase.CONFLICT_IGNORE)
                val actualRouteId = if (routeId != -1L) routeId else db.rawQuery(
                    "SELECT id FROM routes WHERE origin_port_id=? AND destination_port_id=? AND variant_name=?",
                    arrayOf(originId.toString(), destinationId.toString(), variant)
                ).use { c -> require(c.moveToFirst()); c.getLong(0) }

                db.delete("route_points", "route_id=?", arrayOf(actualRouteId.toString()))
                for (pointIndex in 0 until coordinates.length()) {
                    val coordinate = coordinates.getJSONArray(pointIndex)
                    require(coordinate.length() >= 2) { "Feature ${i + 1}: invalid coordinate at point ${pointIndex + 1}." }
                    val longitude = coordinate.getDouble(0)
                    val latitude = coordinate.getDouble(1)
                    require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Feature ${i + 1}: coordinate outside WGS84 bounds." }
                    val point = android.content.ContentValues().apply {
                        put("route_id", actualRouteId)
                        put("sequence_no", pointIndex)
                        put("latitude", latitude)
                        put("longitude", longitude)
                        put("point_type", properties.optString("point_type", "AIS_DERIVED"))
                    }
                    db.insertOrThrow("route_points", null, point)
                }
                imported++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return imported
    }

    private fun upsertPort(db: SQLiteDatabase, p: JSONObject, prefix: String): Long {
        val name = p.optString("${prefix}_name").trim()
        val country = p.optString("${prefix}_country").trim()
        val latitude = p.getDouble("${prefix}_latitude")
        val longitude = p.getDouble("${prefix}_longitude")
        require(name.isNotEmpty() && country.isNotEmpty()) { "Both endpoint port names and countries are required." }
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Invalid endpoint coordinates for $name." }
        val code = p.optString("${prefix}_unlocode").trim().takeIf { it.isNotEmpty() && it != "null" }
        if (code != null) {
            db.rawQuery("SELECT id FROM ports WHERE unlocode=?", arrayOf(code)).use { c ->
                if (c.moveToFirst()) return c.getLong(0)
            }
        } else {
            db.rawQuery("SELECT id FROM ports WHERE name=? COLLATE NOCASE AND country=? COLLATE NOCASE", arrayOf(name, country)).use { c ->
                if (c.moveToFirst()) return c.getLong(0)
            }
        }
        val values = android.content.ContentValues().apply {
            put("name", name); put("country", country); put("unlocode", code)
            put("latitude", latitude); put("longitude", longitude)
            put("source_name", p.optString("source_name").takeIf { it.isNotBlank() })
            put("source_date", p.optString("observation_end").takeIf { it.isNotBlank() })
        }
        return db.insertOrThrow("ports", null, values)
    }
}
