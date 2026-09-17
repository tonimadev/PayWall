package digital.tonima.paywall.play

import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryProductDetailsResult
import digital.tonima.paywall.core.PayWallConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PayWallManagerImplQueryTest {

    private val context = mockk<Context>(relaxed = true)
    private val billingClient = mockk<BillingClient>(relaxed = true)
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        // connect() sees an already-connected client and calls refreshData() synchronously,
        // without going through startConnection()/onBillingSetupFinished at all.
        every { billingClient.connectionState } returns BillingClient.ConnectionState.CONNECTED
    }

    private fun okResult(): BillingResult =
        BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build()

    private fun failResult(code: Int): BillingResult =
        BillingResult.newBuilder().setResponseCode(code).setDebugMessage("boom").build()

    private fun purchase(
        products: List<String>,
        state: Int = Purchase.PurchaseState.PURCHASED,
        acknowledged: Boolean = true,
        token: String = "token-${products.joinToString()}"
    ): Purchase {
        val purchase = mockk<Purchase>(relaxed = true)
        every { purchase.products } returns products
        every { purchase.purchaseState } returns state
        every { purchase.isAcknowledged } returns acknowledged
        every { purchase.purchaseToken } returns token
        return purchase
    }

    private fun newManager(config: PayWallConfig): PayWallManagerImpl =
        PayWallManagerImpl(
            context = context,
            config = config,
            mainDispatcher = dispatcher,
            billingClientFactory = { _, _ -> billingClient }
        )

    // ---- queryPurchases / consolidation ----

    @Test
    fun `owned products combine PURCHASED in-app and subscription purchases`() {
        val inAppPurchase = purchase(products = listOf("pro"))
        val subsPurchase = purchase(products = listOf("monthly"))
        // queryPurchases() launches one async call per product type, and with the
        // UnconfinedTestDispatcher they resolve synchronously in launch order: INAPP then SUBS.
        var callCount = 0
        every { billingClient.queryPurchasesAsync(any(), any()) } answers {
            val listener = secondArg<PurchasesResponseListener>()
            callCount++
            val result = if (callCount == 1) listOf(inAppPurchase) else listOf(subsPurchase)
            listener.onQueryPurchasesResponse(okResult(), result)
        }

        val manager = newManager(PayWallConfig())
        manager.connect()

        assertEquals(setOf("pro", "monthly"), manager.ownedProductIds.value)
    }

    @Test
    fun `pending purchases are not counted as owned`() {
        every { billingClient.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(
                okResult(),
                listOf(purchase(products = listOf("pro"), state = Purchase.PurchaseState.PENDING))
            )
        }

        val manager = newManager(PayWallConfig())
        manager.connect()

        assertTrue(manager.ownedProductIds.value.isEmpty())
    }

    @Test
    fun `a failed purchases query keeps the last known-good owned set instead of clearing it`() {
        var callCount = 0
        every { billingClient.queryPurchasesAsync(any(), any()) } answers {
            val listener = secondArg<PurchasesResponseListener>()
            callCount++
            if (callCount <= 2) {
                // First refresh (one call per product type): both succeed with an owned purchase.
                listener.onQueryPurchasesResponse(okResult(), listOf(purchase(products = listOf("pro"))))
            } else {
                // Second refresh: simulate a transient failure.
                listener.onQueryPurchasesResponse(failResult(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE), emptyList())
            }
        }

        val manager = newManager(PayWallConfig())
        manager.connect()
        assertEquals(setOf("pro"), manager.ownedProductIds.value)

        manager.refresh()

        assertEquals(setOf("pro"), manager.ownedProductIds.value)
    }

    @Test
    fun `autoAcknowledge acknowledges unacknowledged purchases but leaves acknowledged ones alone`() {
        val unacknowledged = purchase(products = listOf("pro"), acknowledged = false, token = "unack-token")
        val alreadyAcknowledged = purchase(products = listOf("monthly"), acknowledged = true, token = "ack-token")
        var callCount = 0
        every { billingClient.queryPurchasesAsync(any(), any()) } answers {
            val listener = secondArg<PurchasesResponseListener>()
            callCount++
            val result = if (callCount == 1) listOf(unacknowledged) else listOf(alreadyAcknowledged)
            listener.onQueryPurchasesResponse(okResult(), result)
        }
        val ackSlot = slot<AcknowledgePurchaseParams>()
        every { billingClient.acknowledgePurchase(capture(ackSlot), any()) } answers {
            secondArg<AcknowledgePurchaseResponseListener>().onAcknowledgePurchaseResponse(okResult())
        }

        val manager = newManager(PayWallConfig(autoAcknowledge = true))
        manager.connect()

        verify(exactly = 1) { billingClient.acknowledgePurchase(any(), any()) }
        assertEquals("unack-token", ackSlot.captured.purchaseToken)
    }

    @Test
    fun `autoAcknowledge disabled never acknowledges purchases`() {
        every { billingClient.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(
                okResult(),
                listOf(purchase(products = listOf("pro"), acknowledged = false))
            )
        }

        val manager = newManager(PayWallConfig(autoAcknowledge = false))
        manager.connect()

        verify(exactly = 0) { billingClient.acknowledgePurchase(any(), any()) }
    }

    // ---- queryProductDetails ----

    @Test
    fun `an empty product configuration becomes ready without querying product details`() {
        every { billingClient.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(okResult(), emptyList())
        }

        val manager = newManager(PayWallConfig())
        manager.connect()

        assertTrue(manager.isReady.value)
        verify(exactly = 0) { billingClient.queryProductDetailsAsync(any(), any()) }
    }

    @Test
    fun `product details failure marks not ready and schedules a retry that can succeed`() {
        every { billingClient.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(okResult(), emptyList())
        }
        val details = mockk<ProductDetails>(relaxed = true)
        every { details.productId } returns "pro"
        var callCount = 0
        every { billingClient.queryProductDetailsAsync(any(), any()) } answers {
            val listener = secondArg<ProductDetailsResponseListener>()
            callCount++
            if (callCount == 1) {
                listener.onProductDetailsResponse(
                    failResult(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE),
                    QueryProductDetailsResult.create(emptyList(), emptyList())
                )
            } else {
                listener.onProductDetailsResponse(okResult(), QueryProductDetailsResult.create(listOf(details), emptyList()))
            }
        }

        val manager = newManager(PayWallConfig(inAppProductIds = setOf("pro")))
        manager.connect()

        assertFalse(manager.isReady.value)

        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(manager.isReady.value)
    }

    @Test
    fun `product details give up after the maximum retries for an unrecoverable error`() {
        every { billingClient.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(okResult(), emptyList())
        }
        every { billingClient.queryProductDetailsAsync(any(), any()) } answers {
            secondArg<ProductDetailsResponseListener>().onProductDetailsResponse(
                failResult(BillingClient.BillingResponseCode.DEVELOPER_ERROR),
                QueryProductDetailsResult.create(emptyList(), emptyList())
            )
        }

        val manager = newManager(PayWallConfig(inAppProductIds = setOf("pro")))
        manager.connect()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(manager.isReady.value)
        verify(exactly = 1) { billingClient.queryProductDetailsAsync(any(), any()) }
    }

    // ---- disconnect race fix ----

    @Test
    fun `disconnect cancels an in-flight product details query so a late response cannot re-enable isReady`() {
        every { billingClient.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(okResult(), emptyList())
        }
        val detailsListener = slot<ProductDetailsResponseListener>()
        every { billingClient.queryProductDetailsAsync(any(), capture(detailsListener)) } returns Unit

        val manager = newManager(PayWallConfig(inAppProductIds = setOf("pro")))
        manager.connect()

        // The product details response has not arrived yet.
        assertFalse(manager.isReady.value)

        manager.disconnect()

        // The response now arrives late, after disconnect() already tore things down.
        val details = mockk<ProductDetails>(relaxed = true)
        every { details.productId } returns "pro"
        detailsListener.captured.onProductDetailsResponse(okResult(), QueryProductDetailsResult.create(listOf(details), emptyList()))

        assertFalse("A late async response must not resurrect isReady after disconnect()", manager.isReady.value)
    }
}
