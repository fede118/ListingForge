package com.section11.listingforge.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryPendingAuthStoreTest {

    @Test
    fun `consume returns the stored verifier exactly once`() {
        val store = InMemoryPendingAuthStore()
        store.put("state-1", "verifier-1", AuthClient.WEB)

        assertEquals("verifier-1", store.consume("state-1")?.verifier)
        assertNull(store.consume("state-1"), "second consume must be null (single-use)")
    }

    @Test
    fun `consume of an unknown state is null`() {
        val store = InMemoryPendingAuthStore()
        assertNull(store.consume("never-issued"))
    }

    @Test
    fun `expired entries are not returned`() {
        val store = InMemoryPendingAuthStore(ttlSeconds = 0)
        store.put("state-1", "verifier-1", AuthClient.WEB)
        Thread.sleep(1100)

        assertNull(store.consume("state-1"))
    }
}
