package digital.tonima.paywall.sample

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import digital.tonima.paywall.core.PayWallConfig
import digital.tonima.paywall.play.PayWallManagerImpl
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var payWallManager: PayWallManagerImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val config = PayWallConfig(
            inAppProductIds = setOf("premium_upgrade"),
            subscriptionProductIds = setOf("monthly_sub"),
            debugMode = true
        )

        payWallManager = PayWallManagerImpl(this, config)

        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val btnPurchase = findViewById<Button>(R.id.btnPurchase)
        val btnSubscribe = findViewById<Button>(R.id.btnSubscribe)
        val txtStatus = findViewById<TextView>(R.id.txtStatus)

        btnConnect.setOnClickListener {
            payWallManager.connect()
        }

        btnPurchase.setOnClickListener {
            payWallManager.launchPurchase(this, "premium_upgrade")
        }

        btnSubscribe.setOnClickListener {
            payWallManager.launchSubscription(this, "monthly_sub")
        }

        lifecycleScope.launch {
            payWallManager.ownedProductIds.collect { ownedIds ->
                txtStatus.text = "Owned: ${ownedIds.joinToString(", ").ifEmpty { "None" }}"
            }
        }
    }
}
