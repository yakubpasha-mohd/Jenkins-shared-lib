def call(Map config = [:]) {

    def appName    = config.appName ?: "pharmaops"
    def repoUrl    = config.repoUrl ?: "https://github.com/yakubpasha-mohd/pharmaops.git"
      properties([
        parameters([
            string(name: 'BRANCH', defaultValue: 'main', description: 'Git Branch'),
            choice(name: 'SERVICE_NAME', choices: ['all','api-gateway','auth-service'], description: 'Service'),
            choice(name: 'ENV', choices: ['dev','qa','prod'], description: 'Env')
        ])
    ])
    pipeline {
        agent { label 'jenkins-slave' }

        tools {
            maven 'maven-3.9.6'
            jdk 'openjdk-17'
            nodejs 'nodejs-20'
        }

            environment {
            REPO_URL     = "${repoUrl}"
            IMAGE_TAG    = "${BUILD_NUMBER}"
            SERVICES_DIR = "services"
            DOCKER_USER  = 'myptech'
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
                        SERVICES = (params.SERVICE_NAME != "all") ?
                            [params.SERVICE_NAME] :
                            sh(
                                script: "find ${SERVICES_DIR} -maxdepth 1 -mindepth 1 -type d | xargs -n 1 basename",
                                returnStdout: true
                            ).trim().split('\n').toList().unique()

                        echo "Services: ${SERVICES}"
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
                                    sh '''
                                        echo "Building service: $(pwd)"

                                        if [ -f pom.xml ]; then
                                            mvn clean package -DskipTests -U

                                        elif [ -f package.json ]; then
                                            npm ci
                                            npm run build || echo "No build script"

                                        else
                                            echo "Unknown project"
                                        fi
                                    '''
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
                                    sh '''
                                        echo "Running tests"

                                        if [ -f pom.xml ]; then
                                            mvn test || true

                                        elif [ -f package.json ]; then
                                            npm ci
                                            npm test -- --watchAll=false --runInBand --silent --forceExit || true
                                        fi
                                    '''

                                    // Only Java reports
                                    script {
                                        if (fileExists('pom.xml') && fileExists('target/surefire-reports')) {
                                            junit allowEmptyResults: true,
                                                  testResults: 'target/surefire-reports/*.xml'
                                        } else {
                                            echo "Skipping JUnit"
                                        }
                                    }
                                }
                            }
                        }
                        parallel tests
                    }
                }
            }

            stage('Docker Build') {
                steps {
                    script {
                        def builds = [:]

                        SERVICES.each { svc ->
                            builds[svc] = {
                                dir("${SERVICES_DIR}/${svc}") {
                                    sh "docker build -t ${DOCKER_USER}/${svc}:${IMAGE_TAG} ."
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
                            sh 'echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin'

                            def pushes = [:]
                            SERVICES.each { svc ->
                                pushes[svc] = {
                                    sh "docker push ${DOCKER_USER}/${svc}:${IMAGE_TAG}"
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
                                sh "docker compose up -d ${svc}"
                            } else if (params.ENV == 'qa') {
                                sh "docker compose -f docker-compose.qa.yml up -d ${svc}"
                            } else if (params.ENV == 'prod') {
                                input "Deploy ${svc} to PROD?"
                                sh "docker compose -f docker-compose.prod.yml up -d ${svc}"
                            }
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "Pipeline Success ✅"
            }
            failure {
                echo "Pipeline Failed ❌"
            }
            always {
                cleanWs()
            }
        }
    }
}
