package com.fourriders.next.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Samples are modelled on a real Grab Express "Route Details" screen. When the app
 * misreads a real screen, copy its raw text (hold the panel) and add it here.
 */
class OrderFormParserTest {
    private val pickupScreen = """
        Route Details
        Current net earnings: RM5.20
        2/2 stops to go
        1. Pick Up Item
        LOT CC.10D, LEVEL CONCOURSE, NU SENTRAL SHOPPING CENTRE, JALAN TUN SAMBATHAN, 50470 KUALA LUMPUR., Kuala Lumpur, Kuala Lumpur
        Sender's name:
        CHAGEE MY Nu Sentral
        Order ID
        IN-2-0E5FJISDEERJ6NXFSTT5
        Drop-off details
        Hide item details
        Item
        Address details
        -. AD0020
        Notes
        ORDER E-COMMERCE! Nombor diatas
    """.trimIndent()

    private val dropoffScreen = """
        Route Details
        2. Drop Off Item
        No 12 Jalan Bukit 3, Taman ABC, A-12-03, 47400 Petaling Jaya, Selangor
        Recipient's name:
        Ahmad Faiz
        Order ID
        IN-2-0E5FJISDEERJ6NXFSTT5
        Address details
        Tingkat 12
    """.trimIndent()

    @Test
    fun pickupScreen_fillsFareAndPickup_dropoffNeedsMoreInfo() {
        val form = OrderFormParser.parse(pickupScreen)
        assertEquals(5.20, form.fare!!, 0.001)
        assertEquals("IN-2-0E5FJISDEERJ6NXFSTT5", form.orderId)
        assertEquals("CHAGEE MY Nu Sentral", form.pickup.name)
        assertEquals(
            "NU SENTRAL SHOPPING CENTRE, JALAN TUN SAMBATHAN, 50470 KUALA LUMPUR, Kuala Lumpur",
            form.pickup.location
        )
        assertEquals("LOT CC.10D • LEVEL CONCOURSE", form.pickup.unit)
        assertEquals("AD0020", form.pickup.details)
        assertFalse(form.dropoff.seen)
    }

    @Test
    fun scrolling_addsDropoff_andKeepsEarlierInfo() {
        val first = OrderFormParser.parse(pickupScreen)
        val second = OrderFormParser.parse(dropoffScreen)
        val merged = OrderFormParser.merge(first, second)
        assertEquals(5.20, merged.fare!!, 0.001)
        assertEquals("CHAGEE MY Nu Sentral", merged.pickup.name)
        assertEquals("Ahmad Faiz", merged.dropoff.name)
        assertEquals("Jalan Bukit 3, Taman ABC, 47400 Petaling Jaya, Selangor", merged.dropoff.location)
        assertEquals("No 12 • A-12-03", merged.dropoff.unit)
    }

    @Test
    fun differentOrderId_startsFreshForm() {
        val first = OrderFormParser.parse(pickupScreen)
        val other = OrderFormParser.parse(
            "Current net earnings: RM9.80\n1. Pick Up Item\nUnit 5, Jalan Ampang, 50450 Kuala Lumpur\n" +
                "Sender's name: Kedai Ali\nOrder ID: IN-2-ZZZZZZZZZZZZZZZZZZZZ"
        )
        val merged = OrderFormParser.merge(first, other)
        assertEquals(9.80, merged.fare!!, 0.001)
        assertEquals("Kedai Ali", merged.pickup.name)
        assertEquals("Unit 5", merged.pickup.unit)
        assertFalse(merged.dropoff.seen)
    }

    @Test
    fun nonBreakingSpaceAndCurlyApostrophe_areHandled() {
        val text = pickupScreen.replace("RM5.20", "RM\u00A05.20").replace("Sender's", "Sender\u2019s")
        val form = OrderFormParser.parse(text)
        assertEquals(5.20, form.fare!!, 0.001)
        assertEquals("CHAGEE MY Nu Sentral", form.pickup.name)
    }

    @Test
    fun headingSplitAcrossTwoNodes_stillFindsPickup() {
        val form = OrderFormParser.parse(pickupScreen.replace("1. Pick Up Item", "1.\nPick Up Item"))
        assertEquals("CHAGEE MY Nu Sentral", form.pickup.name)
    }

    @Test
    fun unlabelledAmbiguousMoney_isNotGuessed() {
        val form = OrderFormParser.parse("Promo RM2.00\nBonus RM3.50")
        assertNull(form.fare)
        assertTrue(form.notes.isNotEmpty())
    }

    @Test
    fun unrelatedScreen_isEmpty_andDropoffLinkIsNotAHeading() {
        assertTrue(OrderFormParser.parse("Home\nSettings\nWallet").isEmpty)
        assertFalse(OrderFormParser.parse("Drop-off details").dropoff.seen)
    }
}
