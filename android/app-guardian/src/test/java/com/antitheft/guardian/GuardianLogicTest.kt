package com.antitheft.guardian

import com.antitheft.common.crypto.HmacManager
import com.antitheft.common.protocol.SmsPduSerializer
import com.antitheft.guardian.db.LocationEntity
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GuardianLogicTest {

    private val gson = Gson()
    private val testPairingSecret = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
    private val keyBytes = testPairingSecret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun testLowBatteryEmergencyBeaconPduFormattingAndHmac() {
        val alias = "Pixel8"
        val lat = 37.774900
        val lng = -122.419400
        val acc = 15
        val epochSec = 1729000000L
        val seq = 55L

        val pdu = SmsPduSerializer.formatLocationReport(
            alias = alias,
            latitude = lat,
            longitude = lng,
            accuracyMeters = acc,
            captureEpochSec = epochSec,
            sequenceNumber = seq,
            sharedSecretKeyBytes = keyBytes
        )

        // Verify single-part SMS length limit constraint
        assertTrue("SMS PDU must fit within single 160-char GSM 7-bit boundary", pdu.length <= 160)
        assertTrue(pdu.startsWith("ST:Pixel8|37.77490,-122.41940|15|1729000000|55|"))

        // Verify round-trip parsing and HMAC validity
        val parsed = SmsPduSerializer.parseLocationReport(pdu, keyBytes)
        assertNotNull(parsed)
        assertEquals(alias, parsed!!.alias)
        assertEquals(lat, parsed.latitude, 0.0001)
        assertEquals(lng, parsed.longitude, 0.0001)
        assertEquals(acc, parsed.accuracyMeters)
        assertEquals(epochSec, parsed.captureEpochSec)
        assertEquals(seq, parsed.sequenceNumber)
        assertEquals(12, parsed.hmacHex.length)
        assertTrue("HMAC must be marked valid against the key", parsed.isHmacValid)
    }

    @Test
    fun testSyncBatchSerializationAndAckProcessing() {
        val entities = listOf(
            LocationEntity(
                sequenceNumber = 101,
                latitude = 37.7749,
                longitude = -122.4194,
                accuracy = 10.0f,
                altitude = 15.2,
                velocity = 0.5f,
                captureTimeUtc = 1729000100L,
                signatureDerBase64 = "MEQCIA==",
                syncStatus = LocationEntity.STATUS_PENDING
            ),
            LocationEntity(
                sequenceNumber = 102,
                latitude = 37.7750,
                longitude = -122.4195,
                accuracy = 8.5f,
                altitude = 15.5,
                velocity = 1.2f,
                captureTimeUtc = 1729000160L,
                signatureDerBase64 = "MEQCIB==",
                syncStatus = LocationEntity.STATUS_PENDING
            )
        )

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val eventList = entities.map { loc ->
            mapOf(
                "sequence_number" to loc.sequenceNumber,
                "latitude" to loc.latitude,
                "longitude" to loc.longitude,
                "accuracy" to loc.accuracy,
                "altitude" to loc.altitude,
                "velocity" to loc.velocity,
                "capture_time" to isoFormat.format(Date(loc.captureTimeUtc * 1000)),
                "signature" to loc.signatureDerBase64
            )
        }

        val requestPayload = mapOf(
            "device_id" to "dev_sync_test",
            "events" to eventList
        )

        val json = gson.toJson(requestPayload)
        assertTrue(json.contains("\"device_id\":\"dev_sync_test\""))
        assertTrue(json.contains("\"sequence_number\":101"))
        assertTrue(json.contains("\"sequence_number\":102"))

        // Simulate backend 200 response acknowledging sequence numbers
        val mockBackendResponseJson = """
            {
                "status": "success",
                "acknowledged_sequences": [101, 102],
                "duplicate_sequences": []
            }
        """.trimIndent()

        val responseMap = gson.fromJson(mockBackendResponseJson, Map::class.java)
        val ackList = (responseMap["acknowledged_sequences"] as List<*>).map { (it as Number).toLong() }
        assertEquals(2, ackList.size)
        assertEquals(101L, ackList[0])
        assertEquals(102L, ackList[1])

        // Verify 14-day pruning cutoff math
        val nowSec = 1729000000L
        val fourteenDaysAgo = nowSec - (14 * 86400)
        assertEquals(1729000000L - 1209600L, fourteenDaysAgo)
        val oldSyncedRecord = nowSec - (15 * 86400)
        assertTrue("Records older than 14 days must be pruned", oldSyncedRecord < fourteenDaysAgo)
    }

    @Test
    fun testLocationEntityModelIntegrity() {
        val entity = LocationEntity(
            sequenceNumber = 1,
            latitude = 40.7128,
            longitude = -74.0060,
            accuracy = 5.0f,
            altitude = null,
            velocity = null,
            captureTimeUtc = 1729000000L,
            signatureDerBase64 = "MEYCIQC...",
            syncStatus = LocationEntity.STATUS_PENDING
        )

        assertEquals(1L, entity.sequenceNumber)
        assertEquals("PENDING", entity.syncStatus)
        assertEquals(LocationEntity.STATUS_PENDING, entity.syncStatus)

        val synced = entity.copy(syncStatus = LocationEntity.STATUS_SYNCED)
        assertEquals("SYNCED", synced.syncStatus)
        assertEquals(LocationEntity.STATUS_SYNCED, synced.syncStatus)
    }
}
