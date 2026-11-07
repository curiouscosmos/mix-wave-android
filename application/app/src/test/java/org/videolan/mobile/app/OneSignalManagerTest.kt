package org.videolan.mobile.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneSignalManagerTest {
    @Test
    fun registrationRequiresAServerAssignedSubscriptionId() {
        listOf(null, "", " ", "local-", "local-pending-subscription").forEach {
            assertFalse(OneSignalManager.isRegistered(it))
        }
        assertTrue(OneSignalManager.isRegistered("3a947604-718a-4434-a1e2-c1cf6af21df9"))
    }
}
