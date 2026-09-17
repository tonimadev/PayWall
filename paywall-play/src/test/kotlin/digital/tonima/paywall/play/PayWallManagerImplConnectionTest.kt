package digital.tonima.paywall.play

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PurchasesResponseListener
import digital.tonima.paywall.core.PayWallConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PayWallManagerImplConnectionTest {

    private val context = mockk<Context>(relaxed = true)
    private val billingClient = mockk<BillingClient>(relaxed = true)
    private val dispatcher = UnconfinedTestDispatcher()
    private val startConnectionListeners = mutableListOf<BillingClientStateListener>()

    private lateinit var manager: PayWallManagerImpl

    @Before
    fun setUp() {
        every { billingClient.connectionState } returns BillingClient.ConnectionState.DISCONNECTED
        every { billingClient.startConnection(any()) } answers { startConnectionListeners.add(firstArg()) }
        // Purchases resolve to "nothing owned" by default unless a test overrides this.
        every { billingClient.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(okResult(), emptyList())
        }

        manager = PayWallManagerImpl(
            context = context,
            config = PayWallConfig(), // empty product ids -> isReady flips true as soon as connection succeeds
            mainDispatcher = dispatcher,
            billingClientFactory = { _, _ -> billingClient }
        )
    }

    private fun okResult(): BillingResult =
        BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build()

    private fun failResult(code: Int): BillingResult =
        BillingResult.newBuilder().setResponseCode(code).setDebugMessage("boom").build()

    @Test
    fun `connect starts the connection and becomes ready on success`() {
        assertFalse(manager.isReady.value)

        manager.connect()
        startConnectionListeners.single().onBillingSetupFinished(okResult())

        assertTrue(manager.isReady.value)
    }

    @Test
    fun `connect when already connected just refreshes instead of reconnecting`() {
        every { billingClient.connectionState } returns BillingClient.ConnectionState.CONNECTED

        manager.connect()

        verify(exactly = 0) { billingClient.startConnection(any()) }
        verify(atLeast = 1) { billingClient.queryPurchasesAsync(any(), any()) }
    }

    @Test
    fun `connect when already connecting does not start a second connection`() {
        every { billingClient.connectionState } returns BillingClient.ConnectionState.CONNECTING

        manager.connect()

        verify(exactly = 0) { billingClient.startConnection(any()) }
    }

    @Test
    fun `connect when client is closed builds a fresh client instead of reusing the dead one`() {
        val secondClient = mockk<BillingClient>(relaxed = true)
        every { billingClient.connectionState } returns BillingClient.ConnectionState.CLOSED
        val secondClientListeners = mutableListOf<BillingClientStateListener>()
        every { secondClient.startConnection(any()) } answers { secondClientListeners.add(firstArg()) }
        every { secondClient.connectionState } returns BillingClient.ConnectionState.DISCONNECTED
        every { secondClient.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(okResult(), emptyList())
        }

        var callCount = 0
        val factoryManager = PayWallManagerImpl(
            context = context,
            config = PayWallConfig(),
            mainDispatcher = dispatcher,
            billingClientFactory = { _, _ -> if (callCount++ == 0) billingClient else secondClient }
        )

        factoryManager.connect()

        verify(exactly = 0) { billingClient.startConnection(any()) }
        assertEquals(1, secondClientListeners.size)
    }

    @Test
    fun `setup failure with a recoverable code schedules a reconnect`() {
        manager.connect()
        startConnectionListeners.single().onBillingSetupFinished(failResult(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE))

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, startConnectionListeners.size)
    }

    @Test
    fun `setup failure with an unrecoverable code never retries`() {
        manager.connect()
        startConnectionListeners.single().onBillingSetupFinished(failResult(BillingClient.BillingResponseCode.DEVELOPER_ERROR))

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, startConnectionListeners.size)
    }

    @Test
    fun `reconnection gives up after the maximum number of attempts`() {
        manager.connect()

        // Initial attempt + up to 5 retries = 6 total startConnection calls, then it stops.
        repeat(7) {
            startConnectionListeners.last().onBillingSetupFinished(failResult(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE))
            dispatcher.scheduler.advanceUntilIdle()
        }

        assertEquals(6, startConnectionListeners.size)
    }

    @Test
    fun `unexpected service disconnection marks not ready and reconnects`() {
        manager.connect()
        startConnectionListeners.single().onBillingSetupFinished(okResult())
        assertTrue(manager.isReady.value)

        startConnectionListeners.single().onBillingServiceDisconnected()

        assertFalse(manager.isReady.value)

        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, startConnectionListeners.size)
    }

    @Test
    fun `disconnect tears down the connection and stops future reconnect attempts`() {
        every { billingClient.connectionState } returns BillingClient.ConnectionState.CONNECTED
        manager.connect()

        manager.disconnect()

        verify(exactly = 1) { billingClient.endConnection() }
        assertFalse(manager.isReady.value)

        // A stray disconnect callback firing after disconnect() must not resurrect the retry loop.
        startConnectionListeners.singleOrNull()?.onBillingServiceDisconnected()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, startConnectionListeners.size)
    }

    @Test
    fun `disconnect does not call endConnection when the client is already closed`() {
        every { billingClient.connectionState } returns BillingClient.ConnectionState.CLOSED

        manager.disconnect()

        verify(exactly = 0) { billingClient.endConnection() }
    }

    @Test
    fun `refresh reconnects instead of querying when not connected`() {
        every { billingClient.connectionState } returns BillingClient.ConnectionState.DISCONNECTED

        manager.refresh()

        assertEquals(1, startConnectionListeners.size)
    }

    @Test
    fun `refresh queries directly when already connected`() {
        every { billingClient.connectionState } returns BillingClient.ConnectionState.CONNECTED

        manager.refresh()

        assertEquals(0, startConnectionListeners.size)
        verify(atLeast = 1) { billingClient.queryPurchasesAsync(any(), any()) }
    }
}
