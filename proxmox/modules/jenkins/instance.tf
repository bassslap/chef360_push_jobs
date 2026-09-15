locals {
  basename = "jenkins"

  # Extract host/IP from the Proxmox API endpoint URL (https://<host>:8006)
  proxmox_host = regex("https://([^:/]+)", var.proxmox.endpoint)[0]

  user_data_script = file("${path.module}/templates/jenkins-install.sh")
}

resource "local_file" "jenkins_userdata" {
  count = 1
  content = templatefile("${path.module}/templates/jenkins-cloud-init.yml", {
    hostname         = format("%s-%02g", local.basename, "1")
    ip_address       = var.jenkins.ip_address
    subnet_bits      = var.networking.subnet_bits
    gateway          = var.networking.gateway
    dns_servers      = var.networking.dns_servers
    ssh_public_key   = trimspace(file(var.proxmox.public_key_file))
    user_data_script = base64encode(local.user_data_script)
  })
  filename = "${path.root}/tmp/jenkins-userdata-1.yml"
}

resource "local_file" "proxmox_tmp_directory" {
  content  = ""
  filename = "${path.root}/tmp/.keep"
}

resource "null_resource" "copy_snippet_to_proxmox" {
  depends_on = [local_file.jenkins_userdata, local_file.proxmox_tmp_directory]
  triggers = {
    src_hash = sha256(local_file.jenkins_userdata[0].content)
  }

  provisioner "local-exec" {
    command = "scp ${local_file.jenkins_userdata[0].filename} root@${local.proxmox_host}:/var/lib/vz/snippets/"
  }
}

resource "proxmox_virtual_environment_vm" "jenkins" {
  count       = 1
  vm_id       = var.jenkins.vm_id
  node_name   = var.networking.node_name
  name        = format("%s-%02g", local.basename, "1")
  description = "Jenkins CI Server (Chef Push Jobs replacement)"
  tags        = var.tags

  agent {
    enabled = true
    trim    = true
  }

  cpu {
    cores = var.jenkins.cores
    type  = "x86-64-v2-AES"
  }

  memory {
    dedicated = var.jenkins.memory
  }

  network_device {
    bridge  = var.networking.bridge
    model   = "virtio"
    vlan_id = var.networking.vlan_tag != 0 ? var.networking.vlan_tag : null
  }

  # Disk
  disk {
    datastore_id = var.networking.storage
    interface    = "scsi0"
    iothread     = true
    size         = var.jenkins.disk_size_gb
    file_format  = "raw"
  }

  # Clone from template
  clone {
    vm_id = var.proxmox.template_id
    full  = true
  }

  initialization {
    ip_config {
      ipv4 {
        address = "${var.jenkins.ip_address}/${var.networking.subnet_bits}"
        gateway = var.networking.gateway
      }
    }

    user_account {
      keys     = [trimspace(file(var.proxmox.public_key_file))]
      username = var.proxmox_credentials.vm_user
      password = var.proxmox_credentials.vm_password
    }
    dns {
      domain  = "lab.local"
      servers = var.networking.dns_servers
    }
    user_data_file_id = "local:snippets/jenkins-userdata-1.yml"
  }

  depends_on = [null_resource.copy_snippet_to_proxmox]

  operating_system {
    type = "l26"
  }

  vga {
    type = "serial0"
  }

  started         = true
  template        = false
  stop_on_destroy = true
  on_boot         = true
}
