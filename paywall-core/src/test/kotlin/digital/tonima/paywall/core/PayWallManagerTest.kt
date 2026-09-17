package digital.tonima.paywall.core

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the default [PayWallManager.isPurchased] implementation, which every
 * platform-specific manager (e.g. PayWallManagerImpl) inherits verbatim.
 */
private class FakePayWallManager(initialOwned: Set<String> = emptySet()) : PayWallManager {
    private val owned = MutableStateFlow(initialOwned)
    override val ownedProductIds: StateFlow<Set<String>> = owned.asStateFlow()
    override val isReady: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun launchPurchase(activity: Activity, productId: String) = Unit
    override fun launchSubscription(activity: Activity, productId: String, basePlanId: String?) = Unit
    override fun refresh() = Unit

    fun setOwned(ids: Set<String>) {
        owned.value = ids
    }
}

class PayWallManagerTest {

    @Test
    fun `isPurchased returns false when product is not owned`() {
        val manager = FakePayWallManager(initialOwned = emptySet())

        assertFalse(manager.isPurchased("premium"))
    }

    @Test
    fun `isPurchased returns true when product is in owned set`() {
        val manager = FakePayWallManager(initialOwned = setOf("premium", "monthly_sub"))

        assertTrue(manager.isPurchased("premium"))
        assertTrue(manager.isPurchased("monthly_sub"))
        assertFalse(manager.isPurchased("other"))
    }

    @Test
    fun `isPurchased reflects live updates to ownedProductIds`() {
        val manager = FakePayWallManager()

        assertFalse(manager.isPurchased("premium"))

        manager.setOwned(setOf("premium"))

        assertTrue(manager.isPurchased("premium"))
    }
}
