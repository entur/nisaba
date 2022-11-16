# Contains main description of bulk of terraform
terraform {
  required_version = ">= 0.13.2"
}

provider "google" {
  version = ">= 4.26"
}
provider "kubernetes" {
  version = ">= 2.13.1"
}


resource "kubernetes_secret" "ror-nisaba-secret" {
  metadata {
    name = "${var.labels.team}-${var.labels.app}-secrets"
    namespace = var.kube_namespace
  }

  data = {
    "KAFKAUSERNAME"    = var.ror-nisaba-kafka-username
    "KAFKAPASSWORD"    = var.ror-nisaba-kafka-password
  }
}
