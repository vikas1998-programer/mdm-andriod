package com.rrv.mdm.dpc

import android.location.Location
import com.rrv.mdm.dpc.data.model.GeofenceZone
import com.rrv.mdm.dpc.geofence.GeofenceTransitionEvaluator
import com.rrv.mdm.dpc.geofence.GeofenceTransitionEvent
import com.rrv.mdm.dpc.geofence.LocalGeofenceEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class LocalGeofenceEngineTest {

    private lateinit var engine: LocalGeofenceEngine
    private lateinit var evaluator: GeofenceTransitionEvaluator

    @Before
    fun setUp() {
        engine = LocalGeofenceEngine()
        evaluator = GeofenceTransitionEvaluator(
            engine = engine,
            deadbandMeters = 15.0,
            dwellThresholdMs = 1_000L, // 1 second for fast unit tests
            maxAcceptableAccuracyMeters = 35.0f
        )
    }

    @Test
    fun testHaversineDistanceCalculation() {
        val lat1 = 28.6139
        val lon1 = 77.2090
        val lat2 = 28.6315
        val lon2 = 77.2167

        val distance = engine.calculateHaversineDistance(lat1, lon1, lat2, lon2)
        assertTrue("Distance should be approximately 2km (actual: $distance)", distance in 1800.0..2400.0)
    }

    @Test
    fun testCircularGeofenceInsideAndOutside() {
        val zone = GeofenceZone(
            id = "geo-circular-1",
            name = "Headquarters Perimeter",
            zoneType = "CIRCULAR",
            centerLatitude = 28.535500,
            centerLongitude = 77.391000,
            radiusMeters = 500.0
        )

        val insideLoc = mock(Location::class.java).apply {
            `when`(latitude).thenReturn(28.536000)
            `when`(longitude).thenReturn(77.391500)
        }

        val outsideLoc = mock(Location::class.java).apply {
            `when`(latitude).thenReturn(28.545000)
            `when`(longitude).thenReturn(77.405000)
        }

        assertTrue("Expected location to be inside 500m radius", engine.isInsideZone(insideLoc, zone))
        assertFalse("Expected location to be outside 500m radius", engine.isInsideZone(outsideLoc, zone))
    }

    @Test
    fun testPolygonGeofenceRayCasting() {
        val polygonJson = """
            [
                {"lat": 28.5000, "lng": 77.3000},
                {"lat": 28.5000, "lng": 77.4000},
                {"lat": 28.6000, "lng": 77.4000},
                {"lat": 28.6000, "lng": 77.3000}
            ]
        """.trimIndent()

        val zone = GeofenceZone(
            id = "geo-poly-1",
            name = "Logistics Polygon Hub",
            zoneType = "POLYGON",
            centerLatitude = 28.5500,
            centerLongitude = 77.3500,
            radiusMeters = 0.0,
            polygonGeoJson = polygonJson
        )

        val centerInside = mock(Location::class.java).apply {
            `when`(latitude).thenReturn(28.5500)
            `when`(longitude).thenReturn(77.3500)
        }

        val farOutside = mock(Location::class.java).apply {
            `when`(latitude).thenReturn(28.7000)
            `when`(longitude).thenReturn(77.5000)
        }

        assertTrue("Expected point inside polygon", engine.isInsideZone(centerInside, zone))
        assertFalse("Expected point outside polygon", engine.isInsideZone(farOutside, zone))
    }

    @Test
    fun testHysteresisDwellSuppressionAndConfirmedBreach() {
        val zone = GeofenceZone(
            id = "geo-dwell-test",
            name = "Secure Storage Room",
            zoneType = "CIRCULAR",
            centerLatitude = 28.500000,
            centerLongitude = 77.500000,
            radiusMeters = 100.0
        )

        val outsideLoc = mock(Location::class.java).apply {
            `when`(latitude).thenReturn(28.505000) // ~550m outside
            `when`(longitude).thenReturn(77.505000)
            `when`(hasAccuracy()).thenReturn(true)
            `when`(accuracy).thenReturn(10.0f)
        }

        // 1st tick outside -> returns null because dwell timer just started (PENDING_EXIT)
        val initialEvent = evaluator.evaluate(outsideLoc, zone)
        assertNull("First tick outside must start dwell timer without immediate breach", initialEvent)

        // Wait for 1.1s (exceeding 1s test threshold)
        Thread.sleep(1100)

        // 2nd tick outside -> confirmed breach!
        val confirmedEvent = evaluator.evaluate(outsideLoc, zone)
        assertNotNull("Second tick after dwell duration must confirm breach", confirmedEvent)
        assertTrue("Event should be BreachExit", confirmedEvent is GeofenceTransitionEvent.BreachExit)
    }

    @Test
    fun testInaccurateGpsFixDiscarded() {
        val zone = GeofenceZone(
            id = "geo-acc-test",
            name = "Test Zone",
            zoneType = "CIRCULAR",
            centerLatitude = 28.500000,
            centerLongitude = 77.500000,
            radiusMeters = 100.0
        )

        val noisyLoc = mock(Location::class.java).apply {
            `when`(latitude).thenReturn(28.900000)
            `when`(longitude).thenReturn(77.900000)
            `when`(hasAccuracy()).thenReturn(true)
            `when`(accuracy).thenReturn(65.0f) // > 35m threshold
        }

        val result = evaluator.evaluate(noisyLoc, zone)
        assertNull("Inaccurate GPS fixes > 35m must be silently discarded", result)
    }
}
