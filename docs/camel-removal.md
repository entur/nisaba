# Removing Apache Camel from nisaba

What changed, why, and what to watch when it deploys. Written for whoever has to operate or extend nisaba
next. The reference for the method is antu's [camel-removal.md](https://github.com/entur/antu/blob/main/docs/camel-removal.md)
and [camel-migration-learnings.md](https://github.com/entur/antu/blob/main/docs/camel-migration-learnings.md);
their rules (pin the wire contract with literals, inventory what Camel did implicitly, read the deployed
config, remove nothing from helm in the removal release, verify library claims against the jar) were applied
here and are not repeated.

The goal was narrow: take Camel out and keep every externally observable behaviour. Everything that changes
behaviour is listed in *Behaviour changes*, with the reason.

## Why

Camel did five jobs in nisaba: the PubSub consumer, Kubernetes leader election around it, the zip and XPath
extraction of the dataset creation date, the Kafka-backed `idempotentConsumer`, and the two Kafka clients
(the Avro event producer and the reader that fills the import-date map behind the public REST API).

Only one of those was hard, and it was hard for the wrong reason. Camel's `KafkaIdempotentRepository` is a
per-pod cache (an LRU of 1000 keys, synced from the topic 100 ms behind) that decides locally, so two pods
could both publish an event for the same dataset. That is the only reason the consumer ran under a
`master:` lock, and that lock is what failed in staging on 2026-10-14/15: Camel bug CAMEL-25209 silently
ended the leader-election loop after one Kubernetes API blip, no pod took over, pods stayed Ready, and
notifications piled up for ten hours. The fix exists only in Camel 4.23, not in the 4.10 line nisaba is on.

The XPath was the other cost: a DOM of every NeTEx file, some of them hundreds of megabytes, to read one
attribute near the top, on top of a blob helper that already materialises the whole archive in memory.

## The shape of it now

```
marduk ──▶ NetexExportNotificationQueue (Marduk's project)
                     │  one subscriber per pod, one message at a time, both pods
                     ▼
        NetexExportNotificationConsumer ──▶ Redelivery (3× in process) ──▶ NetexImportPipeline
                                                                               │
              download rb_<cs>-aggregated-netex.zip ◀──────────────────────────┤
              created of every XML file (StAX) ────────────────────────────────┤
              claim <cs>_<created>  ──▶ idempotent topic (add / remove) ◀──────┤   atomic across pods
              Chouette key: exist(imported/<cs>/<key>.zip) ────────────────────┤
              publish NetexImportEvent ──▶ rutedata-dataset-import-event-<env> ─┘
                                                     │
                                                     ▼
                                      ImportDateMapReader (every pod, from the beginning)
                                                     │
                                                     ▼
                                      GET /timetable-import-info/import_date
```

There is no leader and no shared in-memory state. The one thing that has to be decided once across pods,
"is this import key new", is decided by the order of records in the idempotent topic.

## Replacement table

| Was | Is now | Note |
| --- | --- | --- |
| `master:...:google-pubsub:...` route, Kubernetes Lease election | `NetexExportNotificationConsumer` on `AbstractEnturGooglePubSubConsumer` (entur-google-pubsub), every pod | No Kubernetes API access; `rbac.yaml` kept this release, removed in the follow-up |
| `idempotentConsumer` + `KafkaIdempotentRepository` | `KafkaClaimRepository` | Same topic, same record format, plus one header; atomic claim, see below |
| `BaseRouteBuilder` error handler (3 redeliveries 5/15/45 s) | `Redelivery` | Whole message re-run instead of the failing step; abortable at shutdown |
| `split(new ZipSplitter())` + camel-xpath DOM | `DatasetCreationTimeExtractor` + `CompositeFrameCreatedParser` (JDK StAX) | Stops at the first CompositeFrame; DTD and external entities refused |
| `find-chouette-import-key` split with `getBlob` | `ChouetteImportKeyResolver` with `exist()` | Metadata call instead of downloading each candidate archive |
| `kafka:` producer with `KafkaAvroSerializer` | `KafkaNetexImportEventPublisher` (plain `KafkaProducer`) | Same key, value and header |
| `kafka:` consumer `seekTo=beginning` | `ImportDateMapReader` on `AssignedTopicPoller` | No consumer group, replay complete before readiness |
| Camel MDC, `camel.breadcrumbId` | `ImportMdc` (`codespace`, `correlationId`) | Log prefix `[codespace=... correlationId=...] ` kept verbatim |
| `nisaba.shutdown.timeout=25` (Camel shutdown strategy) | `InFlightMessages` (`SmartLifecycle`) | See *Shutdown* |
| `PubSubAutoCreateEventNotifier` (profile `google-pubsub-autocreate`) | `entur.pubsub.subscriber.autocreate` | `false` in helm and in tests; destinations are terraformed |
| `camel.component.kafka.*` | `spring.kafka.*` | SASL and schema registry configured once for all clients |
| `jacksonJavaTimeModule` bean, `camel.dataformat.jackson.module-refs` | nothing | Only Camel's data format read it; Boot registers the module itself |

Removed from the build: eleven `camel-*` artifacts, `camel-test-spring-junit5`, `woodstox-core` (never used: Camel's
XPath parsed with the JDK DOM) and the fabric8 Kubernetes client they dragged in. Added: `spring-kafka` (only
for Boot's `spring.kafka.*` properties), `spring-boot-starter-validation` (the generated OpenAPI interfaces
need `jakarta.validation`, which Camel had been providing transitively), `spring-kafka-test` and `awaitility`
for the tests.

### What stayed deliberately identical

The wire. The subscription name `NetexExportNotificationQueue`; the blob paths `outbound/netex/rb_<cs>-aggregated-netex.zip`
and `imported/<cs>/<key>.zip`; the import key `<cs>_<yyyy-MM-ddTHH_mm_ss[.SSS]>`; the Kafka event (key =
codespace, Avro `NetexImportEvent`, one record header `EnturDatasetImportKey`, `acks=all`); the idempotent
topic records (`key = <import key>`, `value = "add" | "remove"`, String serializers); every log line of the
old routes, with its prefix; the REST API. `WireContractTest` and `KafkaWireContractTest` assert these as
literals, never through the constants that hold them, and `CamelReaderCompatibilityTest` feeds the records
the new version writes through a copy of Camel's `addToCache` logic.

## The claim protocol

A claim writes `(key, "add")` to the idempotent topic with a `nisaba-claim-id` header and then waits until
its own record has been read back by its poller. Kafka orders the records of one key within one partition,
so "the first `add` since the last `remove` wins" is the same answer on every pod, and nothing else needs
to be coordinated. The outcome is decided at the moment the poller applies the claim's own record, not by
reading the state afterwards, so a `remove` that lands right after it cannot hide a win.

The Camel reader ignores record headers and decides on `CacheAction.valueOf(value)`, so the version still
deployed during a rolling update, and a rollback, read these records as before. Two things must never be
written: a record with a null value (Camel's startup replay NPEs on it and the pod does not start) and
`clear` (null key; a compacted topic rejects it anyway).

A log alone leaves two holes, both of which Camel covered by accident (its `remove()` updated the local cache
before broadcasting, and only one pod ever consumed). A per-JVM registry of the claims this pod issued closes
them:

- **The publish failed and the release could not be written either** (a Kafka outage). The retry on the same
  pod finds its own claim in the registry, in state `OWNED`, and re-owns it instead of writing a second
  claim that it would then lose to itself and skip. The release is retried in the background until it lands
  or the pod stops.
- **The claim timed out** (the add was acknowledged by the broker, the poller is merely late). The claim is
  marked abandoned; a retry on the same pod re-attaches to it and waits again; if nobody does and it turns
  out to have won, it is released, so that the key does not stay dead.

- **The producer gave up on the add** (it is bounded by `delivery.timeout.ms` = the send timeout). The
  caller fails at once, but the record may have landed with its acknowledgement lost (a request in flight
  when the timeout expires can still be appended by the broker), so the claim is kept, abandoned, and keeps
  listening for its record. If it is read back and won, a retry on the same pod re-owns it, or it is released
  as above. Once the poller has read everything the topic held a send timeout after the failure (a barrier
  of the poller, `caughtUp()`), a record not seen is one that was never written: the claim is forgotten and
  the next attempt claims fresh.
- **Two callers for one key on one pod** (not possible with one callback thread per pod, but the class does
  not rely on that): the key is held by one caller at a time; the second waits for the first to confirm
  (then it is a duplicate) or release (then it claims fresh).

Both have tests that fail without the registry (`KafkaClaimRepositoryTest`, mutation-tested). A new `add`
for a key is only ever written after the previous claim's `remove` has been acknowledged by the broker, so
within one pod a retried `remove` can never land on top of a newer `add`; across pods a `remove` written by
this version only removes the claim it releases (the Camel version's removes, which carry no claim id, are
applied unconditionally), so a `remove` whose acknowledgement was lost and that was sent again cannot delete
another pod's newer claim. A release that fails is retried until it lands or the pod stops, backing off
from the poll timeout to 30 s; nothing is given up on, because a cause the client calls non-retriable (an
authorization failure, say) can still clear and a dead claim is a lost dataset. On shutdown, every release
still being retried is sent once more before the producer closes; a claim still held by a notification the
drain gave up on is waited for briefly (the event producer closes at the same phase and fails its publish),
then released, so that the redelivery can publish the event (or, if that publish did succeed, publish it a
second time: the lesser harm); a release arriving while the repository stops is sent on the caller's thread.

What the protocol does not cover is in *Known open*.

## Behaviour changes

Everything below is a deliberate difference from the Camel implementation.

1. **No leader election; both pods consume.** One notification at a time per pod
   (`executor-threads=1`, `max-outstanding-element-count=1`). Under Camel the leader's single gax subscriber
   ran with the gax defaults, up to five callbacks at once.
2. **The dedup horizon is the whole retained topic**, not the 1000 most recently seen keys. A dataset
   re-exported with an unchanged `created` more than 1000 keys later would have got a second event; the
   key's documented intent is that it does not. Memory cost is a few megabytes a year.
3. **StAX, stop at the first CompositeFrame.** Same result as the XPath for a well-formed file; a file that
   is malformed after that element is no longer rejected; DTDs and external entities are refused; memory
   is a parser buffer instead of a DOM. Camel's non-XML-entry branch would in fact have thrown
   `ClassCastException` in its aggregation (never hit: Marduk's archives contain only XML); the documented
   intent, skip with an INFO line, is what happens now.
4. **The Chouette lookup uses `exist()`**, a metadata call, instead of downloading and MD5-checking every
   candidate archive. The INFO line `File '...' in bucket '...' does not exist` the helper used to log on a
   miss is logged by the resolver with the same wording.
5. **Local retries re-run the whole message** (download again) rather than the failing step; the claim is
   released before each retry, or re-owned when the release did not land. A retry's wait is cut short when the
   context starts closing. The consumer base class sleeps `entur.pubsub.consumer.retry.delay` after a nack;
   it is set to 1 s instead of the library's 15 s, because PubSub's own backoff (10 s to 600 s) governs
   redelivery anyway and the sleep runs inside the shutdown budget.
6. **The PubSub body is trimmed; a blank body is discarded** (ERROR line, `outcome=invalid`, acked) instead
   of looking up `rb_-aggregated-netex.zip`. A nack would redeliver it forever: the subscription has no
   dead-letter topic.
7. **The correlation id attribute is honoured when present.** Camel's `interceptFrom("google-pubsub:*")`
   never matched the `master:`-prefixed route, so it never was; and Marduk's `netex-notify-export` route
   calls `removeHeaders("*")` before publishing, so there is none on the wire today. A no-op kept for its
   intent.
8. **Log fields.** The JSON field `breadcrumb` now carries the correlation id (it carried Camel's random
   per-exchange id); `codespace` is added. The Camel WARN for an unknown record value is kept but now prints
   both the value and the key (Camel printed the key under the label "value"). The import-date reader's
   `Received notification event from <topic>` line now has the codespace in its prefix (under Camel the
   header filter dropped every Kafka header, so it read `[codespace= correlationId=]`). The
   `File '...' in bucket '...' does not exist` line keeps its text but is logged by
   `ChouetteImportKeyResolver` instead of the GCS helper, and also for the in-memory blob store.
9. **Kafka producers run with `enable.idempotence=true`**, and the idempotent-repository producer with
   `acks=all` (Camel used `acks=1`, which silently disabled idempotence). The protocol reads its own record
   back, so an acknowledged-but-lost `add` would only time the claim out.
10. **A poison record on the event topic is skipped** (ERROR, seek past it) instead of blocking the reader.
11. **Local retry back-off is capped at 60 s** like Camel's `maximumRedeliveryDelay`; with the default of
    three retries the cap is never reached.
12. **Client ids are fixed** (`nisaba-idempotent-repo`, `nisaba-event`, `nisaba-event-reader`) and the
    durability settings of the two producers (`acks=all`, `enable.idempotence=true`, delivery timeout = send
    timeout) are forced, whatever `spring.kafka.producer.*` says; an override is logged and ignored. Camel let
    the producer run for `delivery.timeout.ms` = 120 s while the route waited; now a publish that throws has
    not written the event, so the retry cannot write a second one.
13. **An `Error` thrown while processing a message is nacked.** The consumer base class catches only
    `Exception` (Camel's receiver did the same), so a `StackOverflowError` from a pathological archive would
    leave the message neither acked nor nacked, leased for up to the ack extension, with this pod's one
    callback slot blocked meanwhile. It is counted as `failed` and rethrown wrapped in a `RuntimeException`.

## Shutdown

SIGTERM → `ContextClosedEvent`: `NetexExportNotificationConsumer` trips the drain's latch (so a local retry
sleeping in the callback wakes and nacks) and then lets the entur base class close the subscriber, which
waits up to 10 s for the running callback (always the full 10 s while a message is being processed). Then `SmartLifecycle.stop()`
in phase order: `InFlightMessages` (phase `MAX_VALUE`) drains for up to
`nisaba.shutdown.drain.timeout.seconds`; spring-cloud-gcp's schedulers (`MAX_VALUE/2`, bounded by
`spring.lifecycle.timeout-per-shutdown-phase`); the Kafka beans (phase 0) close their pollers and producers
with 5 s timeouts. Budget: 10 + 45 + 5 + 4 × 5 = 80 s against `terminationGracePeriodSeconds: 90`
(the chart default was 30; Camel's `nisaba.shutdown.timeout=25` sat under it). A drained message's ack is
delivered: gax waits for the callback in its own thread and then flushes outstanding acks.

`ShutdownOrderTest` pins that the drain is the highest phase and that the per-phase timeout does not bound
it; if that ever changed, the drain would silently cap at 5 s.

## Operating it

| Property | Default | What it does |
| --- | --- | --- |
| `nisaba.pubsub.consumer.enabled` | `true` | Starts the PubSub consumer (tests set `false`) |
| `entur.pubsub.subscriber.autocreate` | `true` in the library, **`false` in helm** | Must be false: the consumer subscribes with a fully-qualified name in Marduk's project, which the autocreate path cannot create |
| `entur.pubsub.consumer.retry.delay` | 15000 in the library, 1000 in helm | Sleep inside the callback after a nack |
| `spring.cloud.gcp.pubsub.subscriber.executor-threads` | 4 in the library, 1 in helm | Callback threads per pod |
| `spring.cloud.gcp.pubsub.subscriber.flow-control.max-outstanding-element-count` | 1000 in gax, 1 in helm | Messages leased per pod |
| `spring.cloud.gcp.pubsub.subscriber.max-ack-extension-period` | **0 in spring-cloud-gcp, which disables deadline extension**; 3600 in helm | A notification that takes longer than the 60 s ack deadline would otherwise be redelivered mid-processing. `SubscriberPropertiesTest` pins that the value reaches the subscriber factory |
| `nisaba.redelivery.max` / `.delay.millis` / `.backoff.multiplier` | 3 / 5000 / 3 | Local retries before the nack: 5 s, 15 s, 45 s |
| `nisaba.shutdown.drain.timeout.seconds` | 45 | Finish the notification in flight rather than redeliver it |
| `spring.lifecycle.timeout-per-shutdown-phase` | 30s in Boot, 5s in helm | Bounds the other lifecycle phases, not the drain |
| `nisaba.kafka.topic.event` / `.idempotent` | | The two topics |
| `nisaba.kafka.claim-timeout` / `send-timeout` | 30s / 30s | A claim is `claim-timeout` after its `send-timeout` at worst |
| `nisaba.kafka.replay-timeout` | 2m | Start-up replay of each topic; two replays run one after the other and must fit the 300 s startup probe |
| `nisaba.kafka.poll-timeout` | 1s | Consumer poll, and the first interval of a failed release's retries (doubling up to 30 s) |
| `spring.kafka.*` | | Brokers, `security.protocol`, `properties.sasl.*`, `properties.schema.registry.url`, `properties.basic.auth.*`; shared by the three clients |

Kafka clients and their ids, as the broker sees them: `nisaba-idempotent-repo` (producer and consumer of
the idempotent topic), `nisaba-event` (event producer), `nisaba-event-reader` (event consumer). Both
consumers assign their partitions and use no consumer group, as Camel's repository did; the event reader
used to join a random-UUID group, so it now needs strictly fewer rights. Partitions added to a topic at
runtime are not seen until a restart (unchanged).

Health: `/actuator/health` has a `kafkaPoller` contributor that reports DOWN when a poller thread has died
(a claim then fails at once instead of timing out). It is deliberately not in the liveness group in this
release; see *Known open*. Metrics: `nisaba_notifications_total{outcome=published|duplicate|dataset_missing|invalid|failed}`;
`failed` is counted once per message, when the local retries are exhausted and the message is nacked;
`invalid` is a message without a codespace, acked.

Running locally: the `in-memory-blobstore` and `local-disk-blobstore` profiles need `commons-io`, which the
pom scopes to tests on purpose (the GCS repository does not use it), so a local run of the packaged jar
takes it on the loader path: `java -cp nisaba.jar -Dloader.path=<commons-io.jar>
org.springframework.boot.loader.launch.PropertiesLauncher --spring.profiles.active=in-memory-blobstore ...`,
with a Kafka broker and the two topics created beforehand (`allow.auto.create.topics` is off).

Logging: every pipeline line keeps the `[codespace=<cs> correlationId=<id>] ` prefix; the MDC carries both,
and the JSON log has `breadcrumb` (the correlation id, field name kept for saved queries) and `codespace`.
The MDC is cleared on entry to the message handler, not in a `finally`, so the base class's
`Message processing failed, retrying in ...` ERROR line after a failure still carries the identity.

The idempotent topic can be read with the kcat recipe in the team's notes: values are `add` and `remove`,
the header `nisaba-claim-id` says which pod wrote a record (`<pod name>:<uuid>`). A dead claim can be lifted
by hand by producing `<key> remove` with kcat.

## Deploying it

Deploy dev → tst → prd, outside the nightly export window (02:30 to 05:00 Europe/Oslo) with the
subscription's `num_undelivered_messages` at 0 and held at 0 for the whole rollout. With `maxSurge: 25%`
and two replicas, a new pod joins before an old one leaves, so for some minutes an old leader (local-cache
check, up to five concurrent callbacks) and new pods (atomic claim) share the subscription and the topic. A
duplicate event needs the same import key in flight on both versions at once, which only an ack-deadline
redelivery or a double Marduk notification produces; the old pod's unguarded `remove` on a failure can
delete a new pod's live claim in the same window. Accepted for the rollout window; verify afterwards that the
event topic has one event per import key.

Nothing downstream has to change: the event wire format is identical. There is no HPA (`forceReplicas: 2`),
so antu's autoscaling re-derivation does not apply.

**Nothing is removed from helm in this release.** `rbac.yaml`, every `camel.*` key, `nisaba.shutdown.timeout`
and `camel.component.google-pubsub.*` stay, marked in the ConfigMap. helm applies the ConfigMap before the
first pod is replaced and kubelet re-syncs it into running pods, so an old pod restarting mid-rollout boots
the old jar against the new file; without `camel.cluster.kubernetes.enabled` that jar fails with
`IllegalStateException("No cluster service found")`. The new keys are ignored by the old jar.

| Rollback | Works | Why |
| --- | --- | --- |
| `git revert` + full redeploy | yes | ConfigMap, values, pom and RBAC revert together |
| `helm rollback` | yes | Restores ConfigMap and image from one manifest |
| image-only rollback, `kubectl rollout undo` | yes, **only because the old keys were kept** | Restores the pod template only; the ConfigMap is a separate object |

Camel reads the new records (headers ignored; `CamelReaderCompatibilityTest`). Its LRU truncates to 1000
keys again, as before.

Follow-up PR once production has run a week on this version: delete `rbac.yaml`, the `camel.*` keys,
`nisaba.shutdown.timeout` and `camel.component.google-pubsub.*`; delete the Lease
`nisaba-leaders-lock-on-netex-export-notification-queue-route` (and any `nisaba-leaders` ConfigMap) by hand
in each namespace; decide whether `kafkaPoller` joins the liveness group.

After the first deploy in each environment: both pods log `Received NeTEx export notification`; one event
per import key in the event topic; `/timetable-import-info/import_date` populated after a restart; no
`GroupAuthorizationException` or `TopicAuthorizationException` in the logs; delete a pod while it is
processing a notification once, in dev, and watch `Waiting up to 45 s for 1 message(s) still being processed`
followed by `Finished the in-flight messages, shutting down`.

## Testing it

The old suite booted a Camel context and a PubSub emulator for every scenario: 17 tests in 1 min 36 s. Now
about a hundred tests in about a minute and a half, almost all of it the embedded Kafka broker and the one
emulator:

- Plain unit tests for the parser, the archive extraction, the Chouette lookup, the key and event factories,
  the notification record, the redelivery schedule, the drain, the MDC, and the pipeline's decision tree
  with hand-written doubles.
- `KafkaClaimRepositoryTest` runs the protocol against an embedded KRaft broker, one topic per test. The
  interleavings are constructed, not raced: the poller has a pause hook that returns once the thread is
  parked, and a wrapped producer can be made to fail every send. Scenarios: replay of a Camel-written
  history including `clear` and a bogus value; two pods racing for one key; release then claim; `clear`;
  three partitions and a fresh replay; an abandoned claim released; a retry re-attaching to a claim in
  flight; a release that fails and is retried while the next attempt waits; an add the producer gives up
  on, forgotten once the poller has read past it, with a retry waiting for that; an add that landed with its
  acknowledgement lost, released, or re-owned by a retry; a claim still held at stop, released; a release
  arriving during stop; two callers for one key on one pod; stop joining the poller and leaving no client
  MBeans. Every
  scenario ends with an empty registry and no pending future, asserted after each test. Replay of 20 000 records over three partitions: 125 ms. Claim latency over 200
  claims on the embedded broker: p50 4 ms, p99 6 ms. The four Kafka test classes were run twenty times in a
  row on a copy of the tree: 20 of 20 green.
- Mutation checks, run and recorded: deciding the claim right after the add is written (Camel semantics)
  fails the race test; removing the local registry fails the re-attach, failed-release and one-holder tests;
  removing `release()` from the pipeline fails the pipeline tests; dropping `max-ack-extension-period` from
  the test properties fails `SubscriberPropertiesTest` (which uses non-default values, including
  `executor-threads=1`, so it cannot pass vacuously).
- Spring tests without Docker: the pipeline as Spring wires it with the in-memory blob store and
  `KafkaTestDoubles` (an in-memory claim repository and a recording publisher), the shutdown order, the
  subscriber properties. The Kafka beans are `@Profile("!test")`; the doubles are imported explicitly by each
  test, and `TestApp` keeps `TypeExcludeFilter` so that no `@TestConfiguration` leaks between tests.
- `PubSubWiringTest` is the only test that starts an emulator, and it checks only the transport: it creates
  the topic and the subscription itself (the consumer never does), publishes a codespace with a correlation
  attribute and waits for the recording publisher.

Run with `GOOGLE_APPLICATION_CREDENTIALS=/dev/null`: nothing needs credentials.

## Known open

Ranked by consequence, none blocking:

1. **A pod killed between claim and publish** leaves the key claimed with no event, and the redelivery is
   skipped. Identical to Camel's eager `add()` then crash. Lift by hand with kcat (`<key> remove`); the next
   import of that dataset has a new key anyway.
2. **A release that has not landed when the redelivery goes to the other pod** is skipped there (foreign
   owner). Once the release lands the key is absent again, so the next nightly re-export of the same
   dataset (same `created`, same key) publishes it: delayed by up to a day, not lost. Needs a Kafka outage
   longer than the three local retries (65 s) first.
3. **A `publish()` that times out after the broker durably wrote the event** → release → retry → a second
   event. Same as Camel redelivering `to-kafka-topic-event`; producer idempotence does not cover a new
   `send()`.
4. **`kafkaPoller` is not in the liveness group.** A dead poller thread (only an uncaught `Error` kills it;
   exceptions from `poll()` are logged and the loop goes on, as Camel's did) makes every claim fail at once
   and every notification nack forever; a restart is the fix, but a false DOWN would restart both pods, so
   it is observed first.
5. **No alert on `num_undelivered_messages` / `oldest_unacked_message_age`** for the subscription: the
   October outage went unnoticed for ten hours and nothing in this change watches for the next one.
6. **The Apigee proxy** (`api/timetable-import-info`) targets `/services/timetable-import-info`, the old Camel
   REST path, while the Spring controller serves `/timetable-import-info/...` without a context path. Unrelated
   to this change, noticed while reading.
7. **No `@Scheduled` task exists.** If one is added, note that spring-cloud-gcp registers its own
   `ThreadPoolTaskScheduler` beans, so Boot's `TaskSchedulingAutoConfiguration` backs off and
   `spring.task.scheduling.*` means nothing without a `taskScheduler` bean (antu's lesson).
