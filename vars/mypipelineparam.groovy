def call(Map config = [:]) {

    def appName = config.appName ?: "java-app"

    pipeline {
        agent any

        parameters {
            string(name: 'REPO_URL', defaultValue: '', description: 'Git Repo URL')
            string(name: 'BRANCH', defaultValue: 'master', description: 'Git Branch')
            choice(name: 'ENV', choices: ['dev', 'qa', 'prod'], description: 'Environment')
        }

        environment {
            APP_NAME = "${appName}"
        }

        stages {

            stage('Checkout') {
                steps {
                    git url: params.REPO_URL, branch: params.BRANCH
                }
            }

            stage('Build') {
                steps {
                    sh 'mvn clean package'
                }
            }

            stage('Deploy') {
                steps {
                    echo "Deploying ${APP_NAME} to ${params.ENV}"
                }
            }
        }
    }
}
