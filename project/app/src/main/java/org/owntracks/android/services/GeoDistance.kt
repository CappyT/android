package org.owntracks.android.services

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

/** The great-circle distance between two points, in meters. */
fun distanceMeters(
    fromLatitude: Double,
    fromLongitude: Double,
    toLatitude: Double,
    toLongitude: Double,
): Double {
  val dLat = Math.toRadians(toLatitude - fromLatitude)
  val dLon = Math.toRadians(toLongitude - fromLongitude)
  val a =
      sin(dLat / 2).pow(2) +
          cos(Math.toRadians(fromLatitude)) * cos(Math.toRadians(toLatitude)) * sin(dLon / 2).pow(2)
  return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
}
