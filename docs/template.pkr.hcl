# Copyright 2021 https://dnation.cloud
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

variable "image_name" {
  description = "Name of image"
  type        = string
  default     = "ubuntu20-docker"

  validation {
    condition     = can(regex("^[\\w-_]+", var.image_name))
    error_message = "The 'image_name' value must be a valid image identifier (only alphanumeric characters or any of '_.-')."
  }
}

variable "location" {
  description = "Location where to create image"
  type        = string
  default     = "fsn1"
}

source "hcloud" "jenkins" {
  image         = "ubuntu-20.04"
  location      = var.location
  server_type   = "cx11"
  ssh_username  = "root"
  server_name   = "${var.image_name}-image-builder"
  snapshot_name = var.image_name
  snapshot_labels = {
    vendor = "dnation.cloud"
    name   = var.image_name
  }
}

build {
  sources = [
    "source.hcloud.jenkins"
  ]

  provisioner "shell" {
    environment_vars = [
      "DEBIAN_FRONTEND=noninteractive"
    ]
    inline = [
      "apt-get clean; apt-get update",
      "apt-get install -y --no-install-recommends openjdk-11-jdk apt-transport-https ca-certificates curl gnupg lsb-release",
      "useradd --uid 1000 --groups sudo,adm --create-home --home-dir /home/jenkins jenkins",
      "echo 'jenkins ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/jenkins",
      "curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg",
      "echo \"deb [arch=amd64 signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] \\",
      "  https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable\" \\",
      "  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null",
      "apt-get update; apt-get install -y docker-ce docker-ce-cli containerd.io",
      "systemctl enable docker",
      "usermod jenkins -a -G docker"
    ]
  }
}