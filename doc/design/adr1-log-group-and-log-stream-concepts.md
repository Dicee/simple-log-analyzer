
# ADR 1 - log group and log stream concepts

This ADR introduces and defines the notions of log group and log stream, as well as details the data model of each, which allows a certain degree of configurability. it covers requirement R02 from the [high-level design](high-level-design.md).

## Definitions

A log group is a collection of log streams, all of which have the same format and configuration, but may come from different machines, different processes on a single machine, or different "sessions" of the same application. 

A log group is uniquely identified by a human-readable, user-defined name, while a log stream is uniquely defined by the pair of the log group name it belongs to, and a log stream name that is unique within the log group (initially we can just use a random name, but it might also be configurable later on). Under a long stream, the system may choose to divide the stream into multiple files, but this would be transparent to the end user.

## Log group configuration

All the configuration is handled at the log group level ; there is no configuration at the log stream level. Below are the following configuration elements available for a log group:
- an optional, free-form description (human-readable) to allow the user document what the group is for.
- format type, among one of the supported formats (initially JSON, logfmt and plain text)
- custom enricher/field extractor. This notion will be developed in more depth in [ADR2](adr2-initial-ingestion-layer.md).
- compression algorithm to use for at-rest data. Note that this is separate from such considerations for in-transit data, which can be compressed to optimize bandwidth. The sender will decide on the transit format independently of the log group configuration.
- we will skip that initially because it's not important for a pet project and does not add interesting complexity, but eventually we can add a setting for an encryption algorithm to use for at-rest data.

## Metadata storage

### Storage type

This section addresses how we store all the log group/log stream metadata, i.e. what groups and streams exist, when they were created etc. This is not about how we store the actual data (logs and metrics), which will come later.

This metadata should be written at a relatively low TPS (in particular with the scale of our project, but even in general, relative to the rate of writing actual data, writing data is negligible). A relational database is a pretty adequate way to do store such data. For a small-scale project like ours, and with the limited time and resources of a single person (aka, me), it is reasonable to store log group settings and log stream listing (pair of log group name, log stream name) in a simple SQLite file.

Note that while SQLite is a single-writer store, this is not a concern here: metadata writes are rare. The most frequent one type of object creation is to rotate a file within a log stream , which still remains well within acceptable boundaries for the scale of this project. Note that SQLite is chosen simply as a lightweight way to get a relational database without standing up a separate server; it is not what we would use in production. A real-world version of this project would substitute a more scalable storage backend, but this does not meaningfully affect the design, since the data model and access patterns described in the next section would carry over to another relational database.

### Schema

We propose the following 3 tables:

**log_groups**

| Column        | Primary key | Type         | Nullable |
| ------------- | ----------- | ------------ | -------- |
| name          | yes         | varchar(50)  | false    |
| description   | no          | varchar(200) | true     |
| creation_date | no          | timestamp    | false    |
| format        | no          | varchar      | false    |
| compression   | no          | varchar      | false    |

**log_group_enrichers**

| Column    | Primary key | Foreign key | Type        | Nullable |
| --------- | ----------- | ----------- | ----------- | -------- |
| log_group | yes         | yes         | varchar(50) | false    |
| type      | yes         | no          | varchar     | false    |
| order     | no          | no          | integer     | false    |
| args      | no          | no          | json        | true     |

**log_streams**

| Column        | Primary key | Foreign key | Type        | Nullable |
| ------------- | ----------- | ----------- | ----------- | -------- |
| log_group     | yes         | yes         | varchar(50) | false    |
| stream_name   | yes         | no          | varchar(50) | false    |
| creation_date | no          | no          | timestamp   | false    |

**log_files**

| Column             | Primary key | Foreign key | Type        | Nullable |
| ------------------ | ----------- | ----------- | ----------- | -------- |
| log_group          | yes         | yes         | varchar(50) | false    |
| log_stream         | yes         | yes         | varchar(50) | false    |
| file_name          | yes         | no          | varchar     | false    |
| creation_date      | no          | no          | timestamp   | false    |
| last_modified_date | no          | no          | timestamp   | false    |
| first_timestamp    | no          | no          | timestamp   | false    |
| last_timestamp     | no          | no          | timestamp   | false    |

The only table which will have a fast write rate is `log_files`. The first 4 columns will be written only once, but the last 3 should theoretically be written to every time we write a batch for a given stream. However, it is not critical that these fields stay as fresh as possible. `last_modified_date` is mainly aimed for informational purposes, to display it to the user, while the last two columns are meant for fastening range queries later on. For this reason, it is acceptable to cache the writes and flush them only from time to time, at a slower pace than logs are published. This introduces the risk of data loss in case of a crash or restart of the ingestion service, however this is acceptable for the same reasons as before: this data's freshness isn't critical, and we can always compute the exact value of each dynamic column even if we lost intermediary updates, since we can just look at the files on disk.

Note that adding byte size would also be interesting for `log_files`, which would likely also be a column that we do not write to every single time. We'd just need to ensure that when rotation happens, we always write all the columns immediately before rotating.

## Lifecycle
### Log group

A log group can be:
- created, with the `/create-log-group`, a simple CRUD API offering the configurable settings as input parameters and a name, returning an error if a log group with that name already exists.
- updated, with `/update-log-group`. Not all configuration is editable: some settings would invalidate or be inconsistent with data already at rest. The table below specifies which settings are mutable.

| Setting                             | Mutable | Notes                                                                                                                   |
| ----------------------------------- | ------- | ----------------------------------------------------------------------------------------------------------------------- |
| description                         | Yes     | Purely informational; can be edited freely without affecting ingested data.                                             |
| format type                         | No      | Existing at-rest data is stored in the original format; changing it would make already-ingested data inconsistent.      |
| compression algorithm               | No      | At-rest data is already written with the original algorithm; changing it would make already-ingested data inconsistent. |
| custom enrichers / field extractors | Yes     | Changes apply only to new log entries; previously ingested entries are not reprocessed.                                 |
- deleted, with `/delete-log-group`. By default this only succeeds if the group contains no log stream, to avoid accidentally destroying data. A `force` flag can be passed to override this and delete the group along with all its streams in a single call.

Enrichers do not have a lifecycle of their own, they only live as a sub-configuration of a log group.
### Log stream

A log stream can be: 
- created, with the `create-log-stream` API, which takes only a log group name as a parameter
- deleted, with `delete-log-stream`. Note that this deletes all log files under this stream. 
### Log file

 Files cannot be created nor deleted through an API, they're an internal implementation detail and their lifecycle is entirely dependent of their containing log stream's lifecycle.



