package digital.tonima.paywall.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayWallConfigTest {

    @Test
    fun `default config has no products and safe defaults`() {
        val config = PayWallConfig()

        assertTrue(config.inAppProductIds.isEmpty())
        assertTrue(config.subscriptionProductIds.isEmpty())
        assertTrue(config.autoAcknowledge)
        assertFalse(config.debugMode)
    }

    @Test
    fun `config preserves provided product id sets`() {
        val config = PayWallConfig(
            inAppProductIds = setOf("remove_ads"),
            subscriptionProductIds = setOf("monthly", "yearly"),
            autoAcknowledge = false,
            debugMode = true
        )

        assertEquals(setOf("remove_ads"), config.inAppProductIds)
        assertEquals(setOf("monthly", "yearly"), config.subscriptionProductIds)
        assertFalse(config.autoAcknowledge)
        assertTrue(config.debugMode)
    }
}
