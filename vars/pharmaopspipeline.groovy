def call(Map config = [:]) {

    def appName    = config.appName ?: "pharmaops"
    def dockerRepo = config.dockerRepo ?: "${DOCKER_USER}/${appName}"
    def repoUrl    = config.repoUrl ?: "https://github.com/yakubpasha-mohd/pharmaops.git"

    pipeline {
     agent { label 'jenkins-slave' }

    parameters {
        choice(
            name: 'ENV',
            choices: ['dev', 'test'],
            description: 'Select deployment environment'
        )

        choice(
            name: 'SERVICE',
            choices: ['simple-java-app', 'payment-service', 'user-service'],
            description: 'Select service to deploy'
        )
    }

    environment {
        APP_NAME   = "${params.SERVICE}"
        IMAGE_NAME = "myptech08/${params.SERVICE}"
        IMAGE_TAG  = "${BUILD_NUMBER}"

        SONARQUBE_SERVER      = "SonarQube"
        DOCKER_CREDENTIALS_ID = "dockerhub-creds"
    }

    tools {
        jdk 'jdk17'
        maven 'maven-3.9.6'
    }

    stages {

        stage('Checkout') {
            steps {
                git branch: 'master',
                    url: 'https://github.com/yakubpasha-mohd/simple-java-maven-app.git'
            }
        }

        stage('Build') {
            steps {
                sh '''
                    echo "Building ${APP_NAME}"
                    mvn clean package
                '''
            }
        }

        stage('SonarQube Analysis') {
            steps {
                script {
                    def scannerHome = tool 'sonar-scanner'
                    withSonarQubeEnv("${SONARQUBE_SERVER}") {
                        sh """
                            ${scannerHome}/bin/sonar-scanner \
                              -Dsonar.projectKey=${APP_NAME} \
                              -Dsonar.projectName=${APP_NAME} \
                              -Dsonar.sources=. \
                              -Dsonar.java.binaries=target/classes
                        """
                    }
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 10, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Archive Artifacts') {
            steps {
                archiveArtifacts artifacts: 'target/*.jar'
            }
        }

        stage('Docker Build') {
            steps {
                sh """
                    echo "Building Docker image..."
                    docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .
                    docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest
                """
            }
        }

        stage('Trivy Scan') {
            steps {
                sh """
                    echo "Running Trivy scan..."
                    docker run --rm \
                      -v /var/run/docker.sock:/var/run/docker.sock \
                      -v \$WORKSPACE:/workspace \
                      aquasec/trivy:latest image \
                      --format table \
                      --output /workspace/trivy-report.txt \
                      ${IMAGE_NAME}:${IMAGE_TAG}
                """
                archiveArtifacts artifacts: 'trivy-report.txt'
            }
        }

        stage('Docker Push') {
            steps {
                script {
                    docker.withRegistry('', "${DOCKER_CREDENTIALS_ID}") {
                        sh """
                            echo "Pushing image..."
                            docker push ${IMAGE_NAME}:${IMAGE_TAG}
                            docker push ${IMAGE_NAME}:latest
                        """
                    }
                }
            }
        }

        stage('Deploy') {
            steps {
                sh """
                    echo "Deploying ${APP_NAME} to ${params.ENV}"

                    if [ "${params.ENV}" = "dev" ]; then
                        docker rm -f ${APP_NAME}-dev || true
                        docker run -d \
                          --name ${APP_NAME}-dev \
                          -p 8081:8080 \
                          -e SPRING_PROFILES_ACTIVE=dev \
                          ${IMAGE_NAME}:${IMAGE_TAG}
                    else
                        docker rm -f ${APP_NAME}-test || true
                        docker run -d \
                          --name ${APP_NAME}-test \
                          -p 8082:8080 \
                          -e SPRING_PROFILES_ACTIVE=test \
                          ${IMAGE_NAME}:${IMAGE_TAG}
                    fi
                """
            }
        }
    }

    post {
        success {
            echo "Pipeline completed successfully ✅ (${params.SERVICE} -> ${params.ENV})"
        }
        failure {
            echo "Pipeline Failed ❌ (${params.SERVICE} -> ${params.ENV})"
        }
        always {
            cleanWs()
        }
    }
}
}
