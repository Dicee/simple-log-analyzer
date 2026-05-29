
# ADR 1 - log group and log stream concepts

This ADR introduces and defines the notions of log group and log stream, as well as details the data model of each, which allows a certain degree of configurability. it covers requirement R02 from the [high-level design](high-level-design.md).

## Definitions

A log group is a collection of log streams, all of which have the same format and configuration, but may come from different machines, different processes on a single machine, or different "sessions" of the same application. 

A log group is uniquely identified by a human-readable, user-defined name, while a log stream is uniquely defined by the pair of the log group name it belongs to, and a log stream name that is unique within the log group (initially we can just use a random name, but it might also be configurable later on).

## Log group configuration

All the configuration is handled at the log group level ; there is no configuration at the log stream level. Below are the following configuration elements available for a log group:
- format type, among one of the supported formats (initially JSON, logfmt and plain text)
- custom enricher/field extractor. This notion will be developed in more depth in [ADR2](adr2-initial-ingestion-layer.md).
- log stream maximum byte size. This prevents a log stream, which in the simplest implementation is a single log file, to become overly large.
- compression algorithm to use for at-rest data. Note that this is separate from such considerations for in-transit data, which can be compressed to optimize bandwidth. The sender will decide on the transit format independently of the log group configuration.
- we will skip that initially because it's not important for a pet project and does not add interesting complexity, but eventually we can add a setting for an encryption algorithm to use for at-rest data.

## Storage

For a small-scale project like ours, and with the limited time and resources of a single person (aka, me), it is reasonable to store log group settings and log stream listing (pair of log group name, log stream name) in a simple SQLite file.  We can add metadata such as creation date.

For log streams listings, we can also add the start and end timestamp to help with faster time range queries later on. Finally, we can add other useful metadata such as byte size (which might be empty before the first rotation to the next stream).

SQLite is a single-writer store, but this is not a concern here: metadata writes are rare. The most frequent one is log stream creation (on rotation), which still remains well within acceptable boundaries for the scale of this project. Note that SQLite is chosen simply as a lightweight way to get a relational database without standing up a separate server; it is not what we would use in production. A real-world version of this project would substitute a more scalable storage backend, but this does not meaningfully affect the design, since the data model and access patterns described here would carry over to another relational database.

Additionally, we will need to store the logs themselves. We will not go into many details in this ADR because there will be specific ADRs for efficient storage and retrieval, but we'll just mention that here again, we can safely assume that storing the logs locally on the disk is suitable for our project, as we are creating a local log analyzer packaged as a desktop app rather than a distributed system. Our architecture is not fully based on its objective value, but also tailored to our learning objectives.

## Lifecycle
### Log group

A log group can be:
- created, with the `/create-log-group`, a simple CRUD API offering the configurable settings as input parameters and a name, returning an error if a log group with that name already exists.
- updated, with `/update-log-group`. Not all configuration is editable: some settings would invalidate or be inconsistent with data already at rest. The table below specifies which settings are mutable.

| Setting | Mutable | Notes |
|---|---|---|
| format type | No | Existing at-rest data is stored in the original format; changing it would make already-ingested data inconsistent. |
| compression algorithm | No | At-rest data is already written with the original algorithm; changing it would make already-ingested data inconsistent. |
| custom enrichers / field extractors | Yes | Changes apply only to new log entries; previously ingested entries are not reprocessed. |
| log stream maximum byte size | Yes | Applies going forward; affects when future rotations occur. |
- deleted, with `/delete-log-group`. By default this only succeeds if the group contains no log stream, to avoid accidentally destroying data. A `force` flag can be passed to override this and delete the group along with all its streams in a single call.

### Log stream

A log stream can be: 
- created, with the `create-log-stream` API, which takes only a log group name as a parameter
- deleted, with `delete-log-stream`

Beyond explicit creation through the API, a new log stream is also created automatically on rotation: when a stream reaches the log group's configured maximum byte size, it is closed and a fresh stream is created within the same group to receive subsequent log entries. Note that, within a single log group, multiple streams can be active and populated in parallel (e.g. one per machine, process or session). There is therefore no single "next stream" on rotation: each active stream rotates independently into its own successor.


