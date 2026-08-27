package digital.tonima.paywall.play

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient.BillingResponseCode.OK
import com.android.billingclient.api.Purchase.PurchaseState.PURCHASED
import digital.tonima.paywall.core.PayWallConfig
import digital.tonima.paywall.core.PayWallManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "PayWallSDK"

class PayWallManagerImpl(
    private val context: Context,
    private val config: PayWallConfig
) : PayWallManager {

    private val _ownedProductIds = MutableStateFlow<Set<String>>(emptySet())
    override val ownedProductIds = _ownedProductIds.asStateFlow()

    private val _productDetailsList = MutableStateFlow<List<ProductDetails>>(emptyList())
    val productDetailsList = _productDetailsList.asStateFlow()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
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
        if (billingClient.isReady) {
            queryPurchases()
            queryProductDetails()
            return
        }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == OK) {
                    Log.d(TAG, "Billing client setup finished.")
                    queryPurchases()
                    queryProductDetails()
                } else {
                    Log.e(TAG, "Billing client setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected.")
            }
        })
    }

    private fun queryPurchases() {
        // Query IN-APP
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { result, purchases ->
            if (result.responseCode == OK) {
                updateOwnedProducts(purchases)
            }
        }

        // Query SUBS
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { result, purchases ->
            if (result.responseCode == OK) {
                updateOwnedProducts(purchases)
            }
        }
    }

    private fun updateOwnedProducts(purchases: List<Purchase>) {
        val activeIds = purchases.filter { it.purchaseState == PURCHASED }
            .flatMap { it.products }
            .toSet()
        
        _ownedProductIds.update { it + activeIds }
        
        if (config.autoAcknowledge) {
            purchases.forEach { handlePurchase(it) }
        }
    }

    private fun queryProductDetails() {
        val productList = mutableListOf<QueryProductDetailsParams.Product>()
        
        config.inAppProductIds.forEach { 
            productList.add(QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build())
        }
        
        config.subscriptionProductIds.forEach { 
            productList.add(QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build())
        }

        if (productList.isEmpty()) return

        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        billingClient.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == OK) {
                _productDetailsList.value = details
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
        val details = _productDetailsList.value.find { it.productId == productId }
        if (details == null) {
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
            val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            billingClient.acknowledgePurchase(params) { result ->
                if (result.responseCode == OK) {
                    queryPurchases()
                }
            }
        }
    }

    override fun refresh() {
        queryPurchases()
    }
}
