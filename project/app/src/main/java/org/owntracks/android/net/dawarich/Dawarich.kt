package org.owntracks.android.net.dawarich

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.owntracks.android.model.BatteryStatus
import org.owntracks.android.model.messages.MessageLocation

/**
 * Maps OwnTracks data to the Dawarich points API (`POST /api/v1/points`), which takes the GeoJSON
 * features the Overland app sends.
 */
object Dawarich {
  const val POINTS_PATH = "api/v1/points"

  /** The most points one request carries. The official app uses the same batch size. */
  const val BATCH_SIZE = 100

  /**
   * A location as a GeoJSON feature. Fields that OwnTracks reports as 0 when unknown are left out.
   */
  fun toFeature(message: MessageLocation, deviceId: String): JsonObject = buildJsonObject {
    put("type", "Feature")
    putJsonObject("geometry") {
      put("type", "Point")
      putJsonArray("coordinates") {
        add(message.longitude)
        add(message.latitude)
      }
    }
    putJsonObject("properties") {
      put("timestamp", Instant.ofEpochSecond(message.timestamp).toString())
      put("horizontal_accuracy", message.accuracy)
      if (message.verticalAccuracy > 0) put("vertical_accuracy", message.verticalAccuracy)
      if (message.altitude != 0) put("altitude", message.altitude)
      // OwnTracks carries km/h, Dawarich expects m/s.
      if (message.velocity > 0) {
        put("speed", message.velocity / 3.6)
        put("course", message.bearing)
      }
      message.battery?.let { put("battery_level", it / 100.0) }
      message.batteryStatus?.let { put("battery_state", batteryState(it)) }
      message.ssid?.let { put("wifi", it) }
      if (deviceId.isNotEmpty()) put("device_id", deviceId)
      message.motionActivities?.let { activities ->
        putJsonArray("motion") { activities.forEach { add(it) } }
      }
    }
  }

  /** The request body for a batch of features stored by [toFeature]. */
  fun batchBody(features: List<String>): String =
      features.joinToString(separator = ",", prefix = "{\"locations\":[", postfix = "]}")

  private fun batteryState(status: BatteryStatus) =
      when (status) {
        BatteryStatus.UNPLUGGED -> "unplugged"
        BatteryStatus.CHARGING -> "charging"
        BatteryStatus.FULL -> "full"
        BatteryStatus.UNKNOWN -> "unknown"
      }

  enum class Outcome {
    /** The points arrived. Drop them. */
    SENT,
    /**
     * The server can't take these points, and never will. Drop them so they don't block the rest.
     */
    REJECTED,
    /** The API key or the URL is wrong. Keep the points until the settings change. */
    UNAUTHORIZED,
    /** A temporary problem on the server or the network. Try again later. */
    RETRY,
  }

  fun outcome(httpCode: Int): Outcome =
      when (httpCode) {
        in 200..299 -> Outcome.SENT
        401,
        403,
        404 -> Outcome.UNAUTHORIZED
        408,
        429 -> Outcome.RETRY
        in 400..499 -> Outcome.REJECTED
        else -> Outcome.RETRY
      }

  data class Connection(val serverUrl: String, val apiKey: String)

  /**
   * Reads the QR code that Dawarich shows on Account -> API access, a JSON object with `server_url`
   * and `api_key`. Returns null for anything else.
   */
  fun parseQrCode(contents: String): Connection? = runCatching {
    val json = Json.parseToJsonElement(contents).jsonObject
    val serverUrl = json["server_url"]?.jsonPrimitive?.contentOrNull?.trim()
    val apiKey = json["api_key"]?.jsonPrimitive?.contentOrNull?.trim()
    if (
        serverUrl.isNullOrEmpty() ||
            apiKey.isNullOrEmpty() ||
            !(serverUrl.startsWith("https://") || serverUrl.startsWith("http://"))
    ) {
      null
    } else {
      Connection(serverUrl, apiKey)
    }
  }
      .getOrNull()

  /** The points endpoint under the configured server URL, which may end with a slash or not. */
  fun pointsUrl(serverUrl: String): String = "${serverUrl.trim().trimEnd('/')}/$POINTS_PATH"
}
