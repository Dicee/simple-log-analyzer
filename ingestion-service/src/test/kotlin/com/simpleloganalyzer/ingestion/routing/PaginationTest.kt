package com.simpleloganalyzer.ingestion.routing

import com.simpleloganalyzer.ingestion.exception.ErrorCode
import com.simpleloganalyzer.ingestion.exception.assertThrowsBadRequestException
import com.simpleloganalyzer.ingestion.model.Page
import io.ktor.http.Parameters
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingRequest
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaginationTest {
    private val call = mockk<RoutingCall>()
    private val request = mockk<RoutingRequest>()
    private val params = mockk<Parameters>()

    init {
        every { call.request } returns request
        every { request.queryParameters } returns params
        every { params["pageToken"] } returns null
    }

    @Test
    fun testPageRequest_defaultPageSize() {
        every { params["pageSize"] } returns null
        assertThat(call.pageRequest()).isEqualTo(PageRequest(50, null))
    }

    @Test
    fun testPageRequest_customPageSize() {
        every { params["pageSize"] } returns "10"
        assertThat(call.pageRequest()).isEqualTo(PageRequest(10, null))
    }

    @Test
    fun testPageRequest_pageSizeMin() {
        every { params["pageSize"] } returns "1"
        assertThat(call.pageRequest().pageSize).isEqualTo(1)
    }

    @Test
    fun testPageRequest_pageSizeMax() {
        every { params["pageSize"] } returns "500"
        assertThat(call.pageRequest().pageSize).isEqualTo(500)
    }

    @Test
    fun testPageRequest_withPageToken() {
        every { params["pageSize"] } returns null
        every { params["pageToken"] } returns "some-token"
        assertThat(call.pageRequest()).isEqualTo(PageRequest(50, "some-token"))
    }

    @Test
    fun testPageRequest_pageSizeNotInteger() {
        every { params["pageSize"] } returns "abc"
        assertThrowsBadRequestException(ErrorCode.BAD_REQUEST, "pageSize must be an integer but was abc") {
            call.pageRequest()
        }
    }

    @Test
    fun testPageRequest_pageSizeZero() {
        every { params["pageSize"] } returns "0"
        assertThrowsBadRequestException(ErrorCode.BAD_REQUEST, "pageSize must be between 1 and 500 but was 0") {
            call.pageRequest()
        }
    }

    @Test
    fun testPageRequest_pageSizeNegative() {
        every { params["pageSize"] } returns "-1"
        assertThrowsBadRequestException(ErrorCode.BAD_REQUEST, "pageSize must be between 1 and 500 but was -1") {
            call.pageRequest()
        }
    }

    @Test
    fun testPageRequest_pageSizeTooLarge() {
        every { params["pageSize"] } returns "501"
        assertThrowsBadRequestException(ErrorCode.BAD_REQUEST, "pageSize must be between 1 and 500 but was 501") {
            call.pageRequest()
        }
    }

    @Test
    fun testToPage_partialPage() {
        val items = listOf("a", "b")
        assertThat(items.toPage(5) { it }).isEqualTo(Page(items, nextPageToken = null))
    }

    @Test
    fun testToPage_fullPage() {
        val items = listOf("a", "b", "c")
        assertThat(items.toPage(3) { it }).isEqualTo(Page(items, nextPageToken = "c"))
    }

    @Test
    fun testToPage_emptyList() {
        assertThat(emptyList<String>().toPage(pageSize = 5) { it }).isEqualTo(Page(emptyList<String>(), nextPageToken = null))
    }
}
