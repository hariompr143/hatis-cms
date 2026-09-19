# Installs the HATIS platform into an existing Kubernetes cluster.
#
# This is the module used for SaaS, Dedicated Cloud and Private Enterprise
# installs alike: the customer supplies a cluster (theirs or ours) and this module
# renders the chart with their configuration. Nothing here assumes the cluster is
# managed by a particular cloud.

terraform {
  required_version = ">= 1.6.0"
  required_providers {
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.12"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.27"
    }
  }
}

variable "namespace" {
  description = "Namespace the platform is installed into."
  type        = string
  default     = "hatis-system"
}

variable "release_name" {
  description = "Helm release name."
  type        = string
  default     = "hatis"
}

variable "chart_path" {
  description = "Path to the hatis-platform chart."
  type        = string
}

variable "chart_version" {
  description = "Chart version to install. Pin this: floating versions make rollbacks ambiguous."
  type        = string
}

variable "image_repository" {
  description = "Platform image repository."
  type        = string
}

variable "image_tag" {
  description = "Platform image tag. Must be an immutable tag or digest."
  type        = string

  validation {
    condition     = var.image_tag != "latest"
    error_message = "Refusing to deploy the 'latest' tag: releases must be reproducible and rollbackable."
  }
}

variable "ingress_host" {
  description = "Public hostname for the platform API."
  type        = string
}

variable "certificate_issuer" {
  description = "cert-manager issuer used for the ingress certificate."
  type = object({
    name = string
    kind = optional(string, "ClusterIssuer")
  })
  default = {
    name = "letsencrypt-prod"
    kind = "ClusterIssuer"
  }
}

variable "event_transport" {
  description = "outbox (single node / private) or kafka."
  type        = string
  default     = "outbox"

  validation {
    condition     = contains(["outbox", "kafka"], var.event_transport)
    error_message = "event_transport must be 'outbox' or 'kafka'."
  }
}

variable "kafka_bootstrap" {
  description = "Kafka bootstrap servers. Required when event_transport is 'kafka'."
  type        = string
  default     = ""
}

variable "secrets_provider" {
  description = "Secret store the platform reads its own credentials from."
  type        = string
  default     = "kubernetes"

  validation {
    condition     = contains(["environment", "vault", "aws", "kubernetes"], var.secrets_provider)
    error_message = "secrets_provider must be one of environment, vault, aws, kubernetes."
  }
}

variable "platform_secrets" {
  description = <<-EOT
    Material for the Secret the platform reads at startup. Provided by the
    installer from the customer's secret store; never committed to Git.
  EOT
  type = object({
    db_url            = string
    db_username       = string
    db_password       = string
    redis_host        = string
    token_signing_key = string
  })
  sensitive = true
}

variable "extra_values" {
  description = "Additional values files, applied after the defaults."
  type        = list(string)
  default     = []
}

variable "create_deployer_role" {
  description = "Grant the platform the least-privilege role for scheduling tenant workloads."
  type        = bool
  default     = true
}

resource "kubernetes_namespace" "this" {
  metadata {
    name = var.namespace
    labels = {
      "app.kubernetes.io/part-of" = "hatis-platform"
      # Pods run under the restricted Pod Security standard.
      "pod-security.kubernetes.io/enforce" = "restricted"
      "pod-security.kubernetes.io/audit"   = "restricted"
      "pod-security.kubernetes.io/warn"    = "restricted"
    }
  }
}

resource "kubernetes_secret" "platform" {
  metadata {
    name      = "hatis-platform-secrets"
    namespace = kubernetes_namespace.this.metadata[0].name
    labels = {
      "app.kubernetes.io/part-of" = "hatis-platform"
    }
  }

  data = {
    "db-url"            = var.platform_secrets.db_url
    "db-username"       = var.platform_secrets.db_username
    "db-password"       = var.platform_secrets.db_password
    "redis-host"        = var.platform_secrets.redis_host
    "token-signing-key" = var.platform_secrets.token_signing_key
  }

  type = "Opaque"
}

resource "helm_release" "platform" {
  name       = var.release_name
  chart      = var.chart_path
  version    = var.chart_version
  namespace  = kubernetes_namespace.this.metadata[0].name
  repository = null

  # A failed upgrade must not leave a half-applied release behind.
  atomic  = true
  cleanup_on_fail = true
  timeout = 900
  wait    = true

  values = concat(
    [file("${path.module}/../../../deploy/helm/values/production.yaml")],
    var.extra_values
  )

  set {
    name  = "platform.image.repository"
    value = var.image_repository
  }
  set {
    name  = "platform.image.tag"
    value = var.image_tag
  }
  set {
    name  = "ingress.host"
    value = var.ingress_host
  }
  set {
    name  = "certificate.enabled"
    value = "true"
  }
  set {
    name  = "certificate.issuerRef.name"
    value = var.certificate_issuer.name
  }
  set {
    name  = "certificate.issuerRef.kind"
    value = var.certificate_issuer.kind
  }
  set {
    name  = "events.transport"
    value = var.event_transport
  }
  set {
    name  = "events.kafkaBootstrap"
    value = var.kafka_bootstrap
  }
  set {
    name  = "secretsProvider"
    value = var.secrets_provider
  }
  set {
    name  = "serviceAccount.createDeployerRole"
    value = tostring(var.create_deployer_role)
  }
  set {
    name  = "existingSecret"
    value = kubernetes_secret.platform.metadata[0].name
  }

  depends_on = [kubernetes_secret.platform]
}

output "namespace" {
  description = "Namespace the platform was installed into."
  value       = kubernetes_namespace.this.metadata[0].name
}

output "release" {
  description = "Helm release name."
  value       = helm_release.platform.name
}

output "revision" {
  description = "Chart revision installed. Record this: it is what you roll back to."
  value       = helm_release.platform.version
}
