# Contains main description of bulk of terraform
terraform {
  required_version = ">= 0.13.2"
  required_providers {
      google = {
        source  = "hashicorp/google"
        version = "~> 4.84.0"
      }
  }
}



# Create pubsub topics and subscriptions
resource "google_pubsub_topic" "NetexServiceJourneyPublicationDeadLetterQueue" {
  name    = "NetexServiceJourneyPublicationDeadLetterQueue"
  project = var.gcp_pubsub_project
  labels  = var.labels
}

resource "google_pubsub_subscription" "NetexServiceJourneyPublicationDeadLetterQueue" {
  name    = "NetexServiceJourneyPublicationDeadLetterQueue"
  topic   = google_pubsub_topic.NetexServiceJourneyPublicationDeadLetterQueue.name
  project = var.gcp_pubsub_project
  labels  = var.labels
  expiration_policy {
      ttl = ""
    }
}

resource "google_pubsub_topic" "NetexServiceJourneyPublicationQueue" {
  name    = "NetexServiceJourneyPublicationQueue"
  project = var.gcp_pubsub_project
  labels  = var.labels
}

resource "google_pubsub_subscription" "NetexServiceJourneyPublicationQueue" {
  name                 = "NetexServiceJourneyPublicationQueue"
  topic                = google_pubsub_topic.NetexServiceJourneyPublicationQueue.name
  project              = var.gcp_pubsub_project
  labels               = var.labels
  ack_deadline_seconds = 600
  dead_letter_policy {
    max_delivery_attempts = 5
    dead_letter_topic     = google_pubsub_topic.NetexServiceJourneyPublicationDeadLetterQueue.id
  }
  retry_policy {
    minimum_backoff = "10s"
  }
}

# Create bucket
resource "google_storage_bucket" "storage_bucket" {
  name                        = "${var.bucket_instance_prefix}-${var.bucket_instance_suffix}"
  force_destroy               = var.force_destroy
  location                    = var.location
  project                     = var.storage_project
  storage_class               = var.storage_class
  labels                      = var.labels
  uniform_bucket_level_access = true
  versioning {
    enabled = var.versioning
  }
  lifecycle_rule {
    condition {
      age = 1
    }
    action {
      type = "Delete"
    }
  }
  public_access_prevention = "enforced"
  logging {
    log_bucket        = var.log_bucket
    log_object_prefix = "${var.bucket_instance_prefix}-${var.bucket_instance_suffix}"
  }
}
