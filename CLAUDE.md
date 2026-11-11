# Nisaba - Project Overview

## Purpose

Nisaba is a Java Spring Boot application that monitors NeTEx (Network Timetable Exchange) dataset exports and publishes Kafka events when new timetable datasets become available. It acts as a bridge between Marduk's dataset exports and downstream consumers that need to be notified of new timetable data.

## Core Functionality

Nisaba implements an event-driven workflow that:

1. **Consumes** the Google PubSub subscription `NetexExportNotificationQueue` (in Marduk's project) for NeTEx export notifications from [Marduk](https://github.com/entur/marduk)
2. **Downloads** the NeTEx dataset from Google Cloud Storage when notified
3. **Extracts** dataset creation dates from the `created` attribute of the CompositeFrame of every XML file in the archive
4. **Deduplicates** by claiming the import key in a GCS-backed idempotent repository (one object per key, created only if absent); the claim is atomic across pods
5. **Publishes** a Kafka event only for a new dataset (identified by codespace + creation date)
6. **Serves** the latest import date per codespace over a public REST API, fed by a reader of the event topic

Apache Camel was removed in November 2026; see `docs/camel-removal.md` for the design, the behaviour changes and the operating notes, and `docs/gcs-idempotent-repository.md` for the idempotent repository. There is no leader election any more: both pods consume the subscription.

## Technology Stack

- **Java 21** with Spring Boot 3.4 (superpom 4.10.0)
- **Google Cloud Platform**
  - Cloud Storage (GCS) for dataset storage (`storage-gcp-gcs` helper)
  - PubSub for notifications (`entur-google-pubsub` helper over spring-cloud-gcp)
- **Apache Kafka** (plain Kafka clients configured through Spring Boot's `spring.kafka.*`) with Confluent Avro serialization
- **Maven** for build management
- **Docker** for containerization
- **Helm** for Kubernetes deployment

## Architecture

### Project Structure

```
nisaba/
├── src/main/java/no/entur/nisaba/
│   ├── App.java                          # Spring Boot application entry point
│   ├── Constants.java                    # Wire-level constants (attribute name, paths, Kafka header, date format)
│   ├── config/                           # Blob store configurations, Avro class security, import-date map
│   ├── pubsub/                           # PubSub consumer, notification record, local redelivery, shutdown drain
│   │   ├── NetexExportNotificationConsumer.java
│   │   ├── NetexExportNotification.java
│   │   ├── Redelivery.java
│   │   └── InFlightMessages.java
│   ├── pipeline/                         # What happens to one notification
│   │   ├── NetexImportPipeline.java
│   │   ├── DatasetCreationTimeExtractor.java / CompositeFrameCreatedParser.java
│   │   ├── ChouetteImportKeyResolver.java
│   │   ├── ImportMdc.java
│   │   └── NetexImportMetrics.java
│   ├── idempotent/                       # Idempotent repository on GCS
│   │   ├── IdempotentRepository.java / GcsIdempotentRepository.java
│   │   ├── MarkerStore.java / GcsMarkerStore.java / InMemoryMarkerStore.java
│   │   └── migration/                    # One-shot copy of the former Kafka idempotent topic into the bucket (IdempotentTopicState, IdempotentTopicContract)
│   ├── kafka/                            # Event publisher, import-date reader, topic poller
│   │   ├── AssignedTopicPoller.java
│   │   ├── KafkaNetexImportEventPublisher.java / PublishedImportKeys.java
│   │   ├── ImportDateMapReader.java
│   │   └── NisabaKafkaConfig.java / NisabaKafkaProperties.java
│   ├── services/                         # Blob store service implementations
│   ├── event/                            # NetexImportEventFactory, NetexImportEventKeyFactory
│   └── rest/                             # Public REST API (Spring MVC, OpenAPI-generated interface)
├── src/main/avro/
│   └── NetexImportEvent.avsc             # Avro schema definition
├── src/main/resources/
│   ├── logback.xml                       # Logging configuration
│   └── openapi/openapi.yaml              # Public API specification
├── docs/camel-removal.md                 # Design and operating notes of the Camel-free version
├── docs/gcs-idempotent-repository.md     # Idempotent repository: design, provisioning, migration, operations
├── helm/nisaba/                          # Kubernetes deployment charts
├── terraform/                           # GCS bucket of the idempotent repository and its IAM binding
├── Dockerfile                            # Container image definition
└── pom.xml                               # Maven project configuration
```

### Key Components

- **NetexExportNotificationConsumer**: subscribes to Marduk's subscription with its fully-qualified name, one message at a time per pod; acks on return, nacks on exception.
- **Redelivery**: three in-process retries (5 s, 15 s, 45 s) before the nack, as the Camel error handler did; abortable at shutdown.
- **NetexImportPipeline**: download, creation-time extraction (StAX, stops at the first CompositeFrame), import key, claim, Chouette key lookup, event, publish, confirm; release of the claim on failure.
- **GcsIdempotentRepository** over **GcsMarkerStore**: the idempotent repository. A claim creates the object `<import key>` in the bucket with `ifGenerationMatch=0`; confirm sets `confirmed=true`, release deletes, both conditioned on the generation. An unconfirmed claim older than `nisaba.idempotent.stale-after` (15 min) is taken over. No thread, no start-up replay.
- **IdempotentRepositoryMigrationApp**: command line tool in the jar that replays the former Kafka idempotent topic and creates the missing markers; re-runnable.
- **ImportDateMapReader**: replays the event topic from the beginning on every start (the topic is compacted) into the map behind the REST API.
- **InFlightMessages**: `SmartLifecycle` drain that lets a notification finish during shutdown.

#### Event Model

The Avro schema (`NetexImportEvent.avsc`) defines the Kafka event structure:
```json
{
  "codespace": "string",           // Dataset codespace
  "importDateTime": "string",      // ISO-formatted local datetime
  "importKey": "string",           // Unique key: <codespace>_<datetime with ':' replaced by '_'>
  "publishedDatasetURI": "string", // GCS link to published dataset
  "publishedDatasetPublicLink": "string", // Public HTTPS link
  "originalDatasetURI": "string",  // Path of the Chouette import in the exchange bucket, or ""
  "serviceJourneys": "int",        // Obsolete, always 0
  "commonFiles": "int"             // Obsolete, always 0
}
```
The Kafka record key is the codespace; the one record header is `EnturDatasetImportKey`.

## Data Flow

1. **Notification received**: PubSub body = codespace; correlation id from the `RutebankenCorrelationId` attribute if present (Marduk sends none), else a fresh UUID; both go into the MDC and the `[codespace=... correlationId=...] ` log prefix.
2. **Dataset download**: `outbound/netex/rb_<codespace>-aggregated-netex.zip` from `marduk-{env}`; a missing file is logged as an error and the message is acked.
3. **Creation date extraction**: every `.xml` entry; the `created` attribute of the first CompositeFrame under `dataObjects`; a missing attribute counts as the epoch; the latest value identifies the import.
4. **Claim**: key `<codespace>_<creation date>` (e.g. `avi_2021-04-21T11_51_59`); a duplicate logs "An event has already been sent for this dataset. Skipping".
5. **Event publication**: Chouette import key looked up with `exist()` in the exchange bucket (`imported/<codespace>/<key>.zip`, the most recent existing one wins); Avro event to `rutedata-dataset-import-event-{env}`.

## Configuration

Environment-specific values live in `helm/nisaba/env/values-kub-ent-{dev,tst,prd}.yaml`; the ConfigMap template in `helm/nisaba/templates/configmap.yaml` renders `application.properties`.

Key properties (see `docs/camel-removal.md`, "Operating it", for the full table):

- `marduk.pubsub.project.id`: GCP project of the subscription
- `nisaba.kafka.topic.event`: the event topic; `nisaba.kafka.topic.idempotent`: the former idempotent topic, read by the migration tool only
- `nisaba.idempotent.gcs.bucket`, `nisaba.idempotent.stale-after`: the idempotent repository
- `spring.kafka.*`: brokers, SASL and schema registry, shared by every Kafka client
- `nisaba.redelivery.*`: local retry schedule; `nisaba.shutdown.drain.timeout.seconds`: shutdown drain
- `spring.cloud.gcp.pubsub.subscriber.*`: one message at a time, ack-deadline extension (must not be left at the spring-cloud-gcp default of 0)
- `entur.pubsub.subscriber.autocreate=false`: destinations are terraformed

The `camel.*` keys still in the ConfigMap are unused and kept for rollback until the follow-up release removes them.

## Deployment

- Base image: `bellsoft/liberica-openjre-alpine:21.0.13.0.3`, layered jar, `tini`, non-root user
- Helm charts in `helm/nisaba/`; two replicas, no HPA; `terminationGracePeriodSeconds: 90`
- No Kubernetes API access is needed any more (`rbac.yaml` is kept only until the follow-up release)

## Development

### Building and testing

```bash
mvn clean install
mvn test
```

Tests: plain unit tests, embedded-Kafka (KRaft) tests for the Kafka classes and the migration, Spring tests with in-memory blob store, in-memory marker store and a Kafka test double (`KafkaTestDoubles`), and two tests that need Docker: `PubSubWiringTest` (PubSub emulator) and `GcsMarkerStoreTest` (fake-gcs-server). `WireContractTest` pins the externally visible strings as literals.

### Key Dependencies

- `entur-google-pubsub`: consumer base class over spring-cloud-gcp PubSub
- `storage-gcp-gcs`: GCS blob storage access
- `spring-kafka`: only for Spring Boot's `spring.kafka.*` properties and the embedded test broker; the clients are plain `KafkaProducer`/`KafkaConsumer`
- `kafka-avro-serializer`: Confluent Avro serialization
- `avro`: the generated `NetexImportEvent`

## Related Projects

- **[Marduk](https://github.com/entur/marduk)**: Publishes NeTEx export events
- **[Chouette](https://github.com/entur/chouette)**: Initial dataset import and creation date generation
- **[Uttu](https://github.com/entur/uttu)**: FlexibleLines management with creation date generation
- **[antu](https://github.com/entur/antu)**: its `docs/` hold the Camel-removal lessons this project followed

## Monitoring and Observability

- **Actuator endpoints**: health (liveness/readiness), info, Prometheus metrics
- **Metrics**: `nisaba_notifications_total{outcome=published|duplicate|dataset_missing|invalid|failed}`; health contributor `kafkaPoller`
- **Structured logging**: Logstash JSON with `breadcrumb` (= correlation id) and `codespace` fields from the MDC

## Important Notes

- **Idempotency**: the claim is atomic across pods; a dataset whose publish failed is released so a redelivery can publish it
- **Graceful shutdown**: the notification in flight is finished (up to the drain timeout) before the pods stops
- **Creation date semantics**: for mixed datasets, the maximum of all CompositeFrame dates
- **History retention**: the lifecycle rule of the idempotent repository bucket (365 days)
