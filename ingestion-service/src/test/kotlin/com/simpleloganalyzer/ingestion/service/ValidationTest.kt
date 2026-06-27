package com.simpleloganalyzer.ingestion.service

import com.simpleloganalyzer.ingestion.exception.ErrorCode
import com.simpleloganalyzer.ingestion.exception.assertThrowsBadRequestException
import com.simpleloganalyzer.ingestion.persistence.metadata.DEFAULT_NAME_MAX_LENGTH
import org.assertj.core.api.Assertions.assertThatNoException
import org.junit.jupiter.api.Test

class ValidationTest {
    @Test
    fun testValidateName_valid() {
        assertThatNoException().isThrownBy { validateName("valid-name_01.ext", "field") }
    }

    @Test
    fun testValidateName_singleChar() {
        assertThatNoException().isThrownBy { validateName("a", "field") }
    }

    @Test
    fun testValidateName_maxLength() {
        val name = "a".repeat(DEFAULT_NAME_MAX_LENGTH)
        assertThatNoException().isThrownBy { validateName(name, "field") }
    }

    @Test
    fun testValidateName_blank() {
        assertThrowsBadRequestException(ErrorCode.INVALID_NAME, "field must not be blank") {
            validateName("   ", "field")
        }
    }

    @Test
    fun testValidateName_empty() {
        assertThrowsBadRequestException(ErrorCode.INVALID_NAME, "field must not be blank") {
            validateName("", "field")
        }
    }

    @Test
    fun testValidateName_tooLong() {
        val name = "a".repeat(DEFAULT_NAME_MAX_LENGTH + 1)
        assertThrowsBadRequestException(
            ErrorCode.INVALID_NAME,
            "field must be at most $DEFAULT_NAME_MAX_LENGTH characters but had ${DEFAULT_NAME_MAX_LENGTH + 1}"
        ) {
            validateName(name, "field")
        }
    }

    @Test
    fun testValidateName_invalidChars() {
        assertThrowsBadRequestException(
            ErrorCode.INVALID_NAME,
            "field must contain only letters, digits, '.', '_' or '-' but was: bad name!"
        ) {
            validateName("bad name!", "field")
        }
    }
}
