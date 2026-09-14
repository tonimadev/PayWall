package digital.tonima.paywall.core

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface principal para gestão de cobranças.
 */
interface PayWallManager {
    /**
     * Emite o conjunto de IDs de produtos que o usuário possui atualmente.
     */
    val ownedProductIds: StateFlow<Set<String>>

    /**
     * Indica se o SDK está pronto para iniciar fluxos de compra.
     */
    val isReady: StateFlow<Boolean>

    /**
     * Conecta o SDK ao serviço de faturamento.
     */
    fun connect()

    /**
     * Encerra a conexão com o serviço de faturamento e libera recursos.
     */
    fun disconnect()

    /**
     * Inicia o fluxo de compra para um produto específico.
     */
    fun launchPurchase(activity: Activity, productId: String)

    /**
     * Inicia o fluxo de assinatura para um plano específico.
     */
    fun launchSubscription(activity: Activity, productId: String, basePlanId: String? = null)

    /**
     * Verifica se um produto específico está ativo.
     */
    fun isPurchased(productId: String): Boolean = ownedProductIds.value.contains(productId)
    
    /**
     * Atualiza o status das compras manualmente.
     */
    fun refresh()
}
