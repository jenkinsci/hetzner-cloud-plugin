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

# Developed by

<a href="https://dNation.cloud/"><img src="https://cdn.ifne.eu/public/icons/dnation.png" width="250" alt="dNationCloud"></a>

## Installation

### Installation from update center

- Open your Jenkins instance in browser (as Jenkins administrator)
- Go to `Manage Jenkins`
- Go to `Manage Plugins`
- Search for _Hetzner Cloud_ under `Available` tab
- Click `Install`
- Jenkins server might require restart after plugin is installed

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

Regardless of configuration method, you will need API token to access your Hetzner Cloud project.
You can read more about creating API token in [official documentation](https://docs.hetzner.cloud/).

### Manual configuration

#### 1. Create credentials for API token

Go to `Dashboard` => `Manage Jenkins` => `Manage credentials` => `Global` => `Add credentials`, choose `Secret text` as a credentials kind:

![add-token](docs/add-token.png)

#### 2. Create cloud

Go to `Dashboard` => `Manage Jenkins` => `Manage Nodes and Clouds` => `Configure Clouds` => `Add a new cloud` and choose `Hetzner` from dropdown menu:

![add-cloud-button](docs/add-hcloud-button.png)

![add-cloud](docs/add-cloud.png)

Name of cloud should match pattern `[a-zA-Z0-9][a-zA-Z\-_0-9]`.


You can use `Test Connection` button to verify that token is valid and that plugin can use Hetzner API.

#### 3. Define server templates

![server-template](docs/server-template.png)


Following attributes are **required** for each server template:

- `Name` - name of template, should match regex `[a-zA-Z0-9][a-zA-Z\-_0-9]`

- `Connect method` this attribute specifies how Jenkins master will connect to newly provisioned server. These methods are supported:
  - `Connect as root` - SSH connection to provisioned server will be done as `root` user.
     This is convenient method due to fact, that Hetzner cloud allows us to specify SSH key for `root` user during server creation.
     Once connection is established, Jenkins agent will be launched by non-root user specified in chosen credentials.
     User must already exist.
  - `Connect as user specified in credentials` - again, that user must already be known to server and its `~/.ssh/authorized_keys` must contain public key counterpart of chosen SSH credentials.
     See bellow how server image can pre created using Hashicorp packer, which also can be used to populate public SSH key.
- `Labels` - set of node labels, separated by space

- `Image ID or label expression` - identifier of server image. It could be ID of image (integer) or label expression.
  In case of label expression, it is assumed that expression resolve into exactly one result.
  Either case, image **must have JRE already installed**.
- `Server type` - type of server

- `Location` - this could be either datacenter name or location name. Distinction is made using presence of character `-` in value, which is meant for datacenter.

These additional attributes can be specified, but are not required:

- `Remote directory` - agent working directory

- `Agent JVM options` - Additional JVM options for Jenkins agent

- `Boot deadline minutes` - Maximum amount of time (in minutes) to wait for newly created server to be in `running` state. 

- `Number of Executors`

- `Keep around minutes` - Time that agent will be kept online after it become idle.

### Scripted configuration using Groovy

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
