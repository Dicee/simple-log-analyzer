package com.simpleloganalyzer.ingestion.exception

import com.simpleloganalyzer.testcommons.assertions.MoreAssertions.assertThatThrowable
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ThrowableAssert.ThrowingCallable

fun assertThrowsBadRequestException(code: ErrorCode, msg: String, recursive: Boolean = true, callable: ThrowingCallable) {
    val expected = BadRequestException(code, msg)
    val actual = Assertions.catchThrowable(callable)

    assertThatThrowable(actual, recursive).isLike(expected)
    assertThat((actual as BadRequestException).code).`as`("BadRequestException has the correct code").isEqualTo(code)
}
