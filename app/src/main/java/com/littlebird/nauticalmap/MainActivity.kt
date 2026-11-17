package com.littlebird.nauticalmap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        setContent { MaterialTheme { NauticalApp() } }
    }
}

private enum class AppTab(val label: String) {
    MAP("Map"), ROUTES("Routes"), PORTS("Ports"), OFFLINE("Offline data")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NauticalApp() {
    val context = LocalContext.current
    val database = remember { NauticalDatabase(context) }
    var selectedTab by remember { mutableStateOf(AppTab.MAP) }
    var mapRefresh by remember { mutableIntStateOf(0) }
    var location by remember { mutableStateOf<Location?>(null) }
    var locationMessage by remember { mutableStateOf("Waiting for GPS fix") }
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    var mapPacks by remember { mutableStateOf(MapPackStore.list(context)) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var ports by remember { mutableStateOf(database.getPorts()) }
    var routes by remember { mutableStateOf(database.getRoutes()) }
    var routeGeometries by remember { mutableStateOf(database.getRouteGeometries()) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permissionGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!permissionGranted) locationMessage = "Location permission was not granted"
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val result = runCatching { MapPackStore.import(context, uri) }
            statusMessage = result.fold(
                onSuccess = { "Imported map pack: ${it.name}" },
                onFailure = { "Import failed: ${it.message ?: "Unknown error"}" }
            )
            mapPacks = MapPackStore.list(context)
            mapRefresh++
        }
    }

