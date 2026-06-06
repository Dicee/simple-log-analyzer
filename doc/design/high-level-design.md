# Simple log analyzer - high-level design

## Overview

In this design document, we discuss the high-level design of a simple log analyzer. It is a local developer tool that ingests, indexes, and lets you query 
logs using extracted fields, time-series aggregation and a query language. It comes with a UI allowing to tail live logs, query historical logs,
configure log groups, visualize time series etc.

## Requirements

Requirements are organized by priority (P0 being the highest, P2 the lowest), and each is number using the following pattern: `P<priority><index1><index2><...>`.
Indices are used only to distinguish requirements of the same group/depth within the group, and do not translate into a lower or higher priority. 

### P0

- R00: supported log formats
    - JSON
    - logfmt
    - plain text
- R01: support the concept of field extractor, allowing to run logic on each log event to enrich it with additional fields with built-in or custom logic
- R02: support the notion of log group and log stream
- R03: API to put a batch of raw data into the store
- R04: streaming component allowing to tail a local file
- R05: support pluggable logic to extract things from log messages during tailing (e.g. stacktraces)
- R06: UI
  - R060: log groups
    - create and delete log groups
    - view log groups and log streams (metadata such as last written, size etc)
    - view log stream independently from other streams of the same group
  - R061: log stream visualization
    - live tailing
    - simple filtering on live or historical data
    - display extracted elements
    - grepping with highlighting
    - ability to analyze an existing file after uploading it
    - from a filtered view, give the ability to show the event in its original context

### P1

- R10: additional supported log formats
  - custom metrics log format 
- R11: calculate simple aggregates for time series (sum, avg, min, max)
- R12: support units for metric logs
- R13: support dimensions for metrics
- R14: support queries with a DSL
- R15: UI
  - visualize time series
  - support a right axis

### P2

- R20: calculate more advanced aggregates for time series (percentiles)
- R21: UI
  - periodic polling
- R22 (bonus): support a few compression options and encryption at rest
- R23 (bonus): support anomaly detection

## High-level architecture

### Overview

In this section, we'll discuss the planned architecture at a high-level. We will not delve in full details for each component, but instead will limit ourselves to the broad structure, the role and interaction of each component.

```mermaid
%%{init: {'theme': 'base', 'themeVariables': {'primaryColor': '#ffffff', 'primaryBorderColor': '#cccccc'}}}%%
flowchart TD
    subgraph sources["Sources"]
        J["JSON logs\nstructured"]
        L["logfmt\nkey=value pairs"]
        P["plaintext\nregex extractors"]
    end

    subgraph ingest["Ingestion"]
        PP["Parser pipeline\npluggable format adapters and field extractors"]
        N["Normaliser\ntyped field schema"]
    end

    subgraph storage["Storage"]
        II["Inverted index\nfield value → log event IDs\nterm dict · skip lists/B or B+ tree · row store"]
        TS["Time-series store\nwindowed counters · ring buffers\np50/p95/p99 per window"]
    end

    subgraph query["Query"]
        QE["Query engine\nfield filter · boolean ops · range · aggregation · sort"]
    end

    subgraph ui_ai["UI"]
        UI["UI\nAngular · live tail · filtering · saved queries · charts"]
    end

    J --> PP
    L --> PP
    P --> PP

    PP --> N
    PP --> II
    N --> TS

    II --> QE
    TS --> QE

    QE --> UI

    %% Subgraph container backgrounds
    style sources fill:#F1EFE8,stroke:#B4B2A9,color:#444441
    style ingest  fill:#EEEDFE,stroke:#AFA9EC,color:#3C3489
    style storage fill:#E1F5EE,stroke:#5DCAA5,color:#0F6E56
    style query   fill:#E6F1FB,stroke:#85B7EB,color:#185FA5
    style ui_ai   fill:#FAECE7,stroke:#F0997B,color:#993C1D

    %% Sources — gray
    style J  fill:#D3D1C7,stroke:#5F5E5A,color:#2C2C2A
    style L  fill:#D3D1C7,stroke:#5F5E5A,color:#2C2C2A
    style P  fill:#D3D1C7,stroke:#5F5E5A,color:#2C2C2A

    %% Ingest — purple
    style PP fill:#AFA9EC,stroke:#3C3489,color:#26215C
    style N  fill:#AFA9EC,stroke:#3C3489,color:#26215C

    %% Storage — teal
    style II fill:#5DCAA5,stroke:#0F6E56,color:#04342C
    style TS fill:#5DCAA5,stroke:#0F6E56,color:#04342C

    %% Query — blue
    style QE fill:#85B7EB,stroke:#185FA5,color:#042C53

    %% UI + AI — split: coral for UI, amber for LLM
    style UI  fill:#F0997B,stroke:#993C1D,color:#4A1B0C
````
### Ingestion layer

At a high-level, ingestion will require introducing the following components, concepts and abstractions:
- a PUT API a user can leverage to send data to the service
- a log agent (side-car process) collecting logs on disk, integrating with the PUT API and handling batching, retries and log rotation
- a data model for `LogEvent` Basically, it will be some metadata (including indexable fields) along with the raw data.
- a concept of `Parser`, converting a given input data type into a `LogEvent`
- a concept of `Enricher` or `FieldExtractor`, allowing to plug extra parsing behaviour to add more indexable fields than the default parser supports

This is discussed in more depth in [ADR2](adr2-initial-ingestion-layer.md). It covers requirements R00, R01 and R03-05.

## Technical stack

In this section, we will go through the tech stack and the organization of the build. It is important to note that here, technology choices are very much driven by our learning objectives, and wouldn't necessarily make sense, or be the best, in a real production project. The main technologies we will use are the following:
- Kotlin will be used for the log ingestion service (Ktor server) as well as the log agent interfacing with the ingestion service. Ktor is a pretty adequate choice because it is intrinsically asynchronous (using coroutines), which is often a good choice for use cases where IO and network are big contributors to the latency.
- Angular will be used for the front-end. It might be overkill for a simple app, but I wanted to have a personal project in Angular since I recently started using Angular at work.
- Docker Compose will be used to encapsulate the front-end and back-end, and allow running them together as a unit in a container. This setup is appropriate enough for a dev tool (I intend to use the tool for my local debugging).
- For testing, we'll mainly use, JUnit5, MockK, AssertJ (I love its syntax and rich feature set). We will also have to use either Kotest or Turbine (to be experimented with) for coroutine testing.

### Target system

Because we will be running in Docker, we will exclusively target Linux (maybe Ubuntu but more likely a lighter distribution). Regarding the log agent, we plan to leverage OS-specific process supervising mechanisms (see [ADR2](adr2-initial-ingestion-layer.md)), and therefore to save time and effort we will only target macOS for the moment, with perhaps Linux as a bonus, but definitely not Windows.

### Build and packaging

We will use Gradle as our build system, and the main targets of the build will be the following:
- a jar containing the code required to run the ingestion service
- a zip including a jar for the log agent as well as everything that is necessary to configure and install process supervision for the agent

