package com.simpleloganalyzer.ingestion.modules

import com.simpleloganalyzer.ingestion.persistence.metadata.LogMetadataStore
import com.simpleloganalyzer.ingestion.persistence.metadata.LogFileDao
import com.simpleloganalyzer.ingestion.persistence.metadata.LogGroupDao
import com.simpleloganalyzer.ingestion.persistence.metadata.LogStreamDao
import com.simpleloganalyzer.ingestion.service.LogFileService
import com.simpleloganalyzer.ingestion.service.LogGroupService
import com.simpleloganalyzer.ingestion.service.LogStreamService
import kotlin.time.Clock
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

fun ingestionModule(dbPath: String) = module {
    single<Clock> { Clock.System }
    single { LogMetadataStore.connect(dbPath) }

    singleOf(::LogGroupDao)
    singleOf(::LogStreamDao)
    singleOf(::LogFileDao)

    singleOf(::LogGroupService)
    singleOf(::LogStreamService)
    singleOf(::LogFileService)
}
