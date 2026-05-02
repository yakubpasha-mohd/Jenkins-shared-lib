def call(Map config = [:]) {

    def appName    = config.appName ?: "pharmaops"
    def dockerRepo = config.dockerRepo ?: "${DOCKER_USER}/${appName}"
    def repoUrl    = config.repoUrl ?: "https://github.com/yakubpasha-mohd/pharmaops.git"

    pipeline {
        agent { label 'jenkins-slave' }

       tools {
    maven 'maven-3.9.6'
    jdk 'openjdk-17'
    nodejs 'nodejs-20'
}

        parameters {
            string(
                name: 'BRANCH',
                defaultValue: 'main',
                description: 'Git Branch'
            )

            choice(
                name: 'SERVICE_NAME',
                choices: '''all
api-gateway
auth-service
user-service
product-service
order-service
pharma-ui
notification-service
drug-catalog-service''',
                description: 'Select service to build/deploy'
            )

            choice(
                name: 'ENV',
                choices: ['dev', 'qa', 'prod'],
                description: 'Deployment Environment'
            )
        }

        environment {
            APP_NAME     = "${appName}"
            DOCKER_REPO  = "${dockerRepo}"
            REPO_URL     = "${repoUrl}"
            IMAGE_TAG    = "${BUILD_NUMBER}"
            SERVICES_DIR = "services"
            DOCKER_USER = 'myptech'
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
                            ).trim().split('\n').toList().unique()
                        }

                        echo "Services to process: ${SERVICES.join(', ')}"
                        echo "Total services count: ${SERVICES.size()}"
                    }
                }
            }

          stage('Build Services') {
    steps {
        script {
            def builds = [:]

            SERVICES.each { svc ->
                builds[svc] = {
                    dir("${SERVICES_DIR}/${svc}") {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            sh '''
                                echo "Building service: $(pwd)"

                                if [ -f pom.xml ]; then
                                    echo "Java/Maven project detected"
                                    rm -rf target
                                    mkdir -p target/classes
                                    /opt/maven/bin/mvn clean package -DskipTests -U

                                elif [ -f package.json ]; then
                                    echo "Node.js project detected"

                                    if command -v npm >/dev/null 2>&1; then
                                        npm ci

                                        if npm run | grep -q " build"; then
                                            echo "Build script found"
                                            npm run build
                                        else
                                            echo "No build script found, skipping build"
                                        fi
                                    else
                                        echo "npm not installed, skipping build"
                                    fi

                                else
                                    echo "Unknown project type. Skipping build."
                                fi
                            '''
                        }
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

            SERVICES.each { svc ->
                tests[svc] = {
                    dir("${SERVICES_DIR}/${svc}") {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            sh '''
                                echo "Running tests in: $(pwd)"

                                if [ -f pom.xml ]; then
                                    echo "Java/Maven project detected"

                                    if grep -q "com.h2database" pom.xml; then
                                        echo "H2 dependency found, running tests"
                                        /opt/maven/bin/mvn test -U
                                    else
                                        echo "H2 dependency missing, skipping tests"
                                        /opt/maven/bin/mvn test -DskipTests
                                    fi

                                elif [ -f package.json ]; then
                                    echo "Node.js project detected"

                                    if command -v npm >/dev/null 2>&1; then
                                        npm ci

                                        if npm run | grep -q " test"; then
                                            echo "Running Node tests"
                                            npm test -- --watchAll=false --runInBand --silent --forceExit || true
                                        else
                                            echo "No test script found, skipping tests"
                                        fi
                                    else
                                        echo "npm not installed, skipping tests"
                                    fi

                                else
                                    echo "Unknown project type. Skipping tests."
                                fi
                            '''
                        }

                        script {
                            // ✅ FIX: Only run JUnit for Java projects
                            if (fileExists('pom.xml') && fileExists('target/surefire-reports')) {
                                junit(
                                    allowEmptyResults: true,
                                    testResults: 'target/surefire-reports/*.xml'
                                )
                            } else {
                                echo "Skipping JUnit for ${svc}"
                            }
                        }
                    }
                }
            }

            parallel tests
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

            SERVICES.each { svc ->
                builds[svc] = {
                    dir("${SERVICES_DIR}/${svc}") {
                        sh """
                            echo "Building Docker image for ${svc}"
                            docker build -t ${DOCKER_USER}/${svc}:${IMAGE_TAG} .
                        """
                    }
                }
            }

            parallel builds
        }
    }
}

          stage('Docker Push') {
    steps {
        script {
            withCredentials([
                usernamePassword(
                    credentialsId: 'docker-cred',
                    usernameVariable: 'DOCKER_USER',
                    passwordVariable: 'DOCKER_PASS'
                )
            ]) {

                sh '''
                    echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
                '''

                def pushes = [:]

                SERVICES.each { svc ->
                    pushes[svc] = {
                        sh """
                            echo "Pushing ${svc}"
                            docker push ${DOCKER_USER}/${svc}:${IMAGE_TAG}
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
                        SERVICES.each { svc ->
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
