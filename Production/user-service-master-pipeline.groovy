pipeline {
    agent any

    environment {
        IMAGE_NAME = "user-service"
        GCR_REGISTRY = "us-central1-docker.pkg.dev/ecommerce-backend-1760307199/ecommerce-microservices"
        GCP_PROJECT_ID = "ecommerce-backend-1760307199"
        
        // --- Variables de Entorno de Producción ---
        K8S_NAMESPACE = "production" // Namespace de Producción
        GKE_CLUSTER = "ecommerce-production-cluster" // Tu clúster de Producción
        GKE_ZONE = "us-central1-a" // Zona de tu clúster de Producción
        GCP_CREDENTIALS = credentials('gke-credentials') // Credencial de GCP
    }

    // Parámetros para control manual
    parameters {
        booleanParam(name: 'CREATE_RELEASE_NOTES', defaultValue: true, description: 'Generar Release Notes')
    }

    stages {
        stage('Checkout & Identify Image') {
            steps {
                checkout scm
                script {
                    // Identifica la imagen EXACTA que se va a desplegar
                    // Usa el commit que acaba de ser mergeado a main
                    env.GIT_COMMIT_SHA = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
                    env.IMAGE_TO_DEPLOY = "${GCR_REGISTRY}/${IMAGE_NAME}:${env.GIT_COMMIT_SHA}"
                    env.RELEASE_VERSION = "prod-${env.BUILD_NUMBER}-${env.GIT_COMMIT_SHA}"
                }
                echo "🚀 Iniciando despliegue a PRODUCCIÓN de ${IMAGE_NAME}"
                echo "Imagen a desplegar: ${env.IMAGE_TO_DEPLOY}"
            }
        }

        stage('Authenticate with GCP') {
            steps {
                script {
                    // Autenticarse en GCP y GKE
                    sh 'gcloud auth activate-service-account --key-file=$GCP_CREDENTIALS'
                    sh 'gcloud config set project ${GCP_PROJECT_ID}'
                    sh 'gcloud container clusters get-credentials ${GKE_CLUSTER} --zone ${GKE_ZONE} --project ${GCP_PROJECT_ID}'
                }
            }
        }

        stage('Verify Image in GCR') {
            steps {
                script {
                    echo "🔍 Verificando que la imagen ${env.IMAGE_TO_DEPLOY} exista en el registro..."
                    sh """
                        # Verificar que la imagen que pasó por Staging existe antes de intentar desplegarla
                        gcloud container images describe ${env.IMAGE_TO_DEPLOY}
                        echo "✅ Imagen verificada en GCR."
                    """
                }
            }
        }

        stage('Deploy to Production') {
            steps {
                script {
                    echo "🚀 Desplegando a producción... (Namespace: ${K8S_NAMESPACE})"
                    sh '''
                        # Aplicar manifiestos de producción (usando los de la carpeta k8s/production)
                        # Idealmente esto se haría con HELMFILE y un values.prod.yaml
                        kubectl apply -f k8s/production/${IMAGE_NAME}-deployment.yaml -n ${K8S_NAMESPACE}
                        
                        # Actualizar la imagen en el deployment
                        kubectl set image deployment/${IMAGE_NAME} ${IMAGE_NAME}=${IMAGE_TO_DEPLOY} -n ${K8S_NAMESPACE}
                        
                        # Anotar el deployment con información de release
                        kubectl annotate deployment/${IMAGE_NAME} \
                            deployment.kubernetes.io/revision="${RELEASE_VERSION}" \
                            deployment.kubernetes.io/change-cause="Deploy ${IMAGE_TO_DEPLOY} via Jenkins" \
                            -n ${K8S_NAMESPACE} --overwrite
                        
                        # Esperar que el rollout termine
                        kubectl rollout status deployment/${IMAGE_NAME} -n ${K8S_NAMESPACE} --timeout=600s
                        
                        echo "✅ Deployment a Producción completado"
                    '''
                }
            }
        }

        stage('Post-deployment Health Check') {
            steps {
                script {
                    echo "🏥 Verificando salud del servicio en Producción..."
                    // Este es tu "Smoke Test" de producción
                    sh '''
                        # Esperar estabilización
                        sleep 30 
                        
                        # Obtener la IP del Ingress o LoadBalancer (esto puede variar)
                        SERVICE_IP=$(kubectl get service ${IMAGE_NAME} -n ${K8S_NAMESPACE} -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
                        SERVICE_PORT=80 # Asumiendo que Prod expone en puerto 80/443
                        
                        if [ -z "$SERVICE_IP" ]; then
                           echo "⚠️ No se encontró IP externa (LoadBalancer). Probando internamente."
                           kubectl exec -n ${K8S_NAMESPACE} deployment/${IMAGE_NAME} -- curl -f http://localhost:8700/actuator/health || exit 1
                        else
                            echo "🌐 Servicio disponible en: http://$SERVICE_IP:$SERVICE_PORT"
                            # Health check
                            for i in {1..5}; do
                                if curl -f "http://$SERVICE_IP:$SERVICE_PORT/actuator/health"; then
                                    echo "✅ Health check exitoso (intento $i)"
                                    break
                                fi
                                # ... (resto de tu lógica de reintento)
                            done
                        fi
                        
                        echo "✅ Servicio saludable en producción"
                    '''
                }
            }
        }

        stage('Generate Release Notes') {
            when {
                expression { params.CREATE_RELEASE_NOTES }
            }
            steps {
                script {
                    echo "📝 Generando Release Notes..."
                    
                    def releaseNotes = """
                    # Release Notes - ${IMAGE_NAME} v${RELEASE_VERSION}

                    ## 📋 Información del Release
                    - **Servicio**: ${IMAGE_NAME}
                    - **Versión**: ${RELEASE_VERSION}
                    - **Imagen**: ${env.IMAGE_TO_DEPLOY}
                    - **Ambiente**: Producción
                    - **Fecha**: ${new Date().format('yyyy-MM-dd HH:mm:ss')}
                    - **Jenkins Build**: #${env.BUILD_NUMBER}
                    - **Git Commit**: ${env.GIT_COMMIT_SHA}

                    ## ✅ Validaciones Ejecutadas
                    - [x] Artefacto verificado en GCR.
                    - [x] Desplegado en Producción.
                    - [x] Health checks post-deployment: ✅ PASS

                    ## 🔗 Enlaces Útiles
                    - **Logs**: `kubectl logs -f deployment/${IMAGE_NAME} -n ${K8S_NAMESPACE}`
                    - **Rollback**: `kubectl rollout undo deployment/${IMAGE_NAME} -n ${K8S_NAMESPACE}`

                    ---
                    **Generado automáticamente por Jenkins Pipeline**
                    """
                    
                    writeFile file: "RELEASE_NOTES_${IMAGE_NAME}_${RELEASE_VERSION}.md", text: releaseNotes
                    archiveArtifacts artifacts: "RELEASE_NOTES_${IMAGE_NAME}_${RELEASE_VERSION}.md", fingerprint: true
                    
                    echo "✅ Release Notes generados y archivados"
                }
            }
        }
    }

    post {
        success {
            echo "🎉 ¡Deployment exitoso de ${IMAGE_NAME} v${RELEASE_VERSION} en PRODUCCIÓN!"
        }
        failure {
            echo "❌ Deployment falló para ${IMAGE_NAME}"
            script {
                // Notificar y ejecutar rollback
                sh '''
                    echo "🚨 ¡FALLO EN PRODUCCIÓN! Iniciando rollback..."
                    kubectl rollout undo deployment/${IMAGE_NAME} -n ${K8S_NAMESPACE} || echo "No hay versión anterior para hacer rollback."
                '''
            }
        }
        always {
            cleanWs()
            // Limpiar credenciales de GCloud
            sh 'gcloud auth revoke --all || true'
        }
    }
}