# TODO

- update unit test skill to include Kotlin guidance, after trying MockK and Kotest/Turbine long enough
- check`java.nio.file.WatchService`
- use injection to manage dependencies
- when adding metrics, make sure we are ok with delivery semantics (should probably be able to switch from at-least-once to at-most-once ; unfortunately I think exactly-once will be too complicated in edge cases)
- log poller implementation
	- add process supervisor Mac + Linux
- review ADR1 definition for log stream. I think I prefer supporting infinite streams, by splitting into separate files transparently.

# Done
- parse config in CLI
- address remaining TODOs in the `LogPoller
- add checkpointing support in the `LogPoller`
- log poller implementation
  - implement a good retry strategy
  - add unit tests
  - implement multi-line logs
  - add end-to-end tests (only ingestion service mocked)
  - check I implemented the behaviour for all parameters of the config
  - add log stream resolution
- write ADR for log group and log stream concepts
- review ADR1 and ADR2 with Claude
- write ADR for ingestion component (ingestion API, log-nanny, log event data model etc)
- write ADR for tech stack
- add basic setup:
	- Ktor server with appropriate endpoints (not implemented)
	- separate build module for the log poller and log nanny, with a dummy poller and launch script
	- Gradle build config
	- dummy index page for Angular app