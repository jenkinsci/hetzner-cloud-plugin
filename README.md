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


## Configuration

### Manual configuration

#### 1. Create credentials for API token

From Dashboard => Manage Jenkins => Manage credentials => Global => Add credentials

![add-token](docs/add-token.png)

#### 2. Create cloud

From Dashboard => Manage Jenkins => Manage Nodes and Clouds => Configure Clouds => Add a new cloud

![add-cloud-button](docs/add-hcloud-button.png)

![add-cloud](docs/add-cloud.png)

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
                    NhAAAAAwEAAQAAAQEAyMm0501e8CnMGO72c5fihC//KhdOGahgvbCGbS5mOk4zfpvo+Jui
                    3hIJOOt6DW2atAabo8wUXLg9KH9cXHmKs6SMGuRd7m5fdTaUzO0G//ZmzX51phqSYv6JsU
                    n/vKxqOeVtIWzXHuC+lOtEyR+BKA2UQJOTReO4dv8oAt9cBGSN4gOItc7s07ggK+ZD+S2u
                    gSTpf5gXYnD9qq9TFuEyMFLJi9gWE/M3c/ZeeDyfTeVeYdbaq2qI3NNUtUYXB4Es0XsdzR
                    ZSfMhPggJaw8jhlMYFnJE+8O2gJiWrvky1NYhouRD2QzdTCwDoG58AK2Epnd0wIE1iDAHU
                    6ZVQaj1BawAAA8iwi+/UsIvv1AAAAAdzc2gtcnNhAAABAQDIybTnTV7wKcwY7vZzl+KEL/
                    8qF04ZqGC9sIZtLmY6TjN+m+j4m6LeEgk463oNbZq0BpujzBRcuD0of1xceYqzpIwa5F3u
                    bl91NpTM7Qb/9mbNfnWmGpJi/omxSf+8rGo55W0hbNce4L6U60TJH4EoDZRAk5NF47h2/y
                    gC31wEZI3iA4i1zuzTuCAr5kP5La6BJOl/mBdicP2qr1MW4TIwUsmL2BYT8zdz9l54PJ9N
                    5V5h1tqraojc01S1RhcHgSzRex3NFlJ8yE+CAlrDyOGUxgWckT7w7aAmJau+TLU1iGi5EP
                    ZDN1MLAOgbnwArYSmd3TAgTWIMAdTplVBqPUFrAAAAAwEAAQAAAQEAhe/vad/1tZTcHcHB
                    yqgFpRHzT1uOcJUeO0r20PwDm18xAIL2LGh9g09asYp6t1xmtzI1PlVTO+p2eX5D2TgGaw
                    EXqJSvh+4+ZQ0Mw4pVggcW2ntB9ZSCE+Ehbo8jNfN5RLejTYmyElnvJ52tG9CVMmekflMz
                    CYr3MQHR6eCfHBnbOgdMFiIyTgFliT1MAZBlWRtVJDQkr8DZMBjN1qoVHldLISl2nREvVt
                    b6TM1gCCp3fuey6+pe5BOm5gn/FjxOXiOiBrmfAu0Wu5ITnblCeje9Y/z0dHJNgKxhKfuo
                    hp78EE+fgHwVpUMeAunId9uRBwu8/u8eewtb7tMcYyK82QAAAIB8rhliWV/AOJqk+RhadS
                    uF+640Zekk8dw0EFQiyYeK9IABi+WGs0+XTd3a0k/bUUM0jxxa2os1fSsnojOhMCYpLt5A
                    UzmVWENG4xixscX0xdtJeYI91/Q7JuPmRbR2rGCL76WGyVnFvrKfpih1IgUKd9xkMT32WN
                    yp/rSKab78sQAAAIEA/M+MJjP+v1bA/2efBD70Vp4JgtnrsHIP80ZQOWxVWT+4Ea17OlBR
                    k+AfU1vJsrS9yLAk4LqHc3Zx6P3kd1sVvb9+dkIvQwy189T+sc7f4karRg9msu/aoAuzNE
                    LsaI9VieYN2eF6ET243G8SUA6rKSCpvGDicVEjbbYI8PAaEE8AAACBAMtSJsXF2cFaWOrd
                    pBYI3ZsseI9mlLXCX1Y+P/6QBo7U51/Vw0vLjLgyHyVGveLH26Fr0/b07QWoI3XQSXA3ZO
                    asXVVgiyAEsUaqxEr0NsqACTfYA3reHcIFD/FthDRYh5a5sXzBtRHeqDhsmV0Vj42YAqq2
                    baewZMKBL1QECTolAAAADHJrb3NlZ2lAbDQ4MAECAwQFBg==
                    -----END OPENSSH PRIVATE KEY-----
```

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
