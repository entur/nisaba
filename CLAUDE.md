# Nisaba - Project Overview

## Purpose

Nisaba is a Java Spring Boot application that monitors NeTEx (Network Timetable Exchange) dataset exports and publishes Kafka events when new timetable datasets become available. It acts as a bridge between Marduk's dataset exports and downstream consumers that need to be notified of new timetable data.

## Core Functionality

Nisaba implements an event-driven workflow that:

1. **Monitors** Google PubSub queue (`NetexExportNotificationQueue`) for NeTEx export notifications from [Marduk](https://github.com/entur/marduk)
2. **Downloads** NeTEx datasets from Google Cloud Storage when notified
3. **Extracts** dataset creation dates from CompositeFrame XML elements within the NeTEx archive
4. **Deduplicates** using an idempotent repository backed by Kafka to track previously processed datasets
5. **Publishes** Kafka events only for new datasets (identified by codespace + creation date)

## Technology Stack

- **Java 21** with Spring Boot 3.x
- **Apache Camel 4.8.9** for enterprise integration patterns and routing
- **Google Cloud Platform**
  - Cloud Storage (GCS) for dataset storage
  - PubSub for event notifications
- **Apache Kafka** with Avro serialization for event publishing
- **Maven** for build management
- **Docker** for containerization
- **Helm** for Kubernetes deployment

## Architecture

### Project Structure

```
nisaba/
├── src/main/java/no/entur/nisaba/
│   ├── App.java                          # Spring Boot application entry point
│   ├── Constants.java                    # Application constants
│   ├── config/                           # Configuration classes
│   │   ├── CamelConfig.java
│   │   ├── GcsBlobStoreRepositoryConfig.java
│   │   ├── InMemoryBlobStoreRepositoryConfig.java
│   │   └── LocalDiskBlobStoreRepositoryConfig.java
│   ├── routes/                           # Camel route definitions
│   │   ├── BaseRouteBuilder.java
│   │   ├── netex/notification/
│   │   │   ├── NetexImportNotificationQueueRouteBuilder.java
│   │   │   └── RestNotificationRouteBuilder.java
│   │   └── blobstore/
│   │       ├── MardukBlobStoreRoute.java
│   │       ├── NisabaBlobStoreRoute.java
│   │       └── NisabaExchangeBlobStoreRoute.java
│   ├── services/                         # Blob store service implementations
│   │   ├── AbstractBlobStoreService.java
│   │   ├── MardukBlobStoreService.java
│   │   ├── NisabaBlobStoreService.java
│   │   └── NisabaExchangeBlobStoreService.java
│   ├── event/                            # Event handling
│   │   ├── NetexImportEventFactory.java
│   │   └── NetexImportEventKeyFactory.java
│   ├── pubsub/
│   │   └── PubSubAutoCreateEventNotifier.java
│   └── exceptions/
│       └── NisabaException.java
├── src/main/avro/
│   └── NetexImportEvent.avsc             # Avro schema definition
├── src/main/resources/
│   └── logback.xml                       # Logging configuration
├── helm/nisaba/                          # Kubernetes deployment charts
├── Dockerfile                            # Container image definition
└── pom.xml                               # Maven project configuration
```

### Key Components

#### Main Route: NetexImportNotificationQueueRouteBuilder

The core workflow is implemented in `NetexImportNotificationQueueRouteBuilder` with the following routes:

- **netex-export-notification-queue**: Main entry point, receives PubSub notifications
- **download-netex-dataset**: Downloads the dataset from GCS
- **retrieve-dataset-creation-time**: Parses XML files to extract creation dates
- **parse-created-attribute**: XPath-based extraction of the `created` attribute
- **notify-consumers-if-new**: Checks idempotency and triggers notification
- **notify-consumers**: Publishes to Kafka topic
- **find-chouette-import-key**: Identifies the original Chouette dataset for mixed sources
- **copy-dataset-to-private-bucket**: Copies whitelisted datasets to a private bucket

#### Blob Store Services

Three service implementations handle access to different GCS buckets:
- **MardukBlobStoreService**: Accesses Marduk's export bucket (`marduk-{env}`)
- **NisabaBlobStoreService**: Manages Nisaba's own storage
- **NisabaExchangeBlobStoreService**: Handles exchange bucket for imported datasets

#### Event Model

The Avro schema (`NetexImportEvent.avsc`) defines the Kafka event structure:
```json
{
  "codespace": "string",           // Dataset codespace
  "importDateTime": "string",      // ISO-formatted local datetime
  "importKey": "string",           // Unique key: <codespace>_<datetime>
  "publishedDatasetURI": "string", // GCS link to published dataset
  "publishedDatasetPublicLink": "string", // Public HTTPS link
  "originalDatasetURI": "string",  // GCS link to original dataset
  "serviceJourneys": "int",        // Obsolete, always 0
  "commonFiles": "int"             // Obsolete, always 0
}
```

## Data Flow

### Complete Processing Pipeline

1. **Notification Received**
   - PubSub message received with dataset codespace
   - Correlation ID set for request tracing

2. **Dataset Download**
   - Constructs file path: `outbound/netex/rb_<codespace>-aggregated-netex.zip`
   - Downloads from GCS bucket (e.g., `marduk-production`)

3. **Creation Date Extraction**
   - Unzips archive and iterates through XML files
   - Uses XPath to extract `@created` attribute from `CompositeFrame` elements
   - Aggregates all creation dates into a sorted set
   - Selects the latest date as the dataset creation time

4. **Idempotency Check**
   - Generates unique key: `<codespace>_<creation_date>` (e.g., `avi_2021-04-21T11:51:59`)
   - Checks against Kafka-backed idempotent repository
   - History retained for 365 days

5. **Event Publication**
   - If new dataset: publishes Avro-encoded event to Kafka topic
   - Kafka key: dataset codespace
   - Topic naming: `rutedata-dataset-import-event-{env}`

6. **Optional Private Copy**
   - For whitelisted codespaces, copies dataset to private bucket

### NeTEx Dataset Structure

- **File naming convention**: `rb_<codespace>-aggregated-netex.zip`
- **Creation date source**: `created` attribute in NeTEx CompositeFrame
- **Mixed datasets**: When combining Chouette and Uttu exports, uses maximum creation date
- **Chouette identification**: Looks up original import in exchange bucket to find Chouette source

## Configuration

### Environment-Specific Settings

The application supports multiple environments (dev, test, production) with corresponding:
- GCS buckets: `marduk-{env}`, storage varies by environment
- Kafka topics: `rutedata-dataset-import-event-{env}`
- PubSub queues: Environment-specific subscriptions

### Key Configuration Properties

- `marduk.pubsub.project.id`: GCP project for PubSub
- `nisaba.kafka.topic.event`: Kafka topic for publishing events
- `nisaba.netex.publication.internal.bucket`: Private bucket for whitelisted datasets
- `nisaba.netex.publication.internal.whitelist`: Codespaces eligible for private copy
- `nisaba.shutdown.timeout`: Graceful shutdown timeout (default: 300s)

## Deployment

### Containerization

- Base image: `bellsoft/liberica-openjre-alpine:21.0.9`
- Multi-stage build for optimized image size
- Layer extraction for efficient caching
- Non-root user (`appuser`) for security
- Init system: `tini` for proper signal handling

### Kubernetes

- Helm charts in `helm/nisaba/`
- Supports clustered deployment with leader election
- Uses Camel's `master:` component for singleton routes
- Kubernetes cluster service for leader election

## Development

### Building

```bash
mvn clean install
```

### Running Tests

```bash
mvn test
```

### Key Dependencies

- `camel-spring-boot-starter`: Camel framework integration
- `camel-google-pubsub-starter`: Google PubSub consumer
- `camel-kafka-starter`: Kafka producer
- `camel-zipfile-starter`: ZIP file processing
- `camel-xpath-starter`: XML parsing
- `entur-google-pubsub`: Entur's GCP helpers
- `storage-gcp-gcs`: GCS blob storage access
- `netex-java-model`: NeTEx data model
- `kafka-avro-serializer`: Confluent Avro serialization

### Testing

Test infrastructure includes:
- `NisabaRouteBuilderIntegrationTestBase`: Base class for integration tests
- Testcontainers for GCloud emulation
- Spring Boot test support
- Camel test framework

## Related Projects

- **[Marduk](https://github.com/entur/marduk)**: Publishes NeTEx export events
- **[Chouette](https://github.com/entur/chouette)**: Initial dataset import and creation date generation
- **[Uttu](https://github.com/entur/uttu)**: FlexibleLines management with creation date generation

## Monitoring and Observability

- **Actuator endpoints**: Health checks and metrics via Spring Boot Actuator
- **Prometheus metrics**: Exposed via Micrometer registry
- **Structured logging**: Logstash-compatible JSON logging with Logback
- **MDC logging**: Camel correlation IDs for request tracing
- **Message history**: Enabled for route debugging

## Important Notes

- **Idempotency**: Critical for preventing duplicate event publication
- **Leader election**: Required in clustered deployments to prevent race conditions
- **Graceful shutdown**: 5-minute timeout ensures in-flight messages complete
- **Creation date semantics**: For mixed datasets, uses maximum of all CompositeFrame dates
- **Historical retention**: 365-day window in idempotent repository
