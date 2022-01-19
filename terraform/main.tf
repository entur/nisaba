# Contains main description of bulk of terraform
terraform {
  required_version = ">= 0.13.2"
}

provider "google" {
  version = "~> 4.7.0"
}
provider "kubernetes" {
  load_config_file = var.load_config_file
  version = "~> 1.13.4"
}

# create service account
resource "google_service_account" "nisaba_service_account" {
  account_id = "${var.labels.team}-${var.labels.app}-sa"
  display_name = "${var.labels.team}-${var.labels.app} service account"
  project = var.gcp_resources_project
}

# add service account as member to marduk bucket
resource "google_storage_bucket_iam_member" "storage_marduk_bucket_iam_member" {
  bucket = var.bucket_marduk_instance_name
  role = var.service_account_bucket_role
  member = "serviceAccount:${google_service_account.nisaba_service_account.email}"
}

# add service account as member to nisaba bucket
resource "google_storage_bucket_iam_member" "storage_nisaba_bucket_iam_member" {
  bucket = var.bucket_nisaba_instance_name
  role = var.service_account_bucket_role
  member = "serviceAccount:${google_service_account.nisaba_service_account.email}"
}

# add service account as member to nisaba exchange bucket
resource "google_storage_bucket_iam_member" "storage_nisaba_exchange_bucket_iam_member" {
  bucket = var.bucket_nisaba_exchange_instance_name
  role = var.service_account_bucket_role
  member = "serviceAccount:${google_service_account.nisaba_service_account.email}"
}

# add service account as member to nisaba exchange varelager bucket
resource "google_storage_bucket_iam_member" "storage_nisaba_exchange_varelager_bucket_iam_member" {
  bucket = var.bucket_nisaba_exchange_varelager_instance_name
  role = var.service_account_bucket_role
  member = "serviceAccount:${google_service_account.nisaba_service_account.email}"
}

# add service account as member to pubsub service in the resources project
resource "google_project_iam_member" "pubsub_project_iam_member_subscriber" {
  project = var.gcp_pubsub_project
  role = "roles/pubsub.subscriber"
  member = "serviceAccount:${google_service_account.nisaba_service_account.email}"
}

resource "google_project_iam_member" "pubsub_project_iam_member_publisher" {
  project = var.gcp_pubsub_project
  role = "roles/pubsub.publisher"
  member = "serviceAccount:${google_service_account.nisaba_service_account.email}"
}

# create key for service account
resource "google_service_account_key" "nisaba_service_account_key" {
  service_account_id = google_service_account.nisaba_service_account.name
}

# Add SA key to to k8s
resource "kubernetes_secret" "nisaba_service_account_credentials" {
  metadata {
    name = "${var.labels.team}-${var.labels.app}-sa-key"
    namespace = var.kube_namespace
  }
  data = {
    "credentials.json" = base64decode(google_service_account_key.nisaba_service_account_key.private_key)
  }
}

resource "kubernetes_secret" "ror-nisaba-secret" {
  metadata {
    name = "${var.labels.team}-${var.labels.app}-secret"
    namespace = var.kube_namespace
  }

  data = {
    "kafka-username"    = var.ror-nisaba-kafka-username
    "kafka-password"    = var.ror-nisaba-kafka-password
  }
}

# Create pubsub topics and subscriptions
resource "google_pubsub_topic" "NetexServiceJourneyPublicationDeadLetterQueue" {
  name = "NetexServiceJourneyPublicationDeadLetterQueue"
  project = var.gcp_pubsub_project
  labels = var.labels
}

resource "google_pubsub_subscription" "NetexServiceJourneyPublicationDeadLetterQueue" {
  name = "NetexServiceJourneyPublicationDeadLetterQueue"
  topic = google_pubsub_topic.NetexServiceJourneyPublicationDeadLetterQueue.name
  project = var.gcp_pubsub_project
  labels = var.labels
}

resource "google_pubsub_topic" "NetexServiceJourneyPublicationQueue" {
  name = "NetexServiceJourneyPublicationQueue"
  project = var.gcp_pubsub_project
  labels = var.labels
}

resource "google_pubsub_subscription" "NetexServiceJourneyPublicationQueue" {
  name = "NetexServiceJourneyPublicationQueue"
  topic = google_pubsub_topic.NetexServiceJourneyPublicationQueue.name
  project = var.gcp_pubsub_project
  labels = var.labels
  ack_deadline_seconds = 600
  dead_letter_policy {
    max_delivery_attempts = 5
    dead_letter_topic = google_pubsub_topic.NetexServiceJourneyPublicationDeadLetterQueue.id
  }
  retry_policy {
    minimum_backoff = "10s"
  }
}
