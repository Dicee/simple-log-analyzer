package com.simpleloganalyzer.testcommons.assertions

import org.assertj.core.api.AbstractThrowableAssert

class ThrowableAssert(
    actual: Throwable?,
    private val recursive: Boolean = true
) : AbstractThrowableAssert<ThrowableAssert?, Throwable?>(actual, ThrowableAssert::class.java) {
    /** Allows deep assertions of exceptions in a recursive manner for perfect test accuracy despite exceptions
     * not defining equality properly */
    fun isLike(expected: Throwable): ThrowableAssert {
        hasBeenThrown()
        hasSameClassAs(expected)
        hasMessage(expected.message)
        if (recursive && expected.cause != null) {
            ThrowableAssert(actual!!.cause).isLike(expected.cause!!)
        }
        return this
    }
}