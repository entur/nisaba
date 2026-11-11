# The idempotent repository on Google Cloud Storage

Nisaba publishes one event per dataset, however many times Marduk exports it. The step that drops the
repeats is an idempotent receiver, and the store behind it is the idempotent repository: the set of import
keys (`<codespace>_<creation date>`) an event has been published for.

This document describes the GCS implementation that replaces the Kafka one of
[camel-removal.md](camel-removal.md), how to provision it, and how to migrate the existing history.

## Why

"Is this import key new" has to be decided once across pods. The Kafka implementation decided it with the
order of records in a topic: write `add`, wait until the pod's own record is read back, first one wins. That
is correct, but a log answers asynchronously, and most of `KafkaClaimRepository` (about 500 lines, a poller
thread, a registry of claims in flight, background retries, a drain at shutdown) existed to cover the gap
between "written" and "read back": claims abandoned on a timeout, acknowledgements lost, releases that could
not be written. And one hole stayed open: a pod killed between the claim and the publish left the key
claimed for ever and the dataset unpublished until someone produced a `remove` by hand.

GCS decides the same question in one synchronous request: an object created with the precondition
`ifGenerationMatch=0` is created only if it does not exist, atomically, whatever the number of writers. The
implementation is three small classes without a thread, a start-up replay or a lifecycle, and the dead-claim
hole is closed by a timed takeover.

## How it works

One object per import key in a dedicated bucket (`nisaba.idempotent.gcs.bucket`):

| | |
| --- | --- |
| name | the import key, e.g. `avi_2021-04-21T11_51_59.123` |
| content | the claim id, `<pod name>:<uuid>`, so that `gsutil cat` shows who wrote it |
| metadata `claim-id` | the same claim id |
| metadata `confirmed` | `true` once the event is published; absent while it is being published |
| generation | the ownership token: the confirm and the delete are conditioned on it |
| creation time | the age of an unconfirmed claim, by the server's clock |

The pipeline is unchanged: `claim` → publish → `confirm`, or `release` when the publish fails
(`NetexImportPipeline`).

- **claim**: create the object if absent. Created → the caller owns the key and publishes. Not created → read
  the object:
  - `confirmed` → duplicate, skip ("An event has already been sent for this dataset. Skipping");
  - unconfirmed and the claim of this pod whose release failed earlier → own it again;
  - unconfirmed and younger than `nisaba.idempotent.stale-after` (15 min) → another pod is publishing it, or
    died doing so: the claim fails (`ClaimInProgressException`) and the notification is retried, then nacked,
    until the claim is confirmed (duplicate), released (claimed) or stale (taken over). It is never
    acknowledged as a duplicate, which would lose the dataset if the claimer died;
  - unconfirmed and older → the claimer died or could not release: delete that generation and claim again.
    Of several pods doing this at once, one wins the create;
  - carries the claim id of this very call → the create succeeded and its answer was lost (the client
    library retried it and got "already exists"): own it;
  - gone → it was released meanwhile: claim again.
- **confirm**: set `confirmed=true` on that generation, up to four attempts (0.5 s, 1 s, 2 s apart): the GCS
  client retries a metadata update only when it carries a metageneration precondition, so the repository
  does it. Never throws: the event is out. If it still fails, the key
  is taken over after `stale-after` by the next export of the same dataset and the event is published a
  second time, which is the lesser harm.
- **release**: delete that generation. Never throws. If it fails, the repository remembers the claim and a
  retry on the same pod owns it again; on another pod it heals by the takeover.

Every write names the generation it applies to, so a late confirm or release of a claim that was taken over
cannot touch the new claim.

A store failure during a claim (`IdempotentRepositoryException`) fails the notification: three local
retries, then a nack, as for any other failure.

### Consistency

Nothing here depends on how fast a written object becomes visible. The decision is the precondition of the
create, evaluated by the server, not a read. And the reads that follow are strongly consistent: GCS
guarantees read-after-write, read-after-metadata-update, read-after-delete and list-after-write globally.
What is eventually consistent in GCS is IAM changes and the cache of publicly readable objects; neither
applies to a private bucket read through the API. If a read ever did return "absent" for an object that
exists, the claim would simply try the conditional create again.

### What changed in behaviour

1. **A pod killed between claim and publish no longer loses the dataset** (was *Known open* 1). The next
   notification for the dataset, 15 minutes later or more, takes the claim over. Marduk re-exports nightly.
