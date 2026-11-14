package pl.lokalizator.ui

import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import pl.lokalizator.MyFix
import pl.lokalizator.Peer

private fun dot(color: Int, size: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    setStroke(5, 0xFFFFFFFF.toInt())
    setSize(size, size)
}

@Composable
fun MapPanel(me: MyFix?, peers: List<Peer>, fitKey: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(14.0)
            onResume()
        }
    }
    val fitted = remember { intArrayOf(-1) }
    DisposableEffect(Unit) { onDispose { map.onPause(); map.onDetach() } }

    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        m.overlays.clear()
        val pts = mutableListOf<GeoPoint>()
        fun add(lat: Double, lon: Double, acc: Float?, title: String, color: Int) {
            val gp = GeoPoint(lat, lon)
            pts += gp
            if (acc != null && acc > 15) {
                m.overlays.add(Polygon(m).apply {
                    points = Polygon.pointsAsCircle(gp, acc.toDouble())
                    fillPaint.color = (color and 0x00FFFFFF) or 0x22000000
                    outlinePaint.color = (color and 0x00FFFFFF) or 0x66000000
                    outlinePaint.strokeWidth = 2f
                    infoWindow = null
                })
            }
            m.overlays.add(Marker(m).apply {
                position = gp
                this.title = title
                icon = dot(color, 44)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            })
        }
        me?.let { add(it.lat, it.lon, it.acc, "Ty", 0xFF3A86FF.toInt()) }
        peers.forEach { p ->
            if (p.lat != null && p.lon != null) add(p.lat, p.lon, p.acc, p.name, 0xFFE4572E.toInt())
        }
        if (fitted[0] != fitKey && pts.isNotEmpty()) {
            fitted[0] = fitKey
            m.post {
                if (pts.size == 1) {
                    m.controller.setZoom(16.0); m.controller.setCenter(pts[0])
                } else {
                    m.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.6f), false)
                    if (m.zoomLevelDouble > 18) m.controller.setZoom(18.0)
                }
            }
        }
        m.invalidate()
    })
}
