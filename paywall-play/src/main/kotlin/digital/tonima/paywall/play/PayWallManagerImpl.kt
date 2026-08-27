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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class PayWallManagerImpl(
    private val context: Context,
    private val config: PayWallConfig
) : PayWallManager {

    init {
        PayWallLog.isDebugEnabled = config.debugMode
    }

    private val _ownedProductIds = MutableStateFlow<Set<String>>(emptySet())
    override val ownedProductIds = _ownedProductIds.asStateFlow()

    private val _productDetailsList = MutableStateFlow<List<ProductDetails>>(emptyList())
    val productDetailsList = _productDetailsList.asStateFlow()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        PayWallLog.d("onPurchasesUpdated: ${billingResult.responseCode}, count: ${purchases?.size ?: 0}")
        if (billingResult.responseCode == OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
        }
    }

    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    override fun connect() {
        PayWallLog.d("Connecting to Billing Client...")
        if (billingClient.isReady) {
            PayWallLog.d("Billing Client already ready.")
            queryPurchases()
            queryProductDetails()
            return
        }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == OK) {
                    PayWallLog.d("Billing client setup finished.")
                    queryPurchases()
                    queryProductDetails()
                } else {
                    PayWallLog.e("Billing client setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                PayWallLog.w("Billing service disconnected.")
            }
        })
    }

    private fun queryPurchases() {
        PayWallLog.d("Querying purchases...")
        // Query IN-APP
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { result, purchases ->
            if (result.responseCode == OK) {
                PayWallLog.d("Purchases (INAPP) found: ${purchases.size}")
                updateOwnedProducts(purchases)
            } else {
                PayWallLog.e("Error querying INAPP purchases: ${result.debugMessage}")
            }
        }

        // Query SUBS
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { result, purchases ->
            if (result.responseCode == OK) {
                PayWallLog.d("Purchases (SUBS) found: ${purchases.size}")
                updateOwnedProducts(purchases)
            } else {
                PayWallLog.e("Error querying SUBS purchases: ${result.debugMessage}")
            }
        }
    }

    private fun updateOwnedProducts(purchases: List<Purchase>) {
        val activeIds = purchases.filter { it.purchaseState == PURCHASED }
            .flatMap { it.products }
            .toSet()
        
        PayWallLog.d("Updating owned products. Active IDs: $activeIds")
        _ownedProductIds.update { it + activeIds }
        
        if (config.autoAcknowledge) {
            purchases.forEach { handlePurchase(it) }
        }
    }

    private fun queryProductDetails() {
        PayWallLog.d("Querying product details...")
        val productList = mutableListOf<QueryProductDetailsParams.Product>()
        
        config.inAppProductIds.forEach { 
            productList.add(QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build())
        }
        
        config.subscriptionProductIds.forEach { 
            productList.add(QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build())
        }

        if (productList.isEmpty()) {
            PayWallLog.w("Product list is empty. Skipping details query.")
            return
        }

        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        billingClient.queryProductDetailsAsync(params) { result, queryProductDetailsResult ->
            if (result.responseCode == OK) {
                val details = queryProductDetailsResult.productDetailsList
                PayWallLog.d("Product details queried: ${details.size} items.")
                _productDetailsList.value = details
            } else {
                PayWallLog.e("Error querying product details: ${result.debugMessage}")
            }
        }
    }

    override fun launchPurchase(activity: Activity, productId: String) {
        launchBillingFlow(activity, productId, BillingClient.ProductType.INAPP)
    }

    override fun launchSubscription(activity: Activity, productId: String, basePlanId: String?) {
        launchBillingFlow(activity, productId, BillingClient.ProductType.SUBS, basePlanId)
    }

    private fun launchBillingFlow(activity: Activity, productId: String, type: String, basePlanId: String? = null) {
        PayWallLog.d("Launching billing flow for $productId ($type)...")
        val details = _productDetailsList.value.find { it.productId == productId }
        if (details == null) {
            PayWallLog.e("Product details not found for $productId. Make sure it was queried.")
            Toast.makeText(context, "Produto não encontrado.", Toast.LENGTH_SHORT).show()
            return
        }

        val paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)

        if (type == BillingClient.ProductType.SUBS) {
            val offerToken = details.subscriptionOfferDetails
                ?.firstOrNull { it.basePlanId == basePlanId }?.offerToken
                ?: details.subscriptionOfferDetails?.firstOrNull()?.offerToken
            
            offerToken?.let { 
                PayWallLog.d("Using offer token: $it")
                paramsBuilder.setOfferToken(it) 
            }
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(paramsBuilder.build()))
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        PayWallLog.d("Billing flow launched. Result code: ${result.responseCode}")
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == PURCHASED && !purchase.isAcknowledged) {
            PayWallLog.d("Acknowledging purchase: ${purchase.orderId}")
            val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            billingClient.acknowledgePurchase(params) { result ->
                if (result.responseCode == OK) {
                    PayWallLog.d("Purchase acknowledged successfully.")
                    queryPurchases()
                } else {
                    PayWallLog.e("Failed to acknowledge purchase: ${result.debugMessage}")
                }
            }
        }
    }

    override fun refresh() {
        queryPurchases()
    }
}
