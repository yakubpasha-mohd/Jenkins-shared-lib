def call(Map config = [:]) {

    pipeline {
        agent any

        tools {
            maven 'Maven3'
            jdk 'openjdk-17'
        }

        environment {
            APP_NAME = config.appName ?: "java-app"
        }

        stages {

            stage('Checkout') {
                steps {
                    git url: config.repoUrl, branch: 'main'
                }
            }

            stage('Build') {
                steps {
                    sh 'mvn clean package'
                }
            }

            stage('Test') {
                steps {
                    sh 'mvn test'
                }
            }

            stage('Archive Artifacts') {
                steps {
                    archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
                }
            }

            stage('Deploy (Local)') {
                steps {
                    echo "Deploying ${APP_NAME}"
                    sh 'ls -l target/'
                }
            }
        }

        post {
            success {
                echo 'Pipeline Success ✅'
            }
            failure {
                echo 'Pipeline Failed ❌'
            }
        }
    }
}
