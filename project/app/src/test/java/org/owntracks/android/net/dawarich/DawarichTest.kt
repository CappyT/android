package org.owntracks.android.net.dawarich

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.owntracks.android.model.BatteryStatus
import org.owntracks.android.model.messages.MessageLocation

class DawarichTest {
  private fun location() =
      MessageLocation().apply {
        latitude = 45.6006
        longitude = 9.9699
        accuracy = 12
        verticalAccuracy = 3
        altitude = 241
        velocity = 36
        bearing = 90
        timestamp = 1_790_000_000
        battery = 44
        batteryStatus = BatteryStatus.CHARGING
        ssid = "home"
        motionActivities = listOf("walking")
      }

  private fun JsonObject.properties() = getValue("properties").jsonObject

  @Test
  fun `A location becomes a GeoJSON point with longitude first`() {
    val feature = Dawarich.toFeature(location(), "blazer")
    assertEquals("Feature", feature.getValue("type").jsonPrimitive.content)
    val coordinates = feature.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
    assertEquals(9.9699, coordinates[0].jsonPrimitive.double, 0.0)
    assertEquals(45.6006, coordinates[1].jsonPrimitive.double, 0.0)
  }

  @Test
  fun `The properties carry the fix time and the OwnTracks fields in Dawarich units`() {
    val properties = Dawarich.toFeature(location(), "blazer").properties()
    assertEquals("2026-09-21T14:13:20Z", properties.getValue("timestamp").jsonPrimitive.content)
    assertEquals(12, properties.getValue("horizontal_accuracy").jsonPrimitive.int)
    assertEquals(3, properties.getValue("vertical_accuracy").jsonPrimitive.int)
    assertEquals(241, properties.getValue("altitude").jsonPrimitive.int)
    assertEquals(10.0, properties.getValue("speed").jsonPrimitive.double, 1e-9)
    assertEquals(90, properties.getValue("course").jsonPrimitive.int)
    assertEquals(0.44, properties.getValue("battery_level").jsonPrimitive.double, 1e-9)
    assertEquals("charging", properties.getValue("battery_state").jsonPrimitive.content)
    assertEquals("home", properties.getValue("wifi").jsonPrimitive.content)
    assertEquals("blazer", properties.getValue("device_id").jsonPrimitive.content)
    assertEquals("walking", properties.getValue("motion").jsonArray[0].jsonPrimitive.content)
  }

  @Test
  fun `Fields OwnTracks reports as zero when unknown are left out`() {
    val properties =
        Dawarich.toFeature(
                MessageLocation().apply {
                  latitude = 1.0
                  longitude = 2.0
                  accuracy = 30
                  timestamp = 1_790_000_000
                },
                "",
            )
            .properties()
    listOf(
            "vertical_accuracy",
            "altitude",
            "speed",
            "course",
            "battery_level",
            "battery_state",
            "wifi",
            "device_id",
            "motion",
        )
        .forEach { assertFalse(it, properties.containsKey(it)) }
  }

  @Test
  fun `A batch wraps the stored features in a locations array`() {
    val features = listOf(Dawarich.toFeature(location(), "a"), Dawarich.toFeature(location(), "b"))
    val body = Json.parseToJsonElement(Dawarich.batchBody(features.map { it.toString() }))
    assertEquals(features, body.jsonObject.getValue("locations").jsonArray.toList())
  }

  @Test
  fun `The points URL works with and without a trailing slash`() {
    assertEquals(
        "https://places.example/api/v1/points",
        Dawarich.pointsUrl("https://places.example/"),
    )
    assertEquals(
        "https://places.example/dawarich/api/v1/points",
        Dawarich.pointsUrl(" https://places.example/dawarich "),
    )
  }

  @Test
  fun `The Dawarich QR code gives the server and the API key`() {
    assertEquals(
        Dawarich.Connection("https://places.example/", "abc123"),
        Dawarich.parseQrCode("""{"server_url":"https://places.example/","api_key":"abc123"}"""),
    )
  }

  @Test
  fun `Other QR codes are not a Dawarich connection`() {
    listOf(
            "https://places.example/",
            "otpauth://totp/Dawarich:me?secret=ABC",
            """{"server_url":"places.example","api_key":"abc"}""",
            """{"server_url":"https://places.example/"}""",
            """{"server_url":"https://places.example/","api_key":""}""",
        )
        .forEach { assertNull(it, Dawarich.parseQrCode(it)) }
  }

  @Test
  fun `Responses decide what happens to the batch`() {
    assertEquals(Dawarich.Outcome.SENT, Dawarich.outcome(200))
    assertEquals(Dawarich.Outcome.SENT, Dawarich.outcome(201))
    assertEquals(Dawarich.Outcome.UNAUTHORIZED, Dawarich.outcome(401))
    assertEquals(Dawarich.Outcome.UNAUTHORIZED, Dawarich.outcome(403))
    assertEquals(Dawarich.Outcome.UNAUTHORIZED, Dawarich.outcome(404))
    assertEquals(Dawarich.Outcome.REJECTED, Dawarich.outcome(422))
    assertEquals(Dawarich.Outcome.REJECTED, Dawarich.outcome(400))
    assertEquals(Dawarich.Outcome.RETRY, Dawarich.outcome(429))
    assertEquals(Dawarich.Outcome.RETRY, Dawarich.outcome(408))
    assertEquals(Dawarich.Outcome.RETRY, Dawarich.outcome(500))
    assertEquals(Dawarich.Outcome.RETRY, Dawarich.outcome(503))
    assertTrue(Dawarich.outcome(302) == Dawarich.Outcome.RETRY)
  }
}
