variable "networking" {
  description = "Network related variables"
  type        = any
}

variable "tags" {
  description = "Tags used for X-fields"
  type        = any
}

variable "proxmox" {
  description = "General Proxmox related variables"
  type        = any
}

variable "proxmox_credentials" {
  description = "Proxmox credentials related variables"
  type        = any
}

variable "jenkins" {
  description = "Jenkins VM specific variables"
  type        = any

  validation {
    condition     = can(regex("^[0-9]+$", tostring(var.jenkins.disk_size_gb)))
    error_message = "Disk size must be specified in GB as a number."
  }
}
