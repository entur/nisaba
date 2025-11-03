
# Nisaba

Nisaba publishes events on the Kafka cluster when new timetable datasets are available.

Nisaba monitors NeTEx export events published by [Marduk](https://github.com/entur/marduk).  
Upon notification of a new export, Nisaba downloads the corresponding dataset from Google Cloud Storage and extracts its creation date.
This creation date is then compared with the creation date of previously imported datasets. If the dataset is new, an event is published to a Kafka topic.

# NeTEx export events
Marduk publishes NeTEx export events to the Google PubSub queue `NetexExportNotificationQueue`.

# NeTEx export bucket
NeTEx datasets are stored in the GCS bucket `marduk-production` (respectively `marduk-dev` and `marduk-test`) in the folder outbound/netex.

The naming convention for NeTEx archives is: `rb_<codespace in lower case>-aggregated-netex.zip`  
Example for the AVI codespace: `rb_avi-aggregated-netex.zip`

# Dataset creation date
The dataset creation date is stored in the `created` attribute of the NeTEx composite frames.  
The creation date is registered once in [Chouette](https://github.com/entur/chouette) when the dataset is imported initially.  
Similarly [Uttu](https://github.com/entur/uttu) generates a creation date when FlexibleLines are updated.  
When Marduk triggers subsequent exports of the same dataset (this happens at least once a day during the nightly validation process), the creation date in the exported NeTEx archive remains unchanged.  
When the dataset contains only NeTEx data exported from Chouette or only NeTEx data exported from Uttu, all CompositeFrames in the NeTEx archive have the same `created` attribute.  
When the dataset contains NeTEx data exported from both Chouette and Uttu, the dataset contains 2 different values for the `created` attribute. In this case the creation date is defined as the maximum of these two values.

# Identifying previously imported datasets
Marduk publishes a NeTEx export event each time a dataset is exported or re-exported, while Nisaba publishes an event only when the dataset is exported for the first time.  
Nisaba relies on a Camel IdempotentRepository to identify datasets that have been already exported.  
The idempotent repository is backed by a Kafka topic that stores the history of previous imports identified by a unique key.  
The history is kept for 365 days.

# Dataset import unique key
A dataset import is uniquely identified by a key built as follows:
```
dataset import unique key = <dataset codespace in lowercase> + '_' + <dataset creation date in ISO-format with time component down to the second >
```  
Example: `avi_2021-04-21T11:51:59`

# Kafka record
Nisaba publishes a Kafka record in the topic `rutedata-dataset-import-event-production` (respectively `rutedata-dataset-import-event-dev` and `rutedata-dataset-import-event-staging`) with the following structure:
- Kafka key: dataset codespace
- Kafka value: Avro format containing a field `codespace` and a field `importDateTime`
