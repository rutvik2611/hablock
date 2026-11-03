package dev.hablock.app.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.hablock.app.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2

/**
 * Tap-to-place zone picker built on osmdroid (Apache-2.0).
 *
 * The map is the one place a picture beats a form: a person can see the block, the building and the
 * radius in one glance. Tiles are fetched from OpenStreetMap; the coordinates never leave the phone.
 */
@Composable
fun ZoneMapDialog(
    initialLat: Double?,
    initialLon: Double?,
    initialRadius: Double,
    onDismiss: () -> Unit,
    onPicked: (lat: Double, lon: Double, radius: Double) -> Unit,
) {
    var centre by remember { mutableStateOf(if (initialLat != null && initialLon != null) Pair(initialLat, initialLon) else null) }
    var radius by remember { mutableStateOf(initialRadius.toInt().coerceIn(5, 1000)) }
    var mapView by remember { mutableStateOf<MapView?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.zone_map_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.zone_map_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    factory = { context ->
                        Configuration.getInstance().userAgentValue = context.packageName
                        MapView(context).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(16.0)
                            val start = centre?.let { GeoPoint(it.first, it.second) }
                                ?: GeoPoint(25.18928, 55.26410)
                            controller.setCenter(start)
                            // A tap is the whole interaction: centre the zone, then drag the radius.
                            setOnTouchListener { _, event ->
                                if (event.action == android.view.MotionEvent.ACTION_UP) {
                                    val projection = projection
                                    val point = projection.fromPixels(event.x.toInt(), event.y.toInt())
                                    val geo = GeoPoint(point.latitude, point.longitude)
                                    centre = geo.latitude to geo.longitude
                                    redraw(this@apply, geo, radius)
                                }
                                false
                            }
                            mapView = this
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${radius} m", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = radius.toFloat(),
                        onValueChange = {
                            radius = it.toInt()
                            mapView?.let { view -> centre?.let { c -> redraw(view, GeoPoint(c.first, c.second), radius) } }
                        },
                        valueRange = 5f..1000f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { centre?.let { onPicked(it.first, it.second, radius.toDouble()) } },
                enabled = centre != null,
            ) { Text(stringResource(R.string.zone_map_use)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )

    DisposableEffect(Unit) {
        onDispose {
            mapView?.onPause()
            mapView?.onDetach()
        }
    }
}

/** Marker at the centre plus a circle drawn as a polygon, since osmdroid has no circle overlay. */
private fun redraw(view: MapView, centre: GeoPoint, radiusMeters: Int) {
    view.overlays.removeAll { it is Polygon || it is Marker }
    view.overlays.add(
        Polygon(view).apply {
            points = circlePoints(centre, radiusMeters.toDouble())
            outlinePaint.color = android.graphics.Color.parseColor("#2F6FED")
            outlinePaint.strokeWidth = 4f
            fillPaint.color = android.graphics.Color.parseColor("#332F6FED")
        },
    )
    view.overlays.add(
        Marker(view).apply {
            position = centre
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Zone centre"
        },
    )
    view.invalidate()
}

/** A ring of geodesic offsets; 60 points is smooth at building zoom. */
internal fun circlePoints(centre: GeoPoint, radiusMeters: Double, steps: Int = 60): List<GeoPoint> {
    val earthRadius = 6_371_000.0
    val latRad = Math.toRadians(centre.latitude)
    val lonRad = Math.toRadians(centre.longitude)
    val angular = radiusMeters / earthRadius
    return (0 until steps).map { step ->
        val bearing = 2.0 * Math.PI * step / steps
        val lat2 = kotlin.math.asin(
            sin(latRad) * cos(angular) + cos(latRad) * sin(angular) * cos(bearing),
        )
        val lon2 = lonRad + atan2(
            sin(bearing) * sin(angular) * cos(latRad),
            cos(angular) - sin(latRad) * sin(lat2),
        )
        GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }
}
