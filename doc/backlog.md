# TODO

- update unit test skill to include Kotlin guidance, after trying MockK and Kotest/Turbine long enough
- check`java.nio.file.WatchService`

# Done
- write ADR for log group and log stream concepts
- review ADR1 and ADR2 with Claude
- write ADR for ingestion component (ingestion API, log-nanny, log event data model etc)
- write ADR for tech stack
- add basic setup:
	- Ktor server with appropriate endpoints (not implemented)
	- separate build module for the log poller and log nanny, with a dummy poller and launch script
	- Gradle build config
	- dummy index page for Angular app