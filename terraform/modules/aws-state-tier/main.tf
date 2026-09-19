# Provisioning of the HATIS data-plane state tier on AWS.
#
#   network (VPC + private subnets)
#     ├── PostgreSQL (RDS, private subnets only, encrypted, PITR enabled)
#     └── Object storage (S3, versioned, encrypted, public access blocked)
#
# Deliberately excludes anything that reaches the internet: the database has no
# public endpoint and the bucket blocks all public access. This module is used
# both for the platform's own SaaS state tier and for Dedicated Cloud installs.

terraform {
  required_version = ">= 1.6.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

variable "name" {
  description = "Prefix applied to every resource name."
  type        = string
}

variable "region" {
  description = "AWS region for the state tier."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC. Private subnets only; no public tier is created."
  type        = string
  default     = "10.40.0.0/16"

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "vpc_cidr must be a valid IPv4 CIDR block."
  }
}

variable "availability_zones" {
  description = "Availability zones. At least two are required for a multi-AZ database."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) >= 2
    error_message = "Provide at least two availability zones so the database can run multi-AZ."
  }
}

variable "database" {
  description = "PostgreSQL instance shape and durability settings."
  type = object({
    instance_class        = optional(string, "db.t4g.medium")
    engine_version        = optional(string, "16.4")
    allocated_storage     = optional(number, 50)
    max_allocated_storage = optional(number, 500)
    multi_az              = optional(bool, true)
    backup_retention_days = optional(number, 14)
    # Point-in-time recovery is always on: a tenant must be able to recover from
    # an accidental destructive change, not just from a full outage.
    deletion_protection = optional(bool, true)
    name                = optional(string, "hatis")
  })
  default = {}
}

variable "storage" {
  description = "Object storage settings."
  type = object({
    versioning                = optional(bool, true)
    noncurrent_version_expiry = optional(number, 90)
  })
  default = {}
}

variable "allowed_security_group_ids" {
  description = "Security groups permitted to reach PostgreSQL on 5432."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
  default     = {}
}

locals {
  common_tags = merge({
    ManagedBy   = "terraform"
    Component   = "hatis-state-tier"
    Environment = var.name
  }, var.tags)

  private_subnets = [
    for index, az in var.availability_zones :
    cidrsubnet(var.vpc_cidr, 8, index + 10)
  ]
}

# ---------------------------------------------------------------------------
# Network
# ---------------------------------------------------------------------------

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(local.common_tags, { Name = "${var.name}-state-vpc" })
}

resource "aws_subnet" "private" {
  count                   = length(var.availability_zones)
  vpc_id                  = aws_vpc.this.id
  cidr_block              = local.private_subnets[count.index]
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = false
  tags                    = merge(local.common_tags, { Name = "${var.name}-private-${count.index + 1}" })
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-db"
  subnet_ids = aws_subnet.private[*].id
  tags       = local.common_tags
}

resource "aws_security_group" "database" {
  name        = "${var.name}-database"
  description = "PostgreSQL access for the HATIS platform"
  vpc_id      = aws_vpc.this.id
  tags        = local.common_tags
}

resource "aws_vpc_security_group_ingress_rule" "database" {
  count                        = length(var.allowed_security_group_ids)
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = var.allowed_security_group_ids[count.index]
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "PostgreSQL from the platform"
}

# ---------------------------------------------------------------------------
# PostgreSQL
# ---------------------------------------------------------------------------

resource "aws_db_instance" "this" {
  identifier     = "${var.name}-postgres"
  engine         = "postgres"
  engine_version = var.database.engine_version
  instance_class = var.database.instance_class

  allocated_storage     = var.database.allocated_storage
  max_allocated_storage = var.database.max_allocated_storage
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = var.database.name
  username = "hatis"
  # The master password is generated and kept in Secrets Manager; it is never
  # written to state, to a values file or to Git.
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.database.id]
  publicly_accessible    = false
  multi_az               = var.database.multi_az

  # Recovery: 14 days of automated backups with point-in-time recovery, plus a
  # low-traffic backup window and a Sunday maintenance window.
  backup_retention_period = var.database.backup_retention_days
  backup_window           = "02:00-03:00"
  maintenance_window      = "sun:04:00-sun:05:00"
  copy_tags_to_snapshot   = true

  auto_minor_version_upgrade = true
  deletion_protection        = var.database.deletion_protection
  # A final snapshot is always taken; destroying the instance must not destroy
  # the data.
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.name}-postgres-final"

  apply_immediately = false
  tags              = local.common_tags
}

# ---------------------------------------------------------------------------
# Object storage
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "assets" {
  bucket        = "${var.name}-hatis-assets"
  force_destroy = false
  tags          = local.common_tags
}

resource "aws_s3_bucket_public_access_block" "assets" {
  bucket                  = aws_s3_bucket.assets.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "assets" {
  bucket = aws_s3_bucket.assets.id
  versioning_configuration {
    status = var.storage.versioning ? "Enabled" : "Suspended"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "assets" {
  bucket = aws_s3_bucket.assets.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "assets" {
  bucket = aws_s3_bucket.assets.id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
    noncurrent_version_expiration {
      noncurrent_days = var.storage.noncurrent_version_expiry
    }
  }
}

# ---------------------------------------------------------------------------
# Outputs
# ---------------------------------------------------------------------------

output "vpc_id" {
  description = "VPC hosting the state tier."
  value       = aws_vpc.this.id
}

output "private_subnet_ids" {
  description = "Private subnets. Nothing in this module is internet reachable."
  value       = aws_subnet.private[*].id
}

output "database_endpoint" {
  description = "PostgreSQL endpoint (host:port). Credentials are in Secrets Manager."
  value       = aws_db_instance.this.endpoint
}

output "database_secret_arn" {
  description = "ARN of the Secrets Manager secret holding the master credentials."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "database_security_group_id" {
  description = "Security group to attach to anything that needs database access."
  value       = aws_security_group.database.id
}

output "asset_bucket" {
  description = "Object storage bucket for tenant assets."
  value       = aws_s3_bucket.assets.bucket
}

output "asset_bucket_arn" {
  description = "ARN of the asset bucket, for IAM policy scoping."
  value       = aws_s3_bucket.assets.arn
}
