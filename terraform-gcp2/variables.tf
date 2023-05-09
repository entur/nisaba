#Enviroment variables

variable "gcp_pubsub_project" {
  description = "The GCP project hosting the PubSub resources"
}

variable "kube_namespace" {
  description = "The Kubernetes namespace"
  default = "nisaba"
}

variable "labels" {
  description = "Labels used in all resources"
  type = map(string)
  default = {
    manager = "terraform"
    team = "ror"
    slack = "talk-ror"
    app = "nisaba"
  }
}

variable  ror-nisaba-kafka-username {
  description = "Nisaba kafka user name"
}

variable ror-nisaba-kafka-password {
  description = "Nisaba kafka user password"
}




