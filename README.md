<!--
     Copyright 2021 https://dnation.cloud

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

          http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

# Hetzner Cloud Plugin for Jenkins

The Hetzner cloud plugin enables [Jenkins CI](https://www.jenkins.io/) to schedule builds on dynamically provisioned VMs in [Hetzner Cloud](https://www.hetzner.com/cloud).

## Installation

### Installation from source

- Clone this git repository
- Build with maven `mvn clean package`
- Open your Jenkins instance in browser (as Jenkins administrator)
- Go to `Manage Jenkins`
- Go to `Manage Plugins`
- Click on `Advanced` tab
- Under `Upload Plugin` section, click on `Choose file` button and select `target/hetzner-cloud.hpi` file
- Jenkins server might require restart after plugin is installed

## Configuration

### Manual configuration

#### 1. Create credentials for API token

From Dashboard => Manage Jenkins => Manage credentials => Global => Add credentials

![add-token](docs/add-token.png)

#### 2. Create cloud

From Dashboard => Manage Jenkins => Manage Nodes and Clouds => Configure Clouds => Add a new cloud

Choose `Hetzner` from dropdown menu

![add-cloud-button](docs/add-hcloud-button.png)

![add-cloud](docs/add-cloud.png)

You can use `Test Connection` button to verify that token is valid and that plugin can use Hetzner API.

#### 3. Define server templates

![server-template](docs/server-template.png)


### Scripted configuration

```groovy
import cloud.dnation.jenkins.plugins.hetzner.*
import cloud.dnation.jenkins.plugins.hetzner.launcher.*

def cloudName = "hcloud-01"

def templates = [
        new HetznerServerTemplate("ubuntu20-cx21", "java", "name=ubuntu20-docker", "fsn1", "cx21"),
        new HetznerServerTemplate("ubuntu20-cx31", "java", "name=ubuntu20-docker", "fsn1", "cx31")
]

templates.each { it -> it.setConnector(new SshConnectorAsRoot("my-private-ssh-key")) }

def cloud = new HetznerCloud(cloudName, "hcloud-token", "10", templates)

def jenkins = Jenkins.get()

jenkins.clouds.remove(jenkins.clouds.getByName(cloudName))
jenkins.clouds.add(cloud)
jenkins.save()
```

### Configuration as a code

Here is sample of CasC file

```yaml
---
jenkins:
  clouds:
    - hetzner:
        name: "hcloud-01"
        credentialsId: "hcloud-api-token"
        instanceCapStr: "10"
        serverTemplates:
          - name: ubuntu2-cx21
            serverType: cx21
            remoteFs: /var/lib/jenkins
            location: fsn1
            image: name=jenkins
            labelStr: java
            numExecutors: 1
            connector:
              root:
                sshCredentialsId: 'ssh-private-key'
          - name: ubuntu2-cx31
            serverType: cx31
            remoteFs: /var/lib/jenkins
            location: fsn1
            image: name=jenkins
            labelStr: java
            numExecutors: 3
            connector:
              root:
                sshCredentialsId: 'ssh-private-key'
credentials:
  system:
    domainCredentials:
      - credentials:
          - string:
              scope: SYSTEM
              id: "hcloud-api-token"
              description: "Hetzner cloud API token"
              secret: "abcdefg12345678909876543212345678909876543234567"
          - basicSSHUserPrivateKey:
              scope: SYSTEM
              id: "ssh-private-key"
              username: "jenkins"
              privateKeySource:
                directEntry:
                  privateKey: |
                    -----BEGIN OPENSSH PRIVATE KEY-----
                    b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABFwAAAAdzc2gtcn
                      ... truncated ...
                    baewZMKBL1QECTolAAAADHJrb3NlZ2lAbDQ4MAECAwQFBg==
                    -----END OPENSSH PRIVATE KEY-----
```

### Server details

Plugin is able to report server details for any provisioned node

![server details](docs/server-detail.png)

### Create server image using Packer

It's possible to create images in Hetzner Cloud using Packer.

- Get [Hashicorp Packer](https://www.packer.io/downloads)

- Create image template, see an [example](docs/template.pkr.hcl)

- Build using `packer build -force template.pkr.hcl`
  You should see output similar to this (truncated):
  ```
   ==> Builds finished. The artifacts of successful builds are:
   --> hcloud.jenkins: A snapshot was created: 'ubuntu20-docker' (ID: 537465784)
  ```

### Known limitations

- there is no known way of verifying SSH host keys on newly provisioned VMs
- modification of SSH credentials used to connect to VMs require manual removal of key from project's security settings.
  Plugin will automatically create new SSH key in project after it's removed.
