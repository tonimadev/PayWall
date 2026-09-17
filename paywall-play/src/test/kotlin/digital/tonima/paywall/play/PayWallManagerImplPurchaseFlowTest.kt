package digital.tonima.paywall.play

import android.app.Activity
import android.content.Context
import android.text.TextUtils
import android.widget.Toast
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.PurchasesResponseListener
import digital.tonima.paywall.core.PayWallConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PayWallManagerImplPurchaseFlowTest {

    private val context = mockk<Context>(relaxed = true)
    private val activity = mockk<Activity>(relaxed = true)
    private val billingClient = mockk<BillingClient>(relaxed = true)
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun stubTextUtils() {
        // The Play Billing library's own param builders call TextUtils.isEmpty internally;
        // give it real semantics instead of Android's unit-test stub, which throws.
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
    }

    @After
    fun unstubTextUtils() {
        unmockkStatic(TextUtils::class)
    }

    private fun okResult(): BillingResult =
        BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build()

    private fun subscriptionOffer(basePlanId: String, offerToken: String): ProductDetails.SubscriptionOfferDetails {
        val offer = mockk<ProductDetails.SubscriptionOfferDetails>()
        every { offer.basePlanId } returns basePlanId
        every { offer.offerToken } returns offerToken
        return offer
    }

    private fun productDetails(
        productId: String,
        offers: List<ProductDetails.SubscriptionOfferDetails>? = null
    ): ProductDetails {
        val details = mockk<ProductDetails>(relaxed = true)
        every { details.productId } returns productId
        every { details.subscriptionOfferDetails } returns offers
        return details
    }

    /** Builds a manager that is already ready, with [availableProducts] as its known catalog. */
    private fun readyManager(vararg availableProducts: ProductDetails): PayWallManagerImpl {
        every { billingClient.connectionState } returns BillingClient.ConnectionState.CONNECTED
        every { billingClient.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(okResult(), emptyList())
        }
        val config = PayWallConfig(
            inAppProductIds = availableProducts.filter { it.productId.startsWith("inapp") }.map { it.productId }.toSet(),
            subscriptionProductIds = availableProducts.filter { it.productId.startsWith("sub") }.map { it.productId }.toSet()
        )
        every { billingClient.queryProductDetailsAsync(any(), any()) } answers {
            secondArg<com.android.billingclient.api.ProductDetailsResponseListener>().onProductDetailsResponse(
                okResult(),
                com.android.billingclient.api.QueryProductDetailsResult.create(availableProducts.toList(), emptyList())
            )
        }
        val manager = PayWallManagerImpl(
            context = context,
            config = config,
            mainDispatcher = dispatcher,
            billingClientFactory = { _, _ -> billingClient }
        )
        manager.connect()
        check(manager.isReady.value) { "test setup failed to reach ready state" }
        return manager
    }

    @Test
    fun `launchPurchase does nothing when the SDK is not ready`() {
        every { billingClient.connectionState } returns BillingClient.ConnectionState.DISCONNECTED
        val manager = PayWallManagerImpl(
            context = context,
            config = PayWallConfig(),
            mainDispatcher = dispatcher,
            billingClientFactory = { _, _ -> billingClient }
        )

        manager.launchPurchase(activity, "inapp_pro")

        verify(exactly = 0) { billingClient.launchBillingFlow(any(), any()) }
    }

    @Test
    fun `launchPurchase shows a toast and does not launch when the product is unknown`() {
        mockkStatic(Toast::class)
        val toast = mockk<Toast>(relaxed = true)
        every { Toast.makeText(any(), any<CharSequence>(), any()) } returns toast

        val manager = readyManager(productDetails("inapp_pro"))

        manager.launchPurchase(activity, "inapp_missing")

        verify(exactly = 0) { billingClient.launchBillingFlow(any(), any()) }
        verify(exactly = 1) { toast.show() }
        unmockkStatic(Toast::class)
    }

    @Test
    fun `launchPurchase launches the billing flow for a known in-app product`() {
        val manager = readyManager(productDetails("inapp_pro"))

        manager.launchPurchase(activity, "inapp_pro")

        verify(exactly = 1) { billingClient.launchBillingFlow(activity, any()) }
    }

    @Test
    fun `launchSubscription with no basePlanId launches using the first available offer`() {
        // Exact offer-token selection is covered by PayWallManagerImplLogicTest#resolveOfferToken;
        // this only verifies the flow actually reaches launchBillingFlow when an offer exists.
        val details = productDetails(
            "sub_monthly",
            offers = listOf(subscriptionOffer("monthly", "token-monthly"), subscriptionOffer("yearly", "token-yearly"))
        )
        val manager = readyManager(details)

        manager.launchSubscription(activity, "sub_monthly", basePlanId = null)

        verify(exactly = 1) { billingClient.launchBillingFlow(activity, any()) }
    }

    @Test
    fun `launchSubscription with a matching basePlanId launches the billing flow`() {
        val details = productDetails(
            "sub_monthly",
            offers = listOf(subscriptionOffer("monthly", "token-monthly"), subscriptionOffer("yearly", "token-yearly"))
        )
        val manager = readyManager(details)

        manager.launchSubscription(activity, "sub_monthly", basePlanId = "yearly")

        verify(exactly = 1) { billingClient.launchBillingFlow(activity, any()) }
    }

    @Test
    fun `launchSubscription aborts instead of charging a different plan when basePlanId does not match any offer`() {
        val details = productDetails("sub_monthly", offers = listOf(subscriptionOffer("monthly", "token-monthly")))
        val manager = readyManager(details)

        manager.launchSubscription(activity, "sub_monthly", basePlanId = "does-not-exist")

        verify(exactly = 0) { billingClient.launchBillingFlow(any(), any()) }
    }

    @Test
    fun `launchSubscription does nothing when the SDK is not ready`() {
        every { billingClient.connectionState } returns BillingClient.ConnectionState.DISCONNECTED
        val manager = PayWallManagerImpl(
            context = context,
            config = PayWallConfig(),
            mainDispatcher = dispatcher,
            billingClientFactory = { _, _ -> billingClient }
        )

        manager.launchSubscription(activity, "sub_monthly")

        verify(exactly = 0) { billingClient.launchBillingFlow(any(), any()) }
    }
}
