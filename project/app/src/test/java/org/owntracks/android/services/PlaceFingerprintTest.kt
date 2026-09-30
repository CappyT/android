package org.owntracks.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceFingerprintTest {
  private val home = 45.6006 to 9.9700
  // About 110 m north of home.
  private val garden = 45.6016 to 9.9700
  // About 1.1 km north of home.
  private val town = 45.6106 to 9.9700
  private val homeAps = setOf("a", "b", "c", "d", "e")

  private fun PlaceFingerprint.at(
      place: Pair<Double, Double>,
      cells: Set<String> = emptySet(),
      aps: Set<String> = emptySet(),
  ) = onAccurateLocation(place.first, place.second, cells, aps)

  @Test
  fun `Nothing is new before the first accurate location`() {
    val fingerprint = PlaceFingerprint()
    assertFalse(fingerprint.isNewCell("1"))
    assertFalse(fingerprint.isNewScan(setOf("x", "y", "z")))
  }

  @Test
  fun `A cell seen at the place is not new`() {
    val fingerprint = PlaceFingerprint()
    fingerprint.at(home, cells = setOf("1"))
    assertFalse(fingerprint.isNewCell("1"))
    assertTrue(fingerprint.isNewCell("2"))
  }

  @Test
  fun `Cells seen near the place join it`() {
    val fingerprint = PlaceFingerprint()
    fingerprint.at(home, cells = setOf("1"))
    fingerprint.at(garden, cells = setOf("2"))
    assertFalse(fingerprint.isNewCell("1"))
    assertFalse(fingerprint.isNewCell("2"))
  }

  @Test
  fun `An accurate location far away starts a new place`() {
    val fingerprint = PlaceFingerprint()
    fingerprint.at(home, cells = setOf("1"), aps = homeAps)
    fingerprint.at(town, cells = setOf("7"))
    assertTrue(fingerprint.isNewCell("1"))
    assertFalse(fingerprint.isNewCell("7"))
    assertFalse(fingerprint.isNewScan(homeAps))
  }

  @Test
  fun `A scan that shares most access points with the place is not new`() {
    val fingerprint = PlaceFingerprint()
    fingerprint.at(home, aps = homeAps)
    assertFalse(fingerprint.isNewScan(setOf("a", "b", "c", "phone-hotspot")))
  }

  @Test
  fun `A scan that shares few access points with the place is new`() {
    val fingerprint = PlaceFingerprint()
    fingerprint.at(home, aps = homeAps)
    assertTrue(fingerprint.isNewScan(setOf("a", "x", "y", "z")))
  }

  @Test
  fun `A small scan is never new`() {
    val fingerprint = PlaceFingerprint()
    fingerprint.at(home, aps = homeAps)
    assertFalse(fingerprint.isNewScan(setOf("x", "y")))
  }
}
