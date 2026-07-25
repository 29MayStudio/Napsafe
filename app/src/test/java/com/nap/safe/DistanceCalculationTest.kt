package com.nap.safe

import org.junit.Assert.assertEquals
import org.junit.Test
import android.location.Location

class DistanceCalculationTest {

    @Test
    fun testHaversineDistanceConcept() {
        // Concept distance calculation validation
        val lat1 = 40.7128
        val lon1 = -74.0060
        val lat2 = 34.0522
        val lon2 = -118.2437

        // Earth's radius in kilometers
        val r = 6371.0

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)

        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        val distance = r * c

        // Distance between NY and LA is approx 3936 km
        assertEquals(3936.0, distance, 50.0)
    }
}
