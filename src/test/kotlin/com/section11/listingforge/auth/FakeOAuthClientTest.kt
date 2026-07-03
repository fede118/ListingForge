package com.section11.listingforge.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeOAuthClientTest {

    @Test
    fun `exchangeCode returns a canned record for the mock user, ignoring its inputs`() = runTest {
        val record = FakeOAuthClient().exchangeCode(code = "irrelevant", verifier = "irrelevant")

        assertEquals("mock-user", record.userId)
    }

    @Test
    fun `refresh returns a record for the same mock user`() = runTest {
        val record = FakeOAuthClient().refresh(refreshToken = "irrelevant")

        assertEquals("mock-user", record.userId)
    }
}
