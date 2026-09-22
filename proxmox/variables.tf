/*************************************
      Chef 360 Courier + Jenkins POC - Jenkins server on Proxmox
**************************************/

variable "tags" {
  description = "Tags to apply to all resources"
  type        = list(string)
  default     = ["jenkins", "chef-push-jobs-replacement"]
}

# Main proxmox provider configuration
variable "proxmox" {
  description = "Proxmox specific variables required for deployment"
  type = object({
    endpoint         = string
    api_token_id     = string
    api_token_secret = string
    node_name        = string
    public_key_file  = string
    template_id      = number
  })
}

# Networking configuration
variable "networking" {
  description = "Networking configuration"
  type = object({
    bridge      = string
    storage     = string
    node_name   = string
    vlan_tag    = number
    gateway     = string
    dns_servers = list(string)
    subnet_bits = number
  })
}

variable "proxmox_credentials" {
  description = "Proxmox VM credentials"
  type = object({
    vm_user     = string
    vm_password = string
  })
  default = {
    vm_user     = "ubuntu"
    vm_password = "ubuntu123!"
  }
}

# Jenkins CI server configuration
variable "jenkins" {
  description = "Jenkins CI server VM configuration"
  type = object({
    vm_id        = number
    cores        = number
    memory       = number
    disk_size_gb = number
    ip_address   = string
  })
}