2. **A failed release no longer needs the other pod to wait for a retry** (was *Known open* 2): same takeover.
3. **No start-up replay and no poller for the repository.** Start-up is one replay shorter; the
   `kafkaPoller` health contributor now only watches the import-date reader (*Known open* 4 no longer
   concerns notifications).
4. **A duplicate costs two GCS requests** (a refused create and a metadata read) instead of a map lookup;
   a new dataset costs two (create, confirm). At a few dozen notifications a night this is noise.
5. **Start-up fails when the bucket is missing or not accessible** (`gcs-blobstore` profile), so a
   provisioning mistake stops the rollout instead of failing the first nightly export.
6. **The dedup horizon is the bucket's lifecycle rule**, not the topic's retention. See *Provisioning*.

A publish that times out after the broker wrote the event is no longer released blindly: the publisher first
waits for the event to be read back from the topic (*Known open* 3, narrowed).

## Configuration

| Property | Default | What it does |
| --- | --- | --- |
| `nisaba.idempotent.gcs.bucket` | | The bucket of the markers (`gcs-blobstore` profile) |
| `nisaba.idempotent.stale-after` | 15m | Age after which an unconfirmed claim is taken over. Must exceed the longest publish: a few blob lookups plus one Kafka send bounded by `nisaba.kafka.send-timeout` (30 s) |
| `nisaba.idempotent.migration.dry-run` | false | Migration tool: list and count, write nothing |
| `nisaba.idempotent.migration.threads` | 16 | Migration tool: concurrent creates |
| `blobstore.gcs.project.id`, `blobstore.gcs.credential.path` | | Shared with the blob stores |

Outside the `gcs-blobstore` profile (`in-memory-blobstore`, `local-disk-blobstore`, tests) the markers live
in memory (`InMemoryMarkerStore`), with the same semantics within one JVM.

`nisaba.kafka.claim-timeout` is gone. `nisaba.kafka.topic.idempotent` is now read by the migration tool only.

## Provisioning (outside this repository)

One bucket per environment, named in `helm/nisaba/env/values-kub-ent-*.yaml`:
`nisaba-idempotent-repo-dev`, `nisaba-idempotent-repo-test`, `nisaba-idempotent-repo-production`.

- Uniform bucket-level access, public access prevention enforced, object versioning off.
- The pod's Google service account needs `storage.objects.create`, `get`, `list`, `update` and `delete` on
  the bucket: `roles/storage.objectUser`.
- **Lifecycle rule: delete at age 365 days.** This is the dedup horizon. Age counts from the creation of the
  marker, so a dataset that Marduk keeps re-exporting unchanged for more than a year is published once more
  when its marker expires. Without a rule the history is kept for ever; the objects are a few dozen bytes.
- Soft delete can be left at the bucket default; a soft-deleted object does not exist for the precondition.

The bucket must exist before the deployment: the pods do not start without it.

## Migrating the history from the Kafka topic

`IdempotentRepositoryMigrationApp` is a command line tool in the application jar. It replays the idempotent
topic from the beginning with the rule both previous versions applied (the first `add` after the last
`remove` makes a key present; Camel-written records without a claim id count), and creates a confirmed marker
for every key present, unless the key has one already. It only reads the topic, never overwrites or deletes
a marker, and can be run any number of times. The claim id of a migrated marker is `migrated:<claim id on
the topic>`, or `migrated:camel`.

It starts none of the application: no PubSub consumer, no web server, no producer.

```bash
gcloud auth application-default login   # an identity with roles/storage.objectUser on the bucket

java -cp nisaba.jar \
  -Dloader.main=no.entur.nisaba.idempotent.migration.IdempotentRepositoryMigrationApp \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --spring.profiles.active=gcs-blobstore \
  --blobstore.gcs.project.id=<project> \
  --nisaba.idempotent.gcs.bucket=nisaba-idempotent-repo-<env> \
  --nisaba.kafka.topic.idempotent=rutedata-dataset-import-event-idempotent-repo-<env> \
  --spring.kafka.bootstrap-servers=<brokers> \
  --spring.kafka.security.protocol=SASL_SSL \
  --spring.kafka.properties.sasl.mechanism=SCRAM-SHA-512 \
  --spring.kafka.properties.sasl.jaas.config='org.apache.kafka.common.security.scram.ScramLoginModule required username="<user>" password="<password>";' \
  --nisaba.idempotent.migration.dry-run=true
```

The Kafka settings are the ones of the kcat recipe; the user needs read access to the topic and no consumer
group. The last log line is the summary:

