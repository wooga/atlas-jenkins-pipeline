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
@Library('github.com/wooga/atlas-jenkins-pipeline@1.x') _
pipeline {
    environment {
        SONAR_TOKEN = credentials("atlas_plugins_sonar_token")
        SONAR_PROJECT_NAME = "atlas-jenkins-pipeline"
        SONAR_PROJECT_KEY = "wooga_atlas-jenkins-pipeline"
    }
    agent any
    stages {
        stage("Check") {
            steps { gradleWrapper "check" }
            post {
                always {
                    gradleWrapper "jacocoTestReport sonarqube " +
                            "-Dsonar.projectKey=${SONAR_PROJECT_KEY} " +
                            "-Dsonar.projectName=${SONAR_PROJECT_NAME} " +
                            "-Dsonar.login=${SONAR_TOKEN} " +
                            "-Dsonar.host.url=${SONAR_HOST} " +
                            "-Dsonar.sources=src/,vars/ " +
                            "-Dsonar.tests=test/ " +
                            "-Dsonar.jacoco.reportPaths=build/jacoco/test.exec"
                }
                cleanup {
                    cleanWs()
                }
            }
        }
    }
}
