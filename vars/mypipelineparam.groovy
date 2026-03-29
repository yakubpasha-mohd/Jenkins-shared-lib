def call(Map config = [:]) {

    
    def appName = config.appName ?: "java-app"
    def repoUrl = config.repoUrl ?: ""
    def branch  = config.branch ?: "main"
    def envName = config.environment ?: "dev"

    pipeline {
        agent any

        tools {
            maven 'Maven3'
            jdk 'openjdk-17'
        }

        environment {
            APP_NAME = "${appName}"
            DEPLOY_ENV = "${envName}"
        }

        stages {

            stage('Checkout') {
                steps {
                    echo "Checking out from ${repoUrl} (branch: ${branch})"
                    git url: repoUrl, branch: branch
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

            stage('Deploy') {
                steps {
                    echo "Deploying ${APP_NAME} to ${DEPLOY_ENV}"
                    
                    script {
                        if (envName == 'dev') {
                            echo "Deploying to DEV environment"
                        } else if (envName == 'qa') {
                            echo "Deploying to QA environment"
                        } else if (envName == 'prod') {
                            echo "🚀 Deploying to PROD environment"
                        } else {
                            error "Invalid environment: ${envName}"
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "Pipeline Success ✅ (${DEPLOY_ENV})"
            }
            failure {
                echo "Pipeline Failed ❌ (${DEPLOY_ENV})"
            }
        }
    }
}