```
Migration of topic <topic> dry run: 4312 keys on the topic, 4312 would be created, 0 already present, 0 failed
```

Run it again without `dry-run`. The exit code is 0 when nothing failed, 1 otherwise (a topic that does not
exist, a replay longer than `nisaba.kafka.replay-timeout`, or a key that could not be written; the keys that
failed are logged, and a second run picks them up). It is also 1, with nothing migrated, when the
`gcs-blobstore` profile is not active: the tool refuses to migrate into the in-memory store. Check: `gsutil ls gs://<bucket> | wc -l` equals the
number of keys on the topic.

## Deploying it

1. Provision the bucket and the IAM binding.
2. Run the migration: dry run, then for real.
3. Deploy, dev → tst → prd, outside the nightly export window (02:30 to 05:00 Europe/Oslo) and with the
   subscription backlog at 0, as for the Camel removal. During the rolling update an old pod still decides on
   the topic and a new pod on the bucket, so a dataset exported in those minutes could be published by both;
   an empty backlog outside the export window makes that a non-event.
4. Run the migration once more after the rollout: it picks up what the old pods wrote to the topic after
   step 2.
5. Keep the idempotent topic until the rollback window has passed. Nothing writes to it any more.

### Rollback

Redeploying the previous version works: it replays the topic and decides on it again. The keys published
through GCS since the switch are not on the topic, so the previous version publishes those datasets once
more at their next export. To avoid it, produce `<key> add` to the topic for the markers created since the
switch (`gsutil ls -l gs://<bucket>` sorted by date) before rolling back.

## Operating it

```bash
gsutil ls gs://<bucket>/avi_*                 # the keys of a codespace
gsutil cat gs://<bucket>/<key>                # who claimed it
gsutil stat gs://<bucket>/<key>               # metadata: claim-id, confirmed; creation time; generation
gsutil rm gs://<bucket>/<key>                 # publish the dataset again at its next export
```

Log lines of the repository (logger `no.entur.nisaba.idempotent.GcsIdempotentRepository`):

| Line | Level | Meaning |
| --- | --- | --- |
| `Claimed key {} with claim {}` | INFO | New dataset, about to be published |
| `Key {} is being published by claim {} since {}; not claiming it` | INFO | Another pod holds a fresh claim; the notification fails and is redelivered |
| `Claim {} for key {} could not be released earlier and is still in place; owning it again` | WARN | A retry on the pod whose release failed |
| `Taking over claim {} for key {}: unconfirmed since {}, {} ago` | WARN | A claimer died or could not release; the dataset is published now |
| `Released key {} (claim {}) so that it can be claimed again` | WARN | The publish failed; a redelivery will retry |
| `Could not confirm claim {} ... Trying again` | INFO | A confirm failed and is retried |
| `Could not confirm claim {} ...` | WARN | The event is out but the marker is not confirmed after the retries; a second event may follow |
| `Could not release key {} ...` | ERROR | GCS failure during a release; heals by a retry on the same pod or by the takeover, or delete the object by hand |

## Testing it

- `GcsIdempotentRepositoryTest`: the rules, on the in-memory store, two repositories being two pods. First
  claim wins; confirmed is a duplicate for ever; a fresh foreign claim is left alone and fails the claim; a stale one is taken
  over and the dead claimer's late confirm and release do nothing; sixteen pods taking over at once, one
  winner; a create whose answer was lost is owned; failed release and failed confirm do not throw and heal; a confirm
  is retried until it lands;
  store failures are reported; a key that keeps changing gives up after three attempts.
- `GcsMarkerStoreTest`: the store against `fsouza/fake-gcs-server` (Docker): create-if-absent including
  sixteen concurrent creates, the object's content and metadata, confirm, delete, a missing bucket.
- `GcsMarkerStoreRequestTest`: the emulator ignores the generation precondition of the delete and of the
  metadata patch, so those are pinned at the HTTP level against a stub of the JSON API: each request carries
  `ifGenerationMatch`, and a 412 or 404 answer means "not the owner".
- `IdempotentRepositoryMigrationTest`: an embedded broker with a history written by both previous versions;
  only present keys are migrated, as confirmed; a second run creates only what is missing and never
  overwrites a live claim; dry run; failures counted; the command line tool end to end.
- The Spring tests run the real repository on the in-memory store.

The store has not been run against a real bucket from this repository: the first dry run of the migration
and the first claim in dev are that test.
