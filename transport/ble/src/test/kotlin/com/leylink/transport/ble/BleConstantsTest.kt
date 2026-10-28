package com.leylink.transport.ble

import com.leylink.core.model.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleConstantsTest {
    private val id = NodeId(ByteArray(8) { (it + 1).toByte() })

    @Test
    fun `advert payload round trips with beacon flag and digest`() {
        val payload = BleConstants.advertPayload(id, beacon = true, digest = 0xBEEF)
        assertEquals(BleConstants.ADVERT_SIZE, payload.size)
        assertTrue(payload.size <= 24) // scan response budget: 31 bytes minus AD structure overhead

        val advert = BleConstants.parseAdvertPayload(payload)!!
        assertEquals(id, advert.nodeId)
        assertTrue(advert.beacon)
        assertEquals(0xBEEF, advert.digest)
    }

    @Test
    fun `beacon defaults to off`() {
        val advert = BleConstants.parseAdvertPayload(BleConstants.advertPayload(id))!!
        assertFalse(advert.beacon)
        assertEquals(0, advert.digest)
    }

    @Test
    fun `wrong version or length is ignored`() {
        val good = BleConstants.advertPayload(id)
        assertNull(BleConstants.parseAdvertPayload(good.copyOf(good.size - 1)))
        assertNull(BleConstants.parseAdvertPayload(good.copyOf().also { it[0] = 9 }))
        assertNull(BleConstants.parseAdvertPayload(ByteArray(0)))
    }
}
