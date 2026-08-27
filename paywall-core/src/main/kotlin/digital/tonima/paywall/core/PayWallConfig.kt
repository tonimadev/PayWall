package digital.tonima.paywall.core

/**
 * Configuração inicial do SDK PayWall.
 * 
 * @param inAppProductIds Conjunto de IDs para produtos de compra única (ex: remover anúncios).
 * @param subscriptionProductIds Conjunto de IDs para planos de assinatura mensal/anual.
 */
data class PayWallConfig(
    val inAppProductIds: Set<String> = emptySet(),
    val subscriptionProductIds: Set<String> = emptySet(),
    val autoAcknowledge: Boolean = true,
    val debugMode: Boolean = false
)
