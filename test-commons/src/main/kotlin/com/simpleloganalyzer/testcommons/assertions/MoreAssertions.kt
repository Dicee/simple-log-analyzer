package com.simpleloganalyzer.testcommons.assertions

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable

object MoreAssertions {
    fun assertThatThrownBy(recursive: Boolean = true, callable: ThrowingCallable): ThrowableAssert {
        return assertThatThrowable(Assertions.catchThrowable(callable), recursive)
    }

    fun assertThatThrowable(t: Throwable, recursive: Boolean = true): ThrowableAssert {
        return ThrowableAssert(t, recursive)
    }
}