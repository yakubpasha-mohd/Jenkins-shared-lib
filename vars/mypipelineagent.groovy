def call(Map config = [:]) {

    def appName = config.appName ?: "java-app"

    pipeline {
        agent { label 'jenkins-slave' }

        tools {
            maven 'Maven3'
            jdk 'openjdk-17'
        }

        environment {
            APP_NAME = "${appName}"
        }
        stages {
        stage('Verify') {
            steps {
                sh 'echo $JAVA_HOME'
                sh 'mvn -version'
            }
        }
    }
        stages {

            stage('Checkout') {
                steps {
                    git url: config.repoUrl, branch: 'master'
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
