pipeline {
    agent any
    environment {
        IMAGE_NAME = "payment-service"
        SERVICE_DIR = "payment-service"
        GCR_REGISTRY = "us-central1-docker.pkg.dev/rock-fortress-479417-t5/ecommerce-microservices"
        SPRING_PROFILES_ACTIVE = "dev"
        GCP_CREDENTIALS = credentials('gke-credentials') 
    }

    stages {
        stage('Install Parent POM') {
            steps {
                cleanWs()
                dir('parent-repo') {
                    git branch: 'main', 
                        url: 'https://github.com/Ecommerce-DevOps/General-config.git', 
                        credentialsId: 'github-credentials'
                    
                    script {
                        docker.image('maven:3.8.4-openjdk-11').inside('-v maven-repo:/root/.m2') {
                            sh 'mvn clean install -N' 
                        }
                    }
                }
            }
        }

        stage('Checkout') {
            steps {
                cleanWs()
                dir("${SERVICE_DIR}") {
                    git branch: 'main', 
                        url: 'https://github.com/Ecommerce-DevOps/payment-service.git',
                        credentialsId: 'github-credentials'
                }
                script {
                    dir("${SERVICE_DIR}") {
                        env.GIT_COMMIT_SHA = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
                    }
                    env.FULL_IMAGE_NAME = "${GCR_REGISTRY}/${IMAGE_NAME}"
                    env.IMAGE_TAG = "${env.GIT_COMMIT_SHA}"
                }
                echo "Procesando Build para ${IMAGE_NAME} commit ${IMAGE_TAG}"
            }
        }

        stage('Compile') {
            steps {
                script {
                    docker.image('maven:3.8.4-openjdk-11').inside('-v maven-repo:/root/.m2') {
                        sh """
                            cd ${SERVICE_DIR}
                            mvn clean compile -Dspring.profiles.active=dev
                        """
                    }
                }
            }
        }

        stage('Unit & Integration Tests (Maven)') {
            steps {
                script {
                    docker.image('maven:3.8.4-openjdk-11').inside('-v maven-repo:/root/.m2') {
                        sh """
                            cd ${SERVICE_DIR}
                            mvn verify -Dspring.profiles.active=dev
                        """
                    }
                }
            }
            post {
                always {
                    junit "${SERVICE_DIR}/target/surefire-reports/*.xml"
                }
            }
        }

        stage('Code Quality Analysis') {
            steps {
                script {
                    docker.image('maven:3.8.4-openjdk-11').inside('-v maven-repo:/root/.m2') {
                        sh '''
                            echo "Análisis de calidad de código..."
                            # Añadido '-pl ${SERVICE_DIR} -am' para resolver el parent POM
                            mvn verify sonar:sonar \
                                -Dspring.profiles.active=dev \
                                -pl ${SERVICE_DIR} -am \
                                -Dsonar.projectKey=${IMAGE_NAME} \
                                -Dsonar.host.url=http://sonarqube:9000 \
                                -Dsonar.login=${SONAR_TOKEN} || echo "SonarQube no configurado"
                        '''
                    }
                }
            }
        }

        stage('Package') {
            steps {
                script {
                    docker.image('maven:3.8.4-openjdk-11').inside('-v maven-repo:/root/.m2') {
                        sh """
                            cd ${SERVICE_DIR}
                            mvn package -DskipTests=true -Dspring.profiles.active=dev
                        """
                    }
                }
            }
        }
        
        stage('Build Docker Image') {
            steps {
                dir("${SERVICE_DIR}") {
                    script {
                        echo "Construyendo imagen: ${FULL_IMAGE_NAME}:${IMAGE_TAG}"
                        def customImage = docker.build("${FULL_IMAGE_NAME}:${IMAGE_TAG}", "-f Dockerfile .")
                        customImage.tag("latest-dev")
                    }
                }
            }
        }

        stage('Security Scan (Trivy)') {
            steps {
                script {
                    echo "Escaneando imagen ${FULL_IMAGE_NAME}:${IMAGE_TAG} para vulnerabilidades..."
                    sh """
                        mkdir -p \$HOME/.trivy/cache
                        docker run --rm \
                            -v /var/run/docker.sock:/var/run/docker.sock \
                            -v \$HOME/.trivy/cache:/root/.cache/trivy \
                            aquasec/trivy:latest image \
                            --severity HIGH,CRITICAL \
                            --format table \
                            ${FULL_IMAGE_NAME}:${IMAGE_TAG} || echo "ADVERTENCIA: Vulnerabilidades encontradas pero se permite continuar"
                    """
                }
            }
        }

        stage('Authenticate & Push Docker Image') {
            steps {
                script {
                    sh 'gcloud auth activate-service-account --key-file=$GCP_CREDENTIALS'
                    sh 'gcloud auth configure-docker us-central1-docker.pkg.dev --quiet'
                    docker.image("${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}").push()
                    docker.image("${env.FULL_IMAGE_NAME}:latest-dev").push()
                    echo "Imagen publicada: ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}"
                }
            }
        }
    } 

    post {
        success {
            echo "Pipeline de Build [${IMAGE_NAME}] completado exitosamente."
        }
        always {
            cleanWs()
            sh 'gcloud auth revoke --all || true'
        }
        failure {
            echo "Build falló para ${IMAGE_NAME}"
        }
    }
} 
