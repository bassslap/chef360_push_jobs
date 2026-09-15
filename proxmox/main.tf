terraform {
  required_version = ">= 1.3"

  required_providers {
    proxmox = {
      source  = "bpg/proxmox"
      version = "~> 0.70.0"
    }
    local = {
      source  = "hashicorp/local"
      version = ">= 2.1"
    }
  }
}

provider "proxmox" {
  endpoint  = var.proxmox.endpoint
  api_token = "${var.proxmox.api_token_id}=${var.proxmox.api_token_secret}"
  insecure  = true

  ssh {
    agent       = true
    username    = "root"
    private_key = file("~/.ssh/id_rsa")
  }
}

# Deploy Jenkins CI server that replaces the old Chef Push Jobs functionality
module "jenkins" {
  source = "./modules/jenkins"

  jenkins             = var.jenkins
  networking          = var.networking
  proxmox             = var.proxmox
  tags                = var.tags
  proxmox_credentials = var.proxmox_credentials
}

output "jenkins_ip" {
  description = "Jenkins VM IP address"
  value       = var.jenkins.ip_address
}

output "jenkins_url" {
  description = "Jenkins Web UI URL"
  value       = "http://${var.jenkins.ip_address}:8080"
}
