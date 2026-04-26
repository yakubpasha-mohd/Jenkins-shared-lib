def call(Map config = [:]) {

    def appName = config.appName ?: "java-app"
    def dockerRepo = config.dockerRepo ?: "${DOCKER_USER}/${appName}"

    pipeline {
        agent { label 'jenkins-slave' }

        tools {
            maven 'maven-3.9.6'
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

            // SonarQube config (must exist in Jenkins)
            SONARQUBE_SERVER = 'SonarQubeServer'
        }

        stages {
            stage('Debug Maven') {
    steps {
        sh '''
            echo "PATH=$PATH"
            which mvn || true
            type -a mvn || true
            mvn --version || true
            ./mvnw --version || true
            env | grep -i maven || true
        '''
    }
}
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

            stage('Build') {
                steps {
                    sh '/opt/maven/bin/mvn clean package'
                }
            }

            stage('Test') {
                steps {
                    sh '/opt/maven/bin/mvn clean package'
                }
            }

          stage('SonarQube Analysis') {
    steps {
        script {
            def scannerHome = tool 'sonar-scanner'

            withSonarQubeEnv('SonarQube') {
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
            script {
                def qg = waitForQualityGate()
                if (qg.status != 'OK') {
                    echo "Quality Gate failed: ${qg.status}"
                }
            }
        }
    }
}

            stage('Archive Artifacts') {
                steps {
                    archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
                }
            }

            // ✅ Docker Build
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

            // ✅ NEW: Trivy Scan (Image Scan)
            stage('Trivy Scan') {
                steps {
                    script {
                        sh """
                        trivy image --severity HIGH,CRITICAL \
                        --exit-code 1 \
                        ${IMAGE_NAME}:${IMAGE_TAG}
                        """
                    }
                }
            }

            // ✅ Docker Push
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
