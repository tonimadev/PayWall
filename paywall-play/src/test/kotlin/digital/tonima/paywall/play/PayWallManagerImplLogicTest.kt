package digital.tonima.paywall.play

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.ProductDetails
import digital.tonima.paywall.core.PayWallConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Covers the pure decision logic inside [PayWallManagerImpl] in isolation, without
 * exercising the BillingClient connection state machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PayWallManagerImplLogicTest {

    private lateinit var manager: PayWallManagerImpl

    @Before
    fun setUp() {
        val context = mockk<Context>(relaxed = true)
        val billingClient = mockk<BillingClient>(relaxed = true)
        manager = PayWallManagerImpl(
            context = context,
            config = PayWallConfig(),
            mainDispatcher = UnconfinedTestDispatcher(),
            billingClientFactory = { _, _ -> billingClient }
        )
    }

    // ---- retryDelayFor: exponential backoff, capped ----

    @Test
    fun `retryDelayFor doubles the delay for each attempt`() {
        assertEquals(1_000L, manager.retryDelayFor(0))
        assertEquals(2_000L, manager.retryDelayFor(1))
        assertEquals(4_000L, manager.retryDelayFor(2))
        assertEquals(8_000L, manager.retryDelayFor(3))
    }

    @Test
    fun `retryDelayFor is capped at the max delay`() {
        assertEquals(30_000L, manager.retryDelayFor(10))
        assertEquals(30_000L, manager.retryDelayFor(30))
    }

    // ---- isRecoverable ----

    @Test
    fun `unrecoverable response codes are not recoverable`() {
        assertEquals(false, manager.isRecoverable(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE))
        assertEquals(false, manager.isRecoverable(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED))
        assertEquals(false, manager.isRecoverable(BillingClient.BillingResponseCode.DEVELOPER_ERROR))
        assertEquals(false, manager.isRecoverable(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE))
    }

    @Test
    fun `transient response codes are recoverable`() {
        assertEquals(true, manager.isRecoverable(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED))
        assertEquals(true, manager.isRecoverable(BillingClient.BillingResponseCode.SERVICE_TIMEOUT))
        assertEquals(true, manager.isRecoverable(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE))
        assertEquals(true, manager.isRecoverable(BillingClient.BillingResponseCode.NETWORK_ERROR))
        assertEquals(true, manager.isRecoverable(BillingClient.BillingResponseCode.ERROR))
    }

    // ---- computeOwnedProductIds ----

    @Test
    fun `computeOwnedProductIds is empty when there are no purchases`() {
        assertEquals(emptySet<String>(), manager.computeOwnedProductIds(emptyList()))
    }

    @Test
    fun `computeOwnedProductIds only counts PURCHASED purchases`() {
        val purchased = purchaseMock(products = listOf("pro"), state = Purchase.PurchaseState.PURCHASED)
        val pending = purchaseMock(products = listOf("addon"), state = Purchase.PurchaseState.PENDING)

        val result = manager.computeOwnedProductIds(listOf(purchased, pending))

        assertEquals(setOf("pro"), result)
    }

    @Test
    fun `computeOwnedProductIds unions products across multiple purchases`() {
        val bundle = purchaseMock(products = listOf("pro", "extra_slots"), state = Purchase.PurchaseState.PURCHASED)
        val subscription = purchaseMock(products = listOf("monthly"), state = Purchase.PurchaseState.PURCHASED)

        val result = manager.computeOwnedProductIds(listOf(bundle, subscription))

        assertEquals(setOf("pro", "extra_slots", "monthly"), result)
    }

    // ---- resolveOfferToken ----

    @Test
    fun `resolveOfferToken with no requested basePlanId takes the first offer`() {
        val offerA = subscriptionOfferMock(basePlanId = "monthly", offerToken = "token-a")
        val offerB = subscriptionOfferMock(basePlanId = "yearly", offerToken = "token-b")

        val result = manager.resolveOfferToken(listOf(offerA, offerB), requestedBasePlanId = null)

        assertEquals("token-a", result)
    }

    @Test
    fun `resolveOfferToken returns the token for a matching basePlanId`() {
        val offerA = subscriptionOfferMock(basePlanId = "monthly", offerToken = "token-a")
        val offerB = subscriptionOfferMock(basePlanId = "yearly", offerToken = "token-b")

        val result = manager.resolveOfferToken(listOf(offerA, offerB), requestedBasePlanId = "yearly")

        assertEquals("token-b", result)
    }

    @Test
    fun `resolveOfferToken returns null instead of substituting a different plan when basePlanId is not found`() {
        val offerA = subscriptionOfferMock(basePlanId = "monthly", offerToken = "token-a")

        val result = manager.resolveOfferToken(listOf(offerA), requestedBasePlanId = "yearly")

        assertNull(result)
    }

    @Test
    fun `resolveOfferToken returns null when there are no offers at all`() {
        assertNull(manager.resolveOfferToken(emptyList(), requestedBasePlanId = null))
        assertNull(manager.resolveOfferToken(emptyList(), requestedBasePlanId = "yearly"))
    }

    private fun purchaseMock(products: List<String>, state: Int): Purchase {
        val purchase = mockk<Purchase>()
        every { purchase.products } returns products
        every { purchase.purchaseState } returns state
        return purchase
    }

    private fun subscriptionOfferMock(basePlanId: String, offerToken: String): ProductDetails.SubscriptionOfferDetails {
        val offer = mockk<ProductDetails.SubscriptionOfferDetails>()
        every { offer.basePlanId } returns basePlanId
        every { offer.offerToken } returns offerToken
        return offer
    }
}
