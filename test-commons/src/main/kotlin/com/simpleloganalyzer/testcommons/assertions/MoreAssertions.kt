package com.simpleloganalyzer.testcommons.assertions

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable

object MoreAssertions {
    fun assertThatThrownBy(callable: ThrowingCallable): ThrowableAssert {
        return assertThatThrowable(Assertions.catchThrowable(callable))
    }

    fun assertThatThrowable(t: Throwable): ThrowableAssert {
        return ThrowableAssert(t)
    }
}