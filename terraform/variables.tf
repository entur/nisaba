variable "gcp_resources_project" {
  description = "The GCP project hosting the project resources"
  type        = string
}

variable "bucket_location" {
  description = "GCP bucket location"
  type        = string
  default     = "europe-west1"
}

variable "bucket_instance_suffix" {
  description = "A suffix for the bucket instance - bucket names must be globally unique"
  type        = string
}

variable "idempotent_repository_bucket_prefix" {
  description = "A prefix for the bucket of the idempotent repository"
  type        = string
  default     = "nisaba-idempotent-repo"
}

variable "idempotent_repository_retention_days" {
  description = "Age in days after which a marker of the idempotent repository is deleted"
  type        = number
  default     = 365
}

variable "nisaba_service_account" {
  description = "Email of the service account the application runs as"
  type        = string
}

variable "labels" {
  description = "Labels used in all resources"
  type        = map(string)
  default = {
    manager = "terraform"
    team    = "ror"
    slack   = "talk-ror"
    app     = "nisaba"
  }
}
