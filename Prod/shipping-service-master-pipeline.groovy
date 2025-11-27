pipeline {
    agent any
    
    parameters {
        string(name: 'IMAGE_TAG', defaultValue: 'latest-dev', description: 'Tag de la imagen a desplegar (e.g., latest-dev, commit-sha)')
        string(name: 'NOTIFICATION_EMAIL', defaultValue: 'geoffreypv00@gmail.com', description: 'Email para notificaciones de pipeline')
    }

    environment {
        IMAGE_NAME = "shipping-service"
        GCR_REGISTRY = "us-central1-docker.pkg.dev/rock-fortress-479417-t5/ecommerce-microservices"
        FULL_IMAGE_NAME = "${GCR_REGISTRY}/${IMAGE_NAME}"
        
        // Use parameter if provided, otherwise default to latest-dev
        IMAGE_TAG = "${params.IMAGE_TAG}" 
        
        GCP_CREDENTIALS = credentials('gke-credentials')
        GCP_PROJECT = "rock-fortress-479417-t5"
        
        CLUSTER_NAME = "ecommerce-devops-cluster" 
        CLUSTER_LOCATION_FLAG = "--region=us-central1"
        
        K8S_NAMESPACE = "prod"
        K8S_DEPLOYMENT_NAME = "shipping-service"
        K8S_CONTAINER_NAME = "shipping-service"
        K8S_SERVICE_NAME = "shipping-service"
        SERVICE_PORT = "8600" 
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
        
        stage('Checkout SCM') {
            steps {
                cleanWs()
                
                // Checkout Scripts repo
                dir('Scripts') {
                    git branch: 'main', url: 'https://github.com/Ecommerce-DevOps/Scripts.git', credentialsId: 'github-credentials'
                }
                
                // Checkout Manifests repo
                dir('manifests-gcp') {
                    git branch: 'main', url: 'https://github.com/Ecommerce-DevOps/Manifests-kubernetes-helms.git', credentialsId: 'github-credentials'
                }

                // Checkout Shipping Service repo (Required for Release Notes & Tagging)
                dir('shipping-service') {
                    git branch: 'main', url: 'https://github.com/Ecommerce-DevOps/shipping-service.git', credentialsId: 'github-credentials'
                }

                echo "📦 Iniciando despliegue a PRODUCCIÓN"
                echo "📦 Imagen a desplegar: ${FULL_IMAGE_NAME}:${IMAGE_TAG}"
            }
        }

        stage('Generate Release Notes') {
            steps {
                script {
                    dir('shipping-service') {
                        sh """
                            echo "📝 Generando Release Notes..."
                            cp ../Scripts/Infra/generate-release-notes.sh .
                            chmod +x generate-release-notes.sh
                            ./generate-release-notes.sh release-notes.txt
                        """
                        archiveArtifacts artifacts: 'release-notes.txt', allowEmptyArchive: true
                    }
                }
            }
        }

        stage('Authenticate GCP & Kubernetes') {
            steps {
                script {
                    sh """
                        echo "🔐 Autenticando con GCP..."
                        gcloud auth activate-service-account --key-file=${GCP_CREDENTIALS}
                        gcloud config set project ${GCP_PROJECT}
                        gcloud auth configure-docker us-central1-docker.pkg.dev --quiet
                        echo "☸️ Obteniendo credenciales de GKE..."
                        gcloud container clusters get-credentials ${CLUSTER_NAME} ${CLUSTER_LOCATION_FLAG} --project ${GCP_PROJECT}
                    """
                }
            }
        }

        stage('Verify Image Exists') {
            steps {
                script {
                    sh """
                        echo "🔍 Verificando ${FULL_IMAGE_NAME}:${IMAGE_TAG}..."
                        gcloud artifacts docker images describe ${FULL_IMAGE_NAME}:${IMAGE_TAG} || {
                            echo "❌ ERROR: Imagen no encontrada en Artifact Registry"
                            exit 1
                        }
                        echo "✅ Imagen verificada."
                    """
                }
            }
        }
        
        stage('Manual Approval') {
            steps {
                script {
                    echo "⏳ Esperando aprobación manual para despliegue a PRODUCCIÓN..."
                    // Timeout de 1 hora para aprobar
                    timeout(time: 1, unit: 'HOURS') {
                        input message: '¿Aprobar despliegue a PRODUCCIÓN?', ok: 'Desplegar'
                    }
                }
            }
        }

        stage('Deploy to Prod (Helm)') {
            steps {
                script {
                    sh """
                        echo "🚀 Desplegando a ${K8S_NAMESPACE} usando Helm..."
                        kubectl create namespace ${K8S_NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -
                        
                        echo "📋 Aplicando/Actualizando Chart de Helm: ${K8S_DEPLOYMENT_NAME}"
                        
                        # Deshabilitamos Eureka para que el pod arranque solo
                        helm upgrade --install ${K8S_DEPLOYMENT_NAME} manifests-gcp/shipping-service/ \
                            --namespace ${K8S_NAMESPACE} \
                            --set image.tag=${IMAGE_TAG} \
                            --set env[4].value="false" \
                            --set env[5].value="false" \
                            --wait --timeout=5m
                        
                        echo "✅ Despliegue completado."
                    """
                }
            }
        }

        stage('Smoke Tests') {
            steps {
                script {
                    sh """
                        echo "🏥 Ejecutando health checks..."
                        
                        kubectl wait --for=condition=ready pod \
                            -l app=${K8S_DEPLOYMENT_NAME} \
                            -n ${K8S_NAMESPACE} \
                            --timeout=300s
                        
                        POD_NAME=\$(kubectl get pods -n ${K8S_NAMESPACE} \
                            -l app=${K8S_DEPLOYMENT_NAME} \
                            -o jsonpath='{.items[0].metadata.name}')
                        
                        echo "🎯 Testing pod: \$POD_NAME en puerto ${SERVICE_PORT}"
                        
                        kubectl exec \$POD_NAME -n ${K8S_NAMESPACE} -- \
                            curl -f http://localhost:${SERVICE_PORT}/shipping-service/actuator/health || {
                                echo "⚠️ Health check falló"
                                kubectl logs \$POD_NAME -n ${K8S_NAMESPACE} --tail=50
                                exit 1
                            }
                        
                        echo "✅ Health check passed!"
                    """
                }
            }
        }
        
        stage('Tag Release (Git & Docker)') {
            steps {
                script {
                    dir('shipping-service') {
                        echo "🏷️ Iniciando proceso de etiquetado..."
                        
                        // Leer versión del pom.xml
                        def pomVersion = sh(script: "mvn help:evaluate -Dexpression=project.version -q -DforceStdout", returnStdout: true).trim()
                        def releaseTag = "v${pomVersion}"
                        
                        echo "🏷️ Versión detectada: ${pomVersion}"
                        echo "🏷️ Tag de Release: ${releaseTag}"
                        
                        // Git Tag
                        sshagent(['github-credentials']) {
                            sh """
                                if git rev-parse ${releaseTag} >/dev/null 2>&1; then
                                    echo "⚠️ Tag ${releaseTag} ya existe en local. Saltando creación de tag."
                                else
                                    git tag -a ${releaseTag} -m "Release ${releaseTag} deployed to Prod"
                                    git push origin ${releaseTag} || echo "⚠️ Tag ya existe en remoto o error al pushear"
                                fi
                            """
                        }
                        
                        // Docker Tag (Retag image deployed with release version and latest)
                        sh """
                            echo "🐳 Etiquetando imagen Docker con ${releaseTag}..."
                            gcloud artifacts docker tags add ${FULL_IMAGE_NAME}:${IMAGE_TAG} ${FULL_IMAGE_NAME}:${releaseTag} --quiet
                            gcloud artifacts docker tags add ${FULL_IMAGE_NAME}:${IMAGE_TAG} ${FULL_IMAGE_NAME}:latest --quiet
                            echo "✅ Imagen etiquetada en Artifact Registry"
                        """
                    }
                }
            }
        }
    }

    post {
        success {
            script {
                sh """
                    echo "🎉 ✅ PROD DEPLOY EXITOSO"
                    echo "📦 Imagen desplegada: ${FULL_IMAGE_NAME}:${IMAGE_TAG}"
                    gcloud auth activate-service-account --key-file=${GCP_CREDENTIALS}
                    gcloud auth revoke --all || true
                """
                echo "📧 Enviando notificación de ÉXITO a ${params.NOTIFICATION_EMAIL}..."
                mail to: "${params.NOTIFICATION_EMAIL}",
                     subject: "Deploy Prod Exitoso: ${IMAGE_NAME}",
                     body: "El despliegue a Producción de ${IMAGE_NAME}:${IMAGE_TAG} ha sido exitoso."
            }
        }
        failure {
            script {
                sh """
                    echo "🔐 Re-autenticando para operaciones de rollback..."
                    gcloud auth activate-service-account --key-file=${GCP_CREDENTIALS}
                    gcloud config set project ${GCP_PROJECT}
                    gcloud container clusters get-credentials ${CLUSTER_NAME} ${CLUSTER_LOCATION_FLAG} --project ${GCP_PROJECT}
                """
                
                def failedStage = env.STAGE_NAME ?: 'Unknown'
                
                sh """
                    echo "❌ 💥 PROD DEPLOY FALLÓ"
                    echo "🔍 Fallo detectado en stage: ${failedStage}"
                    
                    if [ "${failedStage}" = "Deploy to Prod (Helm)" ]; then
                        echo "🔄 Realizando rollback del despliegue fallido..."
                        helm rollback ${K8S_DEPLOYMENT_NAME} 0 -n ${K8S_NAMESPACE} || echo "⚠️ No hay revisión anterior para rollback."
                    else
                        echo "⚠️ Fallo en stage '${failedStage}'. El despliegue NO será revertido."
                    fi
                    
                    echo "📋 Información de debug:"
                    kubectl get events -n ${K8S_NAMESPACE} --sort-by='.lastTimestamp' | tail -20
                    gcloud auth revoke --all || true
                """
                echo "📧 Enviando notificación de FALLO a ${params.NOTIFICATION_EMAIL}..."
                mail to: "${params.NOTIFICATION_EMAIL}",
                     subject: "Deploy Prod FALLIDO: ${IMAGE_NAME}",
                     body: "El despliegue a Producción de ${IMAGE_NAME} ha fallado en el stage '${failedStage}'. Revisar logs en Jenkins."
            }
        }
        always {
            cleanWs()
        }
    }
}
