
# ADR 1 - log group and log stream concepts

This ADR introduces and defines the notions of log group and log stream, as well as details the data model of each, which allows a certain degree of configurability. it covers requirement R02 from the [high-level design](high-level-design.md).

## Definitions

A log group is a collection of log streams, all of which have the same format and configuration, but may come from different machines, different processes on a single machine, or different "sessions" of the same application. 

A log group is uniquely identified by a human-readable, user-defined id, while a log stream is uniquely defined by the pair of the log group id it belongs to, and a log stream name that is unique within the log group (initially we can just use a random id, but it might also be configurable later on).

## Log group configuration

All the configuration is handled at the log group level ; there is no configuration at the log stream level. Below are the following configuration elements available for a log group:
- format type, among one of the supported formats (initially JSON, logfmt and plain text)
- custom enricher/field extractor. This notion will be developed in more depth in [ADR2](adr2-initial-ingestion-layer.md).
- log stream maximum byte size. This prevents a log stream, which in the simplest implementation is a single log file, to become overly large.
- compression algorithm to use for at-rest data. Note that this is separate from such considerations for in-transit data, which can be compressed to optimize bandwidth. The sender will decide on the transit format independently of the log group configuration.
- we will skip that initially because it's not important for a pet project and does not add interesting complexity, but eventually we can add a setting for an encryption algorithm to use for at-rest data.

## Storage

For a small-scale project like ours, and with the limited time and resources of a single person (aka, me), it is reasonable to store log group settings and log stream listing (pair of log group id, log stream id) in a simple SQLite file.  We can add metadata such as creation date.

For log streams listings, we can also add the start and end timestamp to help with faster time range queries later on. Finally, we can add other useful metadata such as byte size (which might be empty before the first rotation to the next stream).

Additionally, we will need to store the logs themselves. We will not go into many details in this ADR because there will be specific ADRs for efficient storage and retrieval, but we'll just mention that here again, we can safely assume that storing the logs locally on the disk is suitable for our project, as we are creating a local log analyzer packaged as a desktop app rather than a distributed system. Our architecture is not fully based on its objective value, but also tailored to our learning objectives.

## Lifecycle
### Log group

A log group can be:
- created, with the `/create-log-group`, a simple CRUD API offering the configurable settings as input parameters and a name and returning a log group id, or an error if the log group already exists.
- updated, with `/update-log-group`. Only the list of custom enrichers/field extractors will be editable at least at first, and changes to it will apply only to new log entries.
- deleted, with `/delete-log-group`, only if it contains no log stream

### Log stream

A log group can be: 
- created, with the `create-log-stream` API, which takes only a log group id as a parameter
- deleted, with `delete-log-stream`


