package digital.tonima.paywall.play

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient.BillingResponseCode.OK
import com.android.billingclient.api.Purchase.PurchaseState.PURCHASED
import digital.tonima.paywall.core.PayWallConfig
import digital.tonima.paywall.core.PayWallLog
import digital.tonima.paywall.core.PayWallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class PayWallManagerImpl(
    private val context: Context,
    private val config: PayWallConfig
) : PayWallManager {

    init {
        PayWallLog.isDebugEnabled = config.debugMode
    }

    // Confines all SDK state mutation to the main thread, which is also the thread
    // BillingClient invokes every callback on - so no extra locking is needed.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _ownedProductIds = MutableStateFlow<Set<String>>(emptySet())
    override val ownedProductIds = _ownedProductIds.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    override val isReady = _isReady.asStateFlow()

    private val _productDetailsList = MutableStateFlow<List<ProductDetails>>(emptyList())
    val productDetailsList = _productDetailsList.asStateFlow()

    private var lastInAppPurchases = emptyList<Purchase>()
    private var lastSubsPurchases = emptyList<Purchase>()

    private var manuallyDisconnected = false
    private var connectionRetryAttempt = 0
    private var productDetailsRetryAttempt = 0
    private var reconnectJob: Job? = null
    private var productDetailsRetryJob: Job? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        PayWallLog.d("onPurchasesUpdated: ${billingResult.responseCode}, count: ${purchases?.size ?: 0}")
        if (billingResult.responseCode == OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
            queryPurchases()
        }
    }

    private var billingClient: BillingClient = buildBillingClient()

    private fun buildBillingClient(): BillingClient =
        BillingClient.newBuilder(context)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()

    override fun connect() {
        manuallyDisconnected = false
        reconnectJob?.cancel()

        when (billingClient.connectionState) {
            BillingClient.ConnectionState.CONNECTED -> {
                PayWallLog.d("Billing Client already connected.")
                refreshData()
                return
            }
            BillingClient.ConnectionState.CONNECTING -> {
                PayWallLog.d("Billing Client already connecting.")
                return
            }
            BillingClient.ConnectionState.CLOSED -> {
                // Per BillingClient's contract, a client that had endConnection() called on
                // it is permanently dead and must never be reused - build a fresh one.
                PayWallLog.d("Billing Client was closed; creating a new instance.")
                billingClient = buildBillingClient()
            }
            else -> Unit // DISCONNECTED - fall through and start a new connection below.
        }

        PayWallLog.d("Connecting to Billing Client...")
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == OK) {
                    PayWallLog.d("Billing client setup finished.")
                    connectionRetryAttempt = 0
                    refreshData()
                } else {
                    PayWallLog.e("Billing client setup failed: ${billingResult.debugMessage}")
                    scheduleReconnect(billingResult.responseCode)
                }
            }

            override fun onBillingServiceDisconnected() {
                // This fires on unexpected drops (Play Store update, low memory, etc.) -
                // not on an explicit disconnect() - so the existing client instance is
                // still safe to reuse via startConnection() again.
                PayWallLog.w("Billing service disconnected.")
                _isReady.value = false
                scheduleReconnect(null)
            }
        })
    }

    override fun disconnect() {
        PayWallLog.d("Disconnecting Billing Client...")
        manuallyDisconnected = true
        reconnectJob?.cancel()
        productDetailsRetryJob?.cancel()
        connectionRetryAttempt = 0
        productDetailsRetryAttempt = 0
        if (billingClient.connectionState != BillingClient.ConnectionState.CLOSED) {
            billingClient.endConnection()
        }
        _isReady.value = false
    }

    private fun scheduleReconnect(responseCode: Int?) {
        if (manuallyDisconnected) return
        if (responseCode != null && !isRecoverable(responseCode)) {
            PayWallLog.e("Billing setup failed with an unrecoverable error ($responseCode); not retrying.")
            return
        }
        if (connectionRetryAttempt >= MAX_RETRY_ATTEMPTS) {
            PayWallLog.e("Giving up billing reconnection after $connectionRetryAttempt attempts.")
            return
        }
        val delayMs = retryDelayFor(connectionRetryAttempt)
        connectionRetryAttempt++
        PayWallLog.d("Scheduling billing reconnect in ${delayMs}ms (attempt $connectionRetryAttempt).")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            connect()
        }
    }

    private fun refreshData() {
        queryPurchases()
        queryProductDetails()
    }

    private fun queryPurchases() {
        PayWallLog.d("Querying purchases...")
        scope.launch {
            // Query both product types concurrently but only publish the combined
            // result once BOTH have resolved, instead of publishing twice from two
            // independent callbacks - which used to make isProUser/isAiUser flicker
            // to "not owned" for a moment on every cold start, whichever query type
            // happened to resolve first.
            val inAppDeferred = async { queryPurchasesOfType(BillingClient.ProductType.INAPP) }
            val subsDeferred = async { queryPurchasesOfType(BillingClient.ProductType.SUBS) }
            // A query that fails keeps the last known-good list instead of being
            // treated as "user owns nothing", so a transient network error can't
            // erase a real purchase from the published state.
            inAppDeferred.await()?.let { lastInAppPurchases = it }
            subsDeferred.await()?.let { lastSubsPurchases = it }
            consolidatePurchases()
        }
    }

    private suspend fun queryPurchasesOfType(type: String): List<Purchase>? =
        suspendCancellableCoroutine { cont ->
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(type).build()
            ) { result, purchases ->
                if (result.responseCode == OK) {
                    cont.resume(purchases) { _, _, _ -> }
                } else {
                    PayWallLog.e("Error querying $type purchases: ${result.debugMessage}")
                    cont.resume(null) { _, _, _ -> }
                }
            }
        }

    private fun consolidatePurchases() {
        val allPurchases = lastInAppPurchases + lastSubsPurchases
        val activeIds = allPurchases.filter { it.purchaseState == PURCHASED }
            .flatMap { it.products }
            .toSet()

        PayWallLog.d("Consolidating owned products. Active IDs: $activeIds")
        _ownedProductIds.value = activeIds

        if (config.autoAcknowledge) {
            allPurchases.forEach { handlePurchase(it) }
        }
    }

    private fun queryProductDetails() {
        PayWallLog.d("Querying product details...")

        if (config.inAppProductIds.isEmpty() && config.subscriptionProductIds.isEmpty()) {
            PayWallLog.w("Product list is empty. Skipping details query.")
            _isReady.value = true
            return
        }

        scope.launch {
            // Billing Library 9+ throws IllegalArgumentException if a single
            // QueryProductDetailsParams mixes INAPP and SUBS products, so each type
            // must be queried separately and merged afterwards.
            val inAppDeferred = config.inAppProductIds.takeIf { it.isNotEmpty() }
                ?.let { ids -> async { queryProductDetailsOfType(ids, BillingClient.ProductType.INAPP) } }
            val subsDeferred = config.subscriptionProductIds.takeIf { it.isNotEmpty() }
                ?.let { ids -> async { queryProductDetailsOfType(ids, BillingClient.ProductType.SUBS) } }

            val inAppResult = inAppDeferred?.await()
            val subsResult = subsDeferred?.await()
            val failedResult = listOfNotNull(inAppResult, subsResult).firstOrNull { it.details == null }

            if (failedResult != null) {
                _isReady.value = false
                scheduleProductDetailsRetry(failedResult.responseCode)
            } else {
                val combined = inAppResult?.details.orEmpty() + subsResult?.details.orEmpty()
                PayWallLog.d("Product details queried: ${combined.size} items.")
                _productDetailsList.value = combined
                _isReady.value = true
                productDetailsRetryAttempt = 0
            }
        }
    }

    private data class ProductDetailsQueryResult(val details: List<ProductDetails>?, val responseCode: Int)

    private suspend fun queryProductDetailsOfType(ids: Set<String>, type: String): ProductDetailsQueryResult =
        suspendCancellableCoroutine { cont ->
            val productList = ids.map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build()
            }
            val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
            billingClient.queryProductDetailsAsync(params) { result, queryProductDetailsResult ->
                if (result.responseCode == OK) {
                    cont.resume(ProductDetailsQueryResult(queryProductDetailsResult.productDetailsList, result.responseCode)) { _, _, _ -> }
                } else {
                    PayWallLog.e("Error querying $type product details: ${result.debugMessage}")
                    cont.resume(ProductDetailsQueryResult(null, result.responseCode)) { _, _, _ -> }
                }
            }
        }

    private fun scheduleProductDetailsRetry(responseCode: Int) {
        if (manuallyDisconnected) return
        if (!isRecoverable(responseCode) || productDetailsRetryAttempt >= MAX_RETRY_ATTEMPTS) {
            PayWallLog.e("Giving up product details retry (code=$responseCode).")
            return
        }
        val delayMs = retryDelayFor(productDetailsRetryAttempt)
        productDetailsRetryAttempt++
        PayWallLog.d("Retrying product details query in ${delayMs}ms (attempt $productDetailsRetryAttempt).")
        productDetailsRetryJob?.cancel()
        productDetailsRetryJob = scope.launch {
            delay(delayMs)
            if (billingClient.connectionState == BillingClient.ConnectionState.CONNECTED) {
                queryProductDetails()
            }
            // If the connection itself dropped meanwhile, the connection-retry path
            // (scheduleReconnect) already owns recovery and will call refreshData().
        }
    }

    private fun isRecoverable(responseCode: Int): Boolean = when (responseCode) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
        BillingClient.BillingResponseCode.DEVELOPER_ERROR,
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> false
        else -> true
    }

    private fun retryDelayFor(attempt: Int): Long =
        (BASE_RETRY_DELAY_MS shl attempt).coerceAtMost(MAX_RETRY_DELAY_MS)

    override fun launchPurchase(activity: Activity, productId: String) {
        if (!isReady.value) {
            PayWallLog.w("LaunchPurchase ignored: SDK not ready.")
            return
        }
        launchBillingFlow(activity, productId, BillingClient.ProductType.INAPP)
    }

    override fun launchSubscription(activity: Activity, productId: String, basePlanId: String?) {
        if (!isReady.value) {
            PayWallLog.w("LaunchSubscription ignored: SDK not ready.")
            return
        }
        launchBillingFlow(activity, productId, BillingClient.ProductType.SUBS, basePlanId)
    }

    private fun launchBillingFlow(activity: Activity, productId: String, type: String, basePlanId: String? = null) {
        PayWallLog.d("Launching billing flow for $productId ($type)...")
        val details = _productDetailsList.value.find { it.productId == productId }
        if (details == null) {
            PayWallLog.e("Product details not found for $productId.")
            Toast.makeText(context, "Produto não encontrado.", Toast.LENGTH_SHORT).show()
            return
        }

        val paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)

        if (type == BillingClient.ProductType.SUBS) {
            val offerToken = details.subscriptionOfferDetails
                ?.firstOrNull { it.basePlanId == basePlanId }?.offerToken
                ?: details.subscriptionOfferDetails?.firstOrNull()?.offerToken

            offerToken?.let { paramsBuilder.setOfferToken(it) }
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(paramsBuilder.build()))
            .build()

        billingClient.launchBillingFlow(activity, flowParams)
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == PURCHASED && !purchase.isAcknowledged) {
            PayWallLog.d("Acknowledging purchase: ${purchase.orderId}")
            val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            billingClient.acknowledgePurchase(params) { result ->
                if (result.responseCode == OK) {
                    PayWallLog.d("Purchase acknowledged successfully.")
                } else {
                    PayWallLog.e("Failed to acknowledge purchase: ${result.debugMessage}")
                }
            }
        }
    }

    override fun refresh() {
        if (billingClient.connectionState == BillingClient.ConnectionState.CONNECTED) {
            refreshData()
        } else {
            PayWallLog.d("Refresh requested while not connected; reconnecting instead.")
            connect()
        }
    }

    private companion object {
        const val MAX_RETRY_ATTEMPTS = 5
        const val BASE_RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_DELAY_MS = 30_000L
    }
}
