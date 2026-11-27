# Bucket of the idempotent repository: one marker object per import key, created with ifGenerationMatch=0.
# The application does not start without it, so it has to exist before the version that uses it is deployed.
resource "google_storage_bucket" "idempotent_repository" {
  name                        = "${var.idempotent_repository_bucket_prefix}-${var.bucket_instance_suffix}"
  location                    = var.bucket_location
  project                     = var.gcp_resources_project
  storage_class               = "STANDARD"
  labels                      = var.labels
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  # A marker has one generation: the conditional create, confirm and release rely on it.
  versioning {
    enabled = false
  }

  # The age of a marker is the dedup horizon: a dataset re-exported unchanged for longer than this is
  # published once more when its marker expires.
  lifecycle_rule {
    condition {
      age = var.idempotent_repository_retention_days
    }
    action {
      type = "Delete"
    }
  }
}

# The application creates, reads, lists, updates (confirm) and deletes (release) markers.
resource "google_storage_bucket_iam_member" "idempotent_repository_object_user" {
  bucket = google_storage_bucket.idempotent_repository.name
  role   = "roles/storage.objectUser"
  member = "serviceAccount:${var.nisaba_service_account}"
}

# The application reads the exchange bucket. The bucket is not managed here.
resource "google_storage_bucket_iam_member" "exchange_bucket_object_viewer" {
  bucket = "${var.exchange_bucket_prefix}-${var.bucket_instance_suffix}"
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${var.nisaba_service_account}"
}

# marduk copies the imported datasets into the exchange bucket. A copy may replace an existing object, which
# needs storage.objects.delete, so roles/storage.objectCreator is not enough. This grant replaces marduk's
# team-wide folder rule (tf-gcp-iam application_rules/ror-marduk-<env>.yaml), so marduk keeps this access
# when that rule is removed.
resource "google_storage_bucket_iam_member" "exchange_bucket_marduk_object_user" {
  bucket = "${var.exchange_bucket_prefix}-${var.bucket_instance_suffix}"
  role   = "roles/storage.objectUser"
  member = "serviceAccount:${var.marduk_service_account}"
}
