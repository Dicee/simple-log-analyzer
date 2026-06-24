package com.simpleloganalyzer.ingestion.service

import com.simpleloganalyzer.ingestion.exception.BadRequestException
import com.simpleloganalyzer.ingestion.exception.ErrorCode
import com.simpleloganalyzer.ingestion.persistence.metadata.DEFAULT_NAME_MAX_LENGTH

private val NAME_REGEX = Regex("^[A-Za-z0-9._-]+$")

internal fun validateName(value: String, fieldLabel: String) {
    if (value.isBlank()) {
        throw BadRequestException(ErrorCode.INVALID_NAME, "$fieldLabel must not be blank")
    }

    val maxLength = DEFAULT_NAME_MAX_LENGTH
    if (value.length > maxLength) {
        throw BadRequestException(ErrorCode.INVALID_NAME, "$fieldLabel must be at most $maxLength characters")
    }
    if (!NAME_REGEX.matches(value)) {
        throw BadRequestException(
            ErrorCode.INVALID_NAME,
            "$fieldLabel must contain only letters, digits, '.', '_' or '-'",
        )
    }
}
