// jenkins-pipelines/favourite-service-master-pipeline.groovy
pipeline {
    agent any
    
    parameters {
        string(name: 'IMAGE_TO_DEPLOY', defaultValue: '', description: 'Full image name with tag to promote to production')
    }
    
    environment {
        IMAGE_NAME = "favourite-service"
        GCR_REGISTRY = "us-central1-docker.pkg.dev/ecommerce-backend-1760307199/ecommerce-microservices"
        SERVICE_DIR = "favourite-service"
        SPRING_PROFILES_ACTIVE = "prod"
        KUBERNETES_NAMESPACE = "ecommerce-production"
        // Credencial de GKE (archivo de clave de servicio JSON)
        GCP_CREDENTIALS = credentials('gke-credentials') 
    }

    stages {
        stage('Validate Parameters') {
            steps {
                script {
                    if (!params.IMAGE_TO_DEPLOY) {
                        error("IMAGE_TO_DEPLOY parameter is required")
                    }
                    echo "Promoviendo imagen ya probada a producción: ${params.IMAGE_TO_DEPLOY}"
                }
            }
        }
        
        stage('Production Security Scan') {
            steps {
                script {
                    echo "Escaneando imagen final antes de producción..."
                    sh """
                        docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
                            aquasec/trivy:latest image \
                            --severity HIGH,CRITICAL \
                            --exit-code 1 \
                            ${params.IMAGE_TO_DEPLOY}
                    """
                }
            }
        }
        
        stage('Tag for Production') {
            steps {
                script {
                    // Autenticarse en GCR
                    sh 'gcloud auth activate-service-account --key-file=$GCP_CREDENTIALS'
                    sh 'gcloud auth configure-docker us-central1-docker.pkg.dev --quiet'
                    
                    // Crear tag de producción para la imagen ya probada
                    sh """
                        docker pull ${params.IMAGE_TO_DEPLOY}
                        docker tag ${params.IMAGE_TO_DEPLOY} ${GCR_REGISTRY}/${IMAGE_NAME}:latest
                        docker tag ${params.IMAGE_TO_DEPLOY} ${GCR_REGISTRY}/${IMAGE_NAME}:stable
                        docker push ${GCR_REGISTRY}/${IMAGE_NAME}:latest
                        docker push ${GCR_REGISTRY}/${IMAGE_NAME}:stable
                    """
                    
                    echo "Imagen promovida a producción: ${GCR_REGISTRY}/${IMAGE_NAME}:latest"
                }
            }
        }
        
        stage('Deploy to Production') {
            steps {
                script {
                    echo "Desplegando ${params.IMAGE_TO_DEPLOY} a ambiente de producción..."
                    
                    sh 'gcloud container clusters get-credentials ecommerce-gke-cluster --zone us-central1-a --project ecommerce-backend-1760307199'
                    
                    // Asegurar que el namespace existe
                    sh "kubectl create namespace ${KUBERNETES_NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -"
                    
                    // Desplegar con estrategia rolling update
                    sh """
                        kubectl set image deployment/${IMAGE_NAME} ${IMAGE_NAME}=${params.IMAGE_TO_DEPLOY} -n ${KUBERNETES_NAMESPACE} || \
                        kubectl create deployment ${IMAGE_NAME} --image=${params.IMAGE_TO_DEPLOY} -n ${KUBERNETES_NAMESPACE}
                        
                        kubectl expose deployment ${IMAGE_NAME} --port=8086 --target-port=8086 -n ${KUBERNETES_NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -
                        
                        kubectl rollout status deployment/${IMAGE_NAME} -n ${KUBERNETES_NAMESPACE} --timeout=600s
                    """
                }
            }
        }
        
        stage('Production Health Check') {
            steps {
                script {
                    echo "Verificando salud del servicio en producción..."
                    sh """
                        kubectl wait --for=condition=ready pod -l app=${IMAGE_NAME} -n ${KUBERNETES_NAMESPACE} --timeout=600s
                        kubectl get pods -l app=${IMAGE_NAME} -n ${KUBERNETES_NAMESPACE}
                        
                        echo "Validando endpoints de producción..."
                        # Aquí irían las validaciones específicas de producción
                    """
                }
            }
        }
    }

    post {
        success {
            echo "Promoción a PRODUCCIÓN de ${params.IMAGE_TO_DEPLOY} completada exitosamente."
        }
        always {
            cleanWs()
            sh 'gcloud auth revoke --all || true'
        }
        failure {
            echo "Promoción a PRODUCCIÓN falló para ${params.IMAGE_TO_DEPLOY}"
        }
    }
}