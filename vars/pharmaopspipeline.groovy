def call(Map config = [:]) {

    def appName    = config.appName ?: "pharmaops"
    def dockerRepo = config.dockerRepo ?: "${DOCKER_USER}/${appName}"
    def repoUrl    = config.repoUrl ?: "https://github.com/yakubpasha-mohd/pharmaops.git"

    pipeline {
        agent { label 'jenkins-slave' }

        tools {
            maven 'maven-3.9.6'
            jdk 'openjdk-17'
        }

        parameters {
            string(name: 'BRANCH',
                   defaultValue: 'main',
                   description: 'Git Branch')

            choice(
                name: 'SERVICE_NAME',
                choices: [
                    'all',
                    'api-gateway',
                    'auth-service',
                    'user-service',
                    'product-service',
                    'order-service'
                ],
                description: 'Select service to build/deploy'
            )

            choice(name: 'ENV',
                   choices: ['dev', 'qa', 'prod'],
                   description: 'Deployment Environment')
        }

        environment {
            APP_NAME     = "${appName}"
            DOCKER_REPO  = "${dockerRepo}"
            REPO_URL     = "${repoUrl}"
            IMAGE_TAG    = "${BUILD_NUMBER}"
            SERVICES_DIR = "services"
        }

        stages {

            stage('Checkout') {
                steps {
                    git branch: params.BRANCH, url: env.REPO_URL
                }
            }

            stage('Detect Services') {
                steps {
                    script {
                        if (params.SERVICE_NAME != "all") {
                            SERVICES = [params.SERVICE_NAME]
                        } else {
                            SERVICES = sh(
                                script: """
                                find ${SERVICES_DIR} -maxdepth 1 -mindepth 1 -type d | xargs -n 1 basename
                                """,
                                returnStdout: true
                            ).trim().split('\n')
                        }

                        echo "Services to process: ${SERVICES.join(', ')}"
                    }
                }
            }

            stage('Build Services') {
                steps {
                    script {
                        def builds = [:]

                        for (svc in SERVICES) {
                            builds[svc] = {
                                dir("${SERVICES_DIR}/${svc}") {
                                    sh '/opt/maven/bin/mvn clean package'
                                }
                            }
                        }

                        parallel builds
                    }
                }
            }

            stage('Unit Tests') {
                steps {
                    script {
                        def tests = [:]

                        for (svc in SERVICES) {
                            tests[svc] = {
                                dir("${SERVICES_DIR}/${svc}") {
                                    sh '/opt/maven/bin/mvn test'
                                    junit 'target/surefire-reports/*.xml'
                                }
                            }
                        }

                        parallel tests
                    }
                }
            }

            stage('SonarQube Analysis') {
                steps {
                    script {
                        def scans = [:]
                        def scannerHome = tool 'sonar-scanner'

                        for (svc in SERVICES) {
                            scans[svc] = {
                                dir("${SERVICES_DIR}/${svc}") {
                                    withSonarQubeEnv('SonarQube') {
                                        sh """
                                        ${scannerHome}/bin/sonar-scanner \
                                        -Dsonar.projectKey=${svc} \
                                        -Dsonar.projectName=${svc} \
                                        -Dsonar.sources=. \
                                        -Dsonar.java.binaries=target/classes
                                        """
                                    }
                                }
                            }
                        }

                        parallel scans
                    }
                }
            }

            stage('Quality Gate') {
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            stage('Archive Artifacts') {
                steps {
                    archiveArtifacts artifacts: '**/target/*.jar', fingerprint: true
                }
            }

            stage('Docker Build') {
                steps {
                    script {
                        def builds = [:]

                        for (svc in SERVICES) {
                            builds[svc] = {
                                dir("${SERVICES_DIR}/${svc}") {
                                    sh """
                                    docker build -t ${DOCKER_REPO}/${svc}:${IMAGE_TAG} .
                                    """
                                }
                            }
                        }

                        parallel builds
                    }
                }
            }

            stage('Trivy Scan') {
                steps {
                    script {
                        def scans = [:]

                        for (svc in SERVICES) {
                            scans[svc] = {
                                sh """
                                trivy image \
                                --format table \
                                --output ${svc}-trivy-report.txt \
                                ${DOCKER_REPO}/${svc}:${IMAGE_TAG}
                                """
                                archiveArtifacts artifacts: "${svc}-trivy-report.txt"
                            }
                        }

                        parallel scans
                    }
                }
            }

            stage('Docker Push') {
                steps {
                    script {
                        withCredentials([usernamePassword(
                            credentialsId: 'docker-cred',
                            usernameVariable: 'DOCKER_USER',
                            passwordVariable: 'DOCKER_PASS'
                        )]) {

                            sh '''
                            echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
                            '''

                            def pushes = [:]

                            for (svc in SERVICES) {
                                pushes[svc] = {
                                    sh """
                                    docker push ${DOCKER_REPO}/${svc}:${IMAGE_TAG}
                                    """
                                }
                            }

                            parallel pushes
                        }
                    }
                }
            }

            stage('Deploy') {
                steps {
                    script {
                        for (svc in SERVICES) {
                            if (params.ENV == 'dev') {
                                sh "docker-compose up -d ${svc}"
                            } else if (params.ENV == 'qa') {
                                sh "docker-compose -f docker-compose.qa.yml up -d ${svc}"
                            } else if (params.ENV == 'prod') {
                                input "Approve Production Deployment for ${svc}?"
                                sh "docker-compose -f docker-compose.prod.yml up -d ${svc}"
                            }
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
            always {
                cleanWs()
            }
        }
    }
}
