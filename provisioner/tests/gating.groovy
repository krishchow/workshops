pipeline {

    agent { label 'jenkins-jnlp-agent' }

    options {
        timestamps()
        timeout(time: 3, unit: 'HOURS')
        buildDiscarder(logRotator(daysToKeepStr: '10'))
    }

    stages {

        stage('Build Information') {
            steps {
                script {
                    TOWER_VERSION = '3.6.4'
                    DOTLESS_TOWER_VERSION = TOWER_VERSION.replace('.', '').trim()
                }
                echo """Tower Version under test: ${TOWER_VERSION}
Workshop branch under test: ${env.BRANCH_NAME} | ${env.CHANGE_NAME}
Build Tag: ${env.BUILD_TAG}"""
            }
        }

        stage('Prep Environment') {
            steps {
                withCredentials([file(credentialsId: 'workshops_tower_license', variable: 'TOWER_LICENSE')]) {
                    sh 'cp ${TOWER_LICENSE} provisioner/tower_license.json'
                }
                sh 'pip install netaddr pywinrm requests requests-credssp boto'
                sh 'yum -y install sshpass'
                sh 'ansible --version | tee ansible_version.log'
                archiveArtifacts artifacts: 'ansible_version.log'
                script {
                    ADMIN_PASSWORD = sh(
                        script: "cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w 16 | head -n 1",
                        returnStdout: true
                    ).trim()

                    if (env.CHANGE_ID) {
                        ANSIBLE_WORKSHOPS_REFSPEC = "+refs/pull/${env.CHANGE_ID}/head:refs/remotes/origin/${env.BRANCH_NAME}"
                    } else {
                        ANSIBLE_WORKSHOPS_REFSPEC = "+refs/heads/${env.BRANCH_NAME}:refs/remotes/origin/${env.BRANCH_NAME}"
                    }
                }

                sh """tee provisioner/tests/ci-common.yml << EOF
tower_installer_url: https://releases.ansible.com/ansible-tower/setup/ansible-tower-setup-${TOWER_VERSION}-1.tar.gz
admin_password: ${ADMIN_PASSWORD}
ansible_workshops_refspec: ${ANSIBLE_WORKSHOPS_REFSPEC}
ansible_workshops_version: ${env.BRANCH_NAME}
ec2_name_prefix: tqe-{{ workshop_type }}-tower${DOTLESS_TOWER_VERSION}-${env.BRANCH_NAME}-${env.BUILD_ID}
EOF
"""
            }
        }

        stage('Workshop Type') {
            parallel {
                stage('RHEL') {
                    steps {
                        script {
                            stage('RHEL-deploy') {
                                withCredentials([string(credentialsId: 'workshops_aws_access_key', variable: 'AWS_ACCESS_KEY'),
                                                 string(credentialsId: 'workshops_aws_secret_key', variable: 'AWS_SECRET_KEY')]) {
                                    withEnv(["AWS_SECRET_KEY=${AWS_SECRET_KEY}",
                                             "AWS_ACCESS_KEY=${AWS_ACCESS_KEY}",
                                             "ANSIBLE_CONFIG=provisioner/ansible.cfg",
                                             "ANSIBLE_FORCE_COLOR=true"]) {
                                        sh """ansible-playbook provisioner/provision_lab.yml \
                                               -e @provisioner/tests/vars.yml \
                                               -e @provisioner/tests/ci-common.yml \
                                               -e @provisioner/tests/ci-rhel.yml"""
                                    }
                                }
                            }
                        }
                        script {
                            stage('RHEL-verify') {
                                withCredentials([string(credentialsId: 'workshops_aws_access_key', variable: 'AWS_ACCESS_KEY'),
                                                 string(credentialsId: 'workshops_aws_secret_key', variable: 'AWS_SECRET_KEY')]) {
                                    withEnv(["AWS_SECRET_KEY=${AWS_SECRET_KEY}",
                                             "AWS_ACCESS_KEY=${AWS_ACCESS_KEY}",
                                             "ANSIBLE_CONFIG=provisioner/ansible.cfg",
                                             "ANSIBLE_FORCE_COLOR=true"]) {
                                        sh """ansible-playbook provisioner/tests/rhel_verify.yml \
                                                -i provisioner/tqe-rhel-tower${DOTLESS_TOWER_VERSION}-${env.BRANCH_NAME}-${env.BUILD_ID}/instructor_inventory.txt \
                                                --private-key=provisioner/tqe-rhel-tower${DOTLESS_TOWER_VERSION}-${env.BRANCH_NAME}-${env.BUILD_ID}/tqe-rhel-tower${DOTLESS_TOWER_VERSION}-${env.BRANCH_NAME}-${env.BUILD_ID}-private.pem \
                                                -e tower_password=${ADMIN_PASSWORD} -e workshop_name=tqe-rhel-tower${DOTLESS_TOWER_VERSION}-${env.BRANCH_NAME}-${env.BUILD_ID}"""
                                    }
                                }
                            }
                        }
                        script {
                            stage('RHEL-teardown') {
                                withCredentials([string(credentialsId: 'workshops_aws_access_key', variable: 'AWS_ACCESS_KEY'),
                                                 string(credentialsId: 'workshops_aws_secret_key', variable: 'AWS_SECRET_KEY')]) {
                                    withEnv(["AWS_SECRET_KEY=${AWS_SECRET_KEY}",
                                             "AWS_ACCESS_KEY=${AWS_ACCESS_KEY}",
                                             "ANSIBLE_CONFIG=provisioner/ansible.cfg",
                                             "ANSIBLE_FORCE_COLOR=true"]) {
                                        sh """ansible-playbook provisioner/teardown_lab.yml \
                                                -e @provisioner/tests/vars.yml \
                                                -e @provisioner/tests/ci-common.yml \
                                                -e @provisioner/tests/ci-rhel.yml"""
                                    }
                                }
                            }
                        }
                    }
                }

                stage('Networking') {
                    steps {
                        script {
                            stage('networking-deploy') {
                                withCredentials([string(credentialsId: 'workshops_aws_access_key', variable: 'AWS_ACCESS_KEY'),
                                                 string(credentialsId: 'workshops_aws_secret_key', variable: 'AWS_SECRET_KEY')]) {
                                    withEnv(["AWS_SECRET_KEY=${AWS_SECRET_KEY}",
                                             "AWS_ACCESS_KEY=${AWS_ACCESS_KEY}",
                                             "ANSIBLE_CONFIG=provisioner/ansible.cfg",
                                             "ANSIBLE_FORCE_COLOR=true"]) {
                                        sh """ansible-playbook provisioner/provision_lab.yml \
                                                -e @provisioner/tests/vars.yml \
                                                -e @provisioner/tests/ci-common.yml \
                                                -e @provisioner/tests/ci-networking.yml"""
                                    }
                                }
                            }
                        }
                        script {
                            stage('networking-teardown') {
                                withCredentials([string(credentialsId: 'workshops_aws_access_key', variable: 'AWS_ACCESS_KEY'),
                                                 string(credentialsId: 'workshops_aws_secret_key', variable: 'AWS_SECRET_KEY')]) {
                                    withEnv(["AWS_SECRET_KEY=${AWS_SECRET_KEY}",
                                             "AWS_ACCESS_KEY=${AWS_ACCESS_KEY}",
                                             "ANSIBLE_CONFIG=provisioner/ansible.cfg",
                                             "ANSIBLE_FORCE_COLOR=true"]) {
                                        sh """ansible-playbook provisioner/teardown_lab.yml \
                                                -e @provisioner/tests/vars.yml \
                                                -e @provisioner/tests/ci-common.yml \
                                                -e @provisioner/tests/ci-networking.yml"""
                                    }
                                }
                            }
                        }
                    }
                }

                stage('F5') {
                    steps {
                        script {
                            stage('F5-deploy') {
                                withCredentials([string(credentialsId: 'workshops_aws_access_key', variable: 'AWS_ACCESS_KEY'),
                                                 string(credentialsId: 'workshops_aws_secret_key', variable: 'AWS_SECRET_KEY')]) {
                                    withEnv(["AWS_SECRET_KEY=${AWS_SECRET_KEY}",
                                             "AWS_ACCESS_KEY=${AWS_ACCESS_KEY}",
                                             "ANSIBLE_CONFIG=provisioner/ansible.cfg",
                                             "ANSIBLE_FORCE_COLOR=true"]) {
                                        sh """ansible-playbook provisioner/provision_lab.yml \
                                                -e @provisioner/tests/vars.yml \
                                                -e @provisioner/tests/ci-common.yml \
                                                -e @provisioner/tests/ci-f5.yml"""
                                    }
                                }
                            }
                        }
                        script {
                            stage('F5-exercises') {
                                sh "cat provisioner/tqe-f5-tower${DOTLESS_TOWER_VERSION}-${env.BRANCH_NAME}-${env.BUILD_ID}/student1-instances.txt | grep -A 1 control | tail -n 1 | cut -d' ' -f 2 | cut -d'=' -f2 | tee control_host"
                                CONTROL_NODE_HOST = readFile('control_host').trim()
                                RUN_ALL_PLAYBOOKS = 'find . -name "*.yml" -o -name "*.yaml" | grep -v "2.0" | sort | xargs -I {} bash -c "echo {} && ANSIBLE_FORCE_COLOR=true ansible-playbook {}"'
                                sh "sshpass -p '${ADMIN_PASSWORD}' ssh -o StrictHostKeyChecking=no student1@${CONTROL_NODE_HOST} 'cd f5-workshop && ${RUN_ALL_PLAYBOOKS}'"
                            }
                        }
                        script {
                            stage('F5-teardown') {
                                withCredentials([string(credentialsId: 'workshops_aws_access_key', variable: 'AWS_ACCESS_KEY'),
                                                 string(credentialsId: 'workshops_aws_secret_key', variable: 'AWS_SECRET_KEY')]) {
                                    withEnv(["AWS_SECRET_KEY=${AWS_SECRET_KEY}",
                                             "AWS_ACCESS_KEY=${AWS_ACCESS_KEY}",
                                             "ANSIBLE_CONFIG=provisioner/ansible.cfg",
                                             "ANSIBLE_FORCE_COLOR=true"]) {
                                        sh """ansible-playbook provisioner/teardown_lab.yml \
                                                -e @provisioner/tests/vars.yml \
                                                -e @provisioner/tests/ci-common.yml \
                                                -e @provisioner/tests/ci-f5.yml"""
                                    }
                                }
                            }
                        }
                    }
                }
                stage('security') {
                    steps {
                        script {
                            stage('security-deploy') {
                                withCredentials([string(credentialsId: 'workshops_aws_access_key', variable: 'AWS_ACCESS_KEY'),
                                                 string(credentialsId: 'workshops_aws_secret_key', variable: 'AWS_SECRET_KEY')]) {
                                    withEnv(["AWS_SECRET_KEY=${AWS_SECRET_KEY}",
                                             "AWS_ACCESS_KEY=${AWS_ACCESS_KEY}",
                                             "ANSIBLE_CONFIG=provisioner/ansible.cfg",
                                             "ANSIBLE_FORCE_COLOR=true"]) {
                                        sh """ansible-playbook provisioner/provision_lab.yml \
                                                -e @provisioner/tests/vars.yml \
                                                -e @provisioner/tests/ci-common.yml \
                                                -e @provisioner/tests/ci-security.yml"""
                                    }
                                }
                            }
                        }
                        script {
                            stage('security-verify') {
                                withCredentials([string(credentialsId: 'workshops_aws_access_key', variable: 'AWS_ACCESS_KEY'),
                                                 string(credentialsId: 'workshops_aws_secret_key', variable: 'AWS_SECRET_KEY')]) {
                                    withEnv(["AWS_SECRET_KEY=${AWS_SECRET_KEY}",
                                             "AWS_ACCESS_KEY=${AWS_ACCESS_KEY}",
                                             "ANSIBLE_CONFIG=provisioner/ansible.cfg",
                                             "ANSIBLE_FORCE_COLOR=true"]) {
                                        sh """ansible-playbook provisioner/tests/security_verify.yml \
                                                -i provisioner/tqe-security-tower${DOTLESS_TOWER_VERSION}-${env.BRANCH_NAME}-${env.BUILD_ID}/instructor_inventory.txt \
                                                --private-key=provisioner/tqe-security-tower${DOTLESS_TOWER_VERSION}-${env.BRANCH_NAME}-${env.BUILD_ID}/tqe-security-tower${DOTLESS_TOWER_VERSION}-${env.BRANCH_NAME}-${env.BUILD_ID}-private.pem"""
                                    }
                                }
                            }
                        }
                        script {
                            stage('security-exercises') {
                                withCredentials([string(credentialsId: 'workshops_aws_access_key', variable: 'AWS_ACCESS_KEY'),
                                                 string(credentialsId: 'workshops_aws_secret_key', variable: 'AWS_SECRET_KEY')]) {
                                    withEnv(["AWS_SECRET_KEY=${AWS_SECRET_KEY}",
                                             "AWS_ACCESS_KEY=${AWS_ACCESS_KEY}",
                                             "ANSIBLE_CONFIG=provisioner/ansible.cfg",
                                             "ANSIBLE_FORCE_COLOR=true"]) {
                                        sh """ansible-playbook provisioner/tests/security_exercise_21.yml \
                                                -i provisioner/tqe-security-tower${DOTLESS_TOWER_VERSION}-${env.BRANCH_NAME}-${env.BUILD_ID}/student1-instances.txt"""
                                    }
                                }
                            }
                        }
                        script {
                            stage('security-teardown') {
                                withCredentials([string(credentialsId: 'workshops_aws_access_key', variable: 'AWS_ACCESS_KEY'),
                                                 string(credentialsId: 'workshops_aws_secret_key', variable: 'AWS_SECRET_KEY')]) {
                                    withEnv(["AWS_SECRET_KEY=${AWS_SECRET_KEY}",
                                             "AWS_ACCESS_KEY=${AWS_ACCESS_KEY}",
                                             "ANSIBLE_CONFIG=provisioner/ansible.cfg",
                                             "ANSIBLE_FORCE_COLOR=true"]) {
                                        sh """ansible-playbook provisioner/teardown_lab.yml \
                                                -e @provisioner/tests/vars.yml \
                                                -e @provisioner/tests/ci-common.yml \
                                                -e @provisioner/tests/ci-security.yml"""
                                    }
                                }
                            }
                        }
                    }
                }
                stage('windows') {
                    steps {
                        script {
                            stage('windows-deploy') {
                                withCredentials([string(credentialsId: 'workshops_aws_access_key', variable: 'AWS_ACCESS_KEY'),
                                                 string(credentialsId: 'workshops_aws_secret_key', variable: 'AWS_SECRET_KEY')]) {
                                    withEnv(["AWS_SECRET_KEY=${AWS_SECRET_KEY}",
                                             "AWS_ACCESS_KEY=${AWS_ACCESS_KEY}",
                                             "ANSIBLE_CONFIG=provisioner/ansible.cfg",
                                             "ANSIBLE_FORCE_COLOR=true"]) {
                                        sh """ansible-playbook provisioner/provision_lab.yml \
                                                -e @provisioner/tests/vars.yml \
                                                -e @provisioner/tests/ci-common.yml \
                                                -e @provisioner/tests/ci-windows.yml"""
                                    }
                                }
                            }
                        }
                        script {
                            stage('windows-teardown') {
                                withCredentials([string(credentialsId: 'workshops_aws_access_key', variable: 'AWS_ACCESS_KEY'),
                                                 string(credentialsId: 'workshops_aws_secret_key', variable: 'AWS_SECRET_KEY')]) {
                                    withEnv(["AWS_SECRET_KEY=${AWS_SECRET_KEY}",
                                             "AWS_ACCESS_KEY=${AWS_ACCESS_KEY}",
                                             "ANSIBLE_CONFIG=provisioner/ansible.cfg",
                                             "ANSIBLE_FORCE_COLOR=true"]) {
                                        sh """ansible-playbook provisioner/teardown_lab.yml \
                                                -e @provisioner/tests/vars.yml \
                                                -e @provisioner/tests/ci-common.yml \
                                                -e @provisioner/tests/ci-windows.yml"""
                                    }
                                }
                            }
                        }
                    }
                }

            }
        }
    }

    post {
        cleanup {
            script {
                stage('Cleaning up') {
                    withCredentials([string(credentialsId: 'workshops_aws_access_key', variable: 'AWS_ACCESS_KEY'),
                                     string(credentialsId: 'workshops_aws_secret_key', variable: 'AWS_SECRET_KEY')]) {
                        withEnv(["AWS_SECRET_KEY=${AWS_SECRET_KEY}",
                                 "AWS_ACCESS_KEY=${AWS_ACCESS_KEY}",
                                 "ANSIBLE_CONFIG=provisioner/ansible.cfg",
                                 "ANSIBLE_FORCE_COLOR=true"]) {
                            sh "ansible-playbook provisioner/teardown_lab.yml -e @provisioner/tests/vars.yml -e @provisioner/tests/ci-common.yml -e @provisioner/tests/ci-rhel.yml"
                            sh "ansible-playbook provisioner/teardown_lab.yml -e @provisioner/tests/vars.yml -e @provisioner/tests/ci-common.yml -e @provisioner/tests/ci-networking.yml"
                            sh "ansible-playbook provisioner/teardown_lab.yml -e @provisioner/tests/vars.yml -e @provisioner/tests/ci-common.yml -e @provisioner/tests/ci-f5.yml"
                            sh "ansible-playbook provisioner/teardown_lab.yml -e @provisioner/tests/vars.yml -e @provisioner/tests/ci-common.yml -e @provisioner/tests/ci-security.yml"
                            sh "ansible-playbook provisioner/teardown_lab.yml -e @provisioner/tests/vars.yml -e @provisioner/tests/ci-common.yml -e @provisioner/tests/ci-windows.yml"
                        }
                    }
                }
            }
        }
    }
}
