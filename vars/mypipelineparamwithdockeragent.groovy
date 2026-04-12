def call(Map config = [:]) {

    def appName = config.appName ?: "java-app"
    def dockerRepo = config.dockerRepo ?: "${DOCKER_USER}/${appName}"
    pipeline {
        agent { label 'jenkins-slave' }

        tools {
            maven 'maven-3.9'
            jdk 'openjdk-17'
        }

        parameters {
            string(name: 'REPO_URL',
                   defaultValue: 'https://github.com/yakubpasha-mohd/simple-java-maven-app.git',
                   description: 'Git Repo URL')

            string(name: 'BRANCH',
                   defaultValue: 'master',
                   description: 'Git Branch')

            choice(name: 'ENV',
                   choices: ['dev','qa','prod'],
                   description: 'Deployment Environment')
        }

        environment {
            APP_NAME   = "${appName}"
            DEPLOY_ENV = "${params.ENV}"
            IMAGE_NAME = "${dockerRepo}"
            IMAGE_TAG  = "${BUILD_NUMBER}"
            MVN_HOME = tool(name: 'maven-3.9', type: 'maven')
        }

        stages {

            stage('Validate Input') {
                steps {
                    script {
                        if (!params.REPO_URL?.trim()) {
                            error "❌ REPO_URL cannot be empty"
                        }
                    }
                }
            }

            stage('Checkout') {
                steps {
                    echo "Checking out ${params.REPO_URL} (${params.BRANCH})"
                    git url: params.REPO_URL, branch: params.BRANCH
                }
            }
            stage('Debug Agent') {
    steps {
        sh 'hostname'
        sh 'java -version'
        sh 'mvn -version || echo "Maven not found"'
    }
}
            stage('Build') {
               steps {
                   sh "${MVN_HOME}/bin/mvn clean package"
               }
       /*
                   script {
            def mvnHome = tool name: 'maven-3.9', type: 'maven'
            sh """
                ${mvnHome}/bin/mvn -version
                ${mvnHome}/bin/mvn clean package
            """
        } */
            }
            }

            stage('Test') {
                steps {
                //    sh '${mvnHome}/bin/mvn test'
                      sh "${MVN_HOME}/bin/mvn test"
                }
            }

            stage('Archive Artifacts') {
                steps {
                    archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
                }
            }

            // ✅ NEW: Docker Build
            stage('Docker Build') {
                steps {
                    script {
                        echo "Building Docker image ${IMAGE_NAME}:${IMAGE_TAG}"
                        sh """
                        docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .
                        """
                    }
                }
            }

            // ✅ NEW: Docker Push
            stage('Docker Push') {
    steps {
        script {
            withCredentials([usernamePassword(
                credentialsId: 'docker-cred',
                usernameVariable: 'DOCKER_USER',
                passwordVariable: 'DOCKER_PASS'
            )]) {

                def imageName = "${DOCKER_USER}/${APP_NAME}"

                sh '''
                echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
                docker tag $IMAGE_NAME:$IMAGE_TAG $DOCKER_USER/$APP_NAME:$IMAGE_TAG
                docker push $DOCKER_USER/$APP_NAME:$IMAGE_TAG
                '''
            }
        }
    }
}

            stage('Deploy') {
                steps {
                    echo "Deploying ${APP_NAME} to ${DEPLOY_ENV}"

                    script {
                        if (params.ENV == 'dev') {
                            echo "Deploying to DEV environment"
                        } else if (params.ENV == 'qa') {
                            echo "Deploying to QA environment"
                        } else if (params.ENV == 'prod') {
                            echo "🚀 Deploying to PROD environment"
                        } else {
                            error "Invalid environment: ${params.ENV}"
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "Pipeline Success ✅ (${params.ENV})"
            }
            failure {
                echo "Pipeline Failed ❌ (${params.ENV})"
            }
        }
    }
}