    val routeImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val result = runCatching {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read the selected GeoJSON file.")
                GeoJsonRouteImporter.import(database, json)
            }
            statusMessage = result.fold(
                onSuccess = { "Imported $it route feature(s) from GeoJSON." },
                onFailure = { "Route import failed: ${it.message ?: "Invalid GeoJSON"}" }
            )
            routes = database.getRoutes()
            ports = database.getPorts()
            routeGeometries = database.getRouteGeometries()
            mapRefresh++
        }
    }

    DisposableEffect(permissionGranted) {
        if (!permissionGranted) {
            onDispose { }
        } else {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(value: Location) {
                    location = value
                    locationMessage = "GPS position updated"
                }
            }
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                        location = it
                        locationMessage = "Last known GPS position"
                    }
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, listener)
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { location = it }
                    manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 25f, listener)
                }
            } catch (_: Exception) {
                locationMessage = "GPS is unavailable or location permission is required"
            }
            onDispose { manager.removeUpdates(listener) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Little Bird Nautical", fontWeight = FontWeight.Bold)
                        Text("Offline marine map", style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Text(when (tab) { AppTab.MAP -> "⌖"; AppTab.ROUTES -> "↝"; AppTab.PORTS -> "⚓"; AppTab.OFFLINE -> "▣" }) },
                        label = { Text(tab.label, maxLines = 1) }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                AppTab.MAP -> MapScreen(
                    context = context,
                    mapPacks = mapPacks,
                    mapRefresh = mapRefresh,
                    routeGeometries = routeGeometries,
                    location = location,
                    locationMessage = locationMessage,
                    onRequestLocation = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    onOpenOffline = { selectedTab = AppTab.OFFLINE }
                )
                AppTab.ROUTES -> RoutesScreen(routes, statusMessage) { routeImportLauncher.launch(arrayOf("application/geo+json", "application/json", "application/*")) }
                AppTab.PORTS -> PortsScreen(ports)
                AppTab.OFFLINE -> OfflineScreen(
                    packs = mapPacks,
                    message = statusMessage,
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onDelete = { file ->
                        MapPackStore.delete(file)
                        mapPacks = MapPackStore.list(context)
                        mapRefresh++
                        statusMessage = "Removed ${file.name}"
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapScreen(
    context: Context,
    mapPacks: List<File>,
    mapRefresh: Int,
    routeGeometries: List<RouteGeometry>,
    location: Location?,
    locationMessage: String,
    onRequestLocation: () -> Unit,
    onOpenOffline: () -> Unit
) {
    var mapView by remember { mutableStateOf<MapView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView?.onPause()
            mapView?.onDetach()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(onClick = onRequestLocation, modifier = Modifier.weight(1f)) { Text("Enable GPS") }
            OutlinedButton(onClick = {
                location?.let { mapView?.controller?.animateTo(GeoPoint(it.latitude, it.longitude)) }
            }, enabled = location != null) { Text("My position") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(mapRefresh) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setUseDataConnection(false)
                            setMultiTouchControls(true)
                            setBuiltInZoomControls(true)
                            minZoomLevel = 2.0
                            maxZoomLevel = 18.0
                            controller.setZoom(3.0)
                            controller.setCenter(GeoPoint(35.0, -25.0))
                            // No network tile source is configured. Only imported offline archives are used.
                            if (mapPacks.isNotEmpty()) {
                                val offlineProvider = OfflineTileProvider(SimpleRegisterReceiver(ctx), mapPacks.toTypedArray())
                                setTileProvider(offlineProvider)
                                val archiveSource = offlineProvider.archives
                                    .asSequence()
                                    .flatMap { it.tileSources.asSequence() }
                                    .firstOrNull()
                                setTileSource(
                                    if (archiveSource != null) FileBasedTileSource.getSource(archiveSource)
                                    else TileSourceFactory.DEFAULT_TILE_SOURCE
                                )
                            } else {
                                setBackgroundColor(android.graphics.Color.rgb(9, 35, 54))
                            }
                            mapView = this
                        }
                    },
                    update = { view ->
                        mapView = view
                        view.overlays.removeAll { it is Marker || it is Polyline }
                        routeGeometries.forEach { route ->
                            val line = Polyline(view).apply {
                                setPoints(route.points.map { (latitude, longitude) -> GeoPoint(latitude, longitude) })
                                title = "Commercial route #${route.id}"
                                width = 5f
                            }
                            view.overlays.add(line)
                        }
                        location?.let { current ->
                            val marker = Marker(view).apply {
                                position = GeoPoint(current.latitude, current.longitude)
                                title = "Current GPS position"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            }
                            view.overlays.add(marker)
                        }
                        view.invalidate()
                    }
                )
            }
            if (mapPacks.isEmpty()) {
                Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No offline map installed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Import an MBTiles, SQLite or ZIP tile archive to display the map. The map does not download tiles from the internet.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onOpenOffline) { Text("Install offline map") }
                    }
                }
            }
        }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(locationMessage, style = MaterialTheme.typography.labelMedium)
                if (location != null) {
                    Text("LAT  ${String.format(Locale.US, "%.5f", location.latitude)}    LON  ${String.format(Locale.US, "%.5f", location.longitude)}")
                    Text("SOG  ${if (location.hasSpeed()) String.format(Locale.US, "%.1f kn", location.speed * 1.943844) else "—"}    COG  ${if (location.hasBearing()) String.format(Locale.US, "%.0f°", location.bearing) else "—"}")
                    Text("Accuracy  ${if (location.hasAccuracy()) String.format(Locale.US, "%.0f m", location.accuracy) else "—"}", style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("LAT  —    LON  —       SOG  —    COG  —", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RoutesScreen(routes: List<RouteSummary>, message: String?, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Commercial routes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Import route geometry as GeoJSON. Each feature must include its port endpoints and source/provenance metadata; imported lines are drawn on the map.")
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import GeoJSON routes") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (routes.isEmpty()) {
            EmptyState("No commercial routes loaded", "The route database is ready. Genuine AIS-derived route data will be added after sourcing and processing licensed historical tracks.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(routes) { route ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("${route.origin} → ${route.destination}", fontWeight = FontWeight.Bold)
                            Text("${route.variant} • ${route.distanceNm?.let { String.format(Locale.US, "%.0f NM", it) } ?: "Distance not set"}")
                            Text("Source: ${route.sourceName}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PortsScreen(ports: List<PortRecord>) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ports database", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Ports are stored independently from route geometry. Each port can be linked to multiple commercial route variants.")
        if (ports.isEmpty()) {
            EmptyState("No ports imported", "The local ports table is ready for port reference data, including port name, country, UN/LOCODE and coordinates.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ports) { port ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(port.name, fontWeight = FontWeight.Bold)
                            Text("${port.country} • ${port.unlocode ?: "No UN/LOCODE"}")
                            Text("${port.latitude}, ${port.longitude}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineScreen(
    packs: List<File>,
    message: String?,
    onImport: () -> Unit,
    onDelete: (File) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Offline map data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Map packs are copied into the app's private storage and remain available without mobile data or Wi-Fi.")
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import map pack") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        Text("Installed packs (${packs.size})", fontWeight = FontWeight.SemiBold)
        if (packs.isEmpty()) EmptyState("No map packs installed", "Supported archive formats: .mbtiles, .sqlite, .zip and .gemf.")
        packs.forEach { file ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name, fontWeight = FontWeight.Medium)
                        Text(formatBytes(file.length()), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { onDelete(file) }) { Text("Remove") }
                }
            }
        }
        HorizontalDivider()
        Text("Important chart notice", fontWeight = FontWeight.SemiBold)
        Text("This first version displays offline map tile archives. It does not yet contain official ENC/S-57 nautical charts, depth soundings, navigational warnings or validated passage plans. Do not use it as a replacement for ECDIS or approved navigation equipment.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyState(title: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private object MapPackStore {
    private val allowed = setOf("mbtiles", "sqlite", "zip", "gemf")
    fun directory(context: Context): File = File(context.filesDir, "maps").apply { mkdirs() }
    fun list(context: Context): List<File> = directory(context).listFiles()?.filter {
        it.isFile && it.extension.lowercase(Locale.ROOT) in allowed
    }?.sortedBy { it.name.lowercase(Locale.ROOT) } ?: emptyList()

    fun import(context: Context, uri: android.net.Uri): File {
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: "offline-map.mbtiles"
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in allowed) { "Choose an MBTiles, SQLite, ZIP or GEMF archive." }
        val safeName = displayName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(directory(context), safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not read the selected file.")
        require(destination.length() > 0L) { "The selected map archive is empty." }
        return destination
    }

    fun delete(file: File) { file.delete() }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
