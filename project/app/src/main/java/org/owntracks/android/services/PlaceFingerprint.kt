package org.owntracks.android.services

/**
 * The cells and Wi-Fi access points seen around the last accurate location. When the device reports
 * a cell or a Wi-Fi scan that doesn't belong to this place, it has probably moved, even before any
 * low-power location says so.
 *
 * A device that doesn't move still switches between a few cells, so the place collects every cell
 * and access point seen at accurate locations within [radiusMeters] of where it started.
 */
class PlaceFingerprint(
    private val radiusMeters: Double = 250.0,
    private val minScanOverlap: Double = 0.3,
) {
  private var anchor: Pair<Double, Double>? = null
  private val cells = mutableSetOf<String>()
  private val accessPoints = mutableSetOf<String>()

  fun onAccurateLocation(
      latitude: Double,
      longitude: Double,
      cells: Set<String>,
      accessPoints: Set<String>,
  ) {
    val current = anchor
    if (
        current == null ||
            distanceMeters(current.first, current.second, latitude, longitude) > radiusMeters
    ) {
      anchor = latitude to longitude
      this.cells.clear()
      this.accessPoints.clear()
    }
    if (this.cells.size + cells.size <= MAX_ENTRIES) this.cells += cells
    if (this.accessPoints.size + accessPoints.size <= MAX_ENTRIES) this.accessPoints += accessPoints
  }

  fun isNewCell(cell: String): Boolean = cells.isNotEmpty() && cell !in cells

  /** A scan that shares too few access points with this place. Small scans say too little. */
  fun isNewScan(scan: Set<String>): Boolean {
    if (accessPoints.isEmpty() || scan.size < MIN_SCAN_SIZE) {
      return false
    }
    return scan.count { it in accessPoints }.toDouble() / scan.size < minScanOverlap
  }

  companion object {
    private const val MIN_SCAN_SIZE = 3
    private const val MAX_ENTRIES = 512
  }
}
