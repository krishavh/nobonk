package ai.genwhy.nobonk.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LoadTicketTest {
    @Test fun supersededLoadMayNotAdoptItsResult() {
        val t = LoadTicket(); val a = t.begin(); val b = t.begin()   // user switched model twice quickly
        assertFalse(t.isCurrent(a)); assertTrue(t.isCurrent(b))
    }
    @Test fun stopOrClearDuringLoadBlocksAdoption() {
        val t = LoadTicket(); val a = t.begin(); t.closeAll()
        assertFalse(t.isCurrent(a))
        t.reopen(); val c = t.begin(); assertTrue(t.isCurrent(c))
    }
    @Test fun modelSwitchWhileFirstLoadBlockedOnAnotherThread() {
        val t = LoadTicket(); val first = t.begin()
        val loading = CountDownLatch(1); val adopted = booleanArrayOf(false)
        val worker = Thread { loading.await(2, TimeUnit.SECONDS); adopted[0] = t.isCurrent(first) }
        worker.start()
        val second = t.begin()                    // switch requested while the first load is still running
        loading.countDown(); worker.join()
        assertFalse(adopted[0]); assertTrue(t.isCurrent(second))
    }
    @Test fun clearDuringLoadThenRecreateStartsFresh() {
        val t = LoadTicket(); val a = t.begin(); t.closeAll()
        val fresh = LoadTicket(); val b = fresh.begin()           // new ViewModel after clear/recreate
        assertFalse(t.isCurrent(a)); assertTrue(fresh.isCurrent(b))
    }
}
