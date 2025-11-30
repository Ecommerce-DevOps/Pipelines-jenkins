pipeline {
    agent any
    
    parameters {
        string(name: 'IMAGE_TAG', defaultValue: 'latest-dev', description: 'Tag de la imagen a desplegar (e.g., latest-dev, commit-sha)')
        string(name: 'NOTIFICATION_EMAIL', defaultValue: 'geoffreypv00@gmail.com', description: 'Email para notificaciones de pipeline')
    }

    environment {
        IMAGE_NAME = "user-service"
        GCR_REGISTRY = "us-central1-docker.pkg.dev/rock-fortress-479417-t5/ecommerce-microservices"
        FULL_IMAGE_NAME = "${GCR_REGISTRY}/${IMAGE_NAME}"
        
        // Use parameter if provided, otherwise default to latest-dev (handled by params, but env var needed for scripts)
        IMAGE_TAG = "${params.IMAGE_TAG}" 
        
        GCP_CREDENTIALS = credentials('gke-credentials')
        GCP_PROJECT = "rock-fortress-479417-t5"
        
        CLUSTER_NAME = "ecommerce-devops-cluster" 
        CLUSTER_LOCATION_FLAG = "--region=us-central1"
        
        K8S_NAMESPACE = "staging"
        K8S_DEPLOYMENT_NAME = "user-service"
        K8S_CONTAINER_NAME = "user-service"
        K8S_SERVICE_NAME = "user-service"
        SERVICE_PORT = "8700" 
        
        API_GATEWAY_SERVICE_NAME = "proxy-client" 
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
                // No main checkout here, we checkout specific repos below
                
                // Checkout Scripts repo
                dir('Scripts') {
                    git branch: 'main', url: 'https://github.com/Ecommerce-DevOps/Scripts.git', credentialsId: 'github-credentials'
                }
                
                // Checkout Manifests repo
                dir('manifests-gcp') {
                    git branch: 'main', url: 'https://github.com/Ecommerce-DevOps/Manifests-kubernetes-helms.git', credentialsId: 'github-credentials'
                }
                
                // Checkout Testing repo
                dir('tests') {
                    git branch: 'main', url: 'https://github.com/Ecommerce-DevOps/Testing-unit-integration-e2e-locust.git', credentialsId: 'github-credentials'
                }

                // Checkout User Service repo (Required for Release Notes history)
                dir('user-service') {
                    git branch: 'main', url: 'https://github.com/Ecommerce-DevOps/user-service.git', credentialsId: 'github-credentials'
                }

                echo "📦 Iniciando despliegue a STAGING"
                echo "📦 Imagen a desplegar: ${FULL_IMAGE_NAME}:${IMAGE_TAG}"
            }
        }

        stage('Generate Release Notes') {
            steps {
                script {
                    // Generate notes inside user-service dir to access git history
                    dir('user-service') {
                        sh """
                            echo "📝 Generando Release Notes..."
                            # Copy script from Scripts repo to here
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
                        gcloud auth activate-service-account --key-file=\${GCP_CREDENTIALS}
                        gcloud config set project \${GCP_PROJECT}
                        gcloud auth configure-docker us-central1-docker.pkg.dev --quiet
                        echo "☸️ Obteniendo credenciales de GKE..."
                        gcloud container clusters get-credentials \${CLUSTER_NAME} \${CLUSTER_LOCATION_FLAG} --project \${GCP_PROJECT}
                    """
                }
            }
        }

        stage('Verify Image Exists in GCR') {
            steps {
                script {
                    sh """
                        echo "🔍 Verificando \${FULL_IMAGE_NAME}:\${IMAGE_TAG}..."
                        gcloud artifacts docker images describe \${FULL_IMAGE_NAME}:\${IMAGE_TAG} || {
                            echo "❌ ERROR: Imagen no encontrada"
                            echo "Asegúrate de que el pipeline de DEV ('user-service-pipeline.groovy') haya corrido exitosamente."
                            exit 1
                        }
                        echo "✅ Imagen verificada."
                    """
                }
            }
        }
        
        stage('Deploy to Staging (Helm)') {
            steps {
                script {
                    sh """
                        echo "🚀 Desplegando a \${K8S_NAMESPACE} usando Helm..."
                        kubectl create namespace \${K8S_NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -
                        
                        echo "📋 Aplicando/Actualizando Chart de Helm: ${K8S_DEPLOYMENT_NAME}"
                        
                        # Configurar perfil staging para desactivar seguridad JWT en tests E2E
                        helm upgrade --install ${K8S_DEPLOYMENT_NAME} manifests-gcp/user-service/ \
                            --namespace ${K8S_NAMESPACE} \
                            --set image.tag=${IMAGE_TAG} \
                            --set service.type=NodePort \
                            --wait --timeout=5m
                        
                        echo "✅ Despliegue completado."
                    """
                }
            }
        }

        stage('Health Check & Smoke Tests') {
            steps {
                script {
                    sh """
                        echo "🏥 Ejecutando health checks..."
                        
                        kubectl wait --for=condition=ready pod \
                            -l app=\${K8S_DEPLOYMENT_NAME} \
                            if curl -s http://localhost:8100/app/actuator/health > /dev/null 2>&1; then
                                echo "✅ Port-forward activo!"
                                break
                            fi
                            if [ \$i -eq 30 ]; then
                                echo "❌ Port -forward no se pudo establecer"
                                kill \$PORT_FORWARD_PID 2>/dev/null || true
                                exit 1
                            fi
                            sleep 1
                        done
                        
                        set -e  # Volver a modo estricto
                        
                        BASE_URL="http://localhost:8100"
                        
                        echo "🚀 =============================================="
                        echo "🚀 Ejecutando Performance Tests con Locust"
                        echo "🚀 Target: \$BASE_URL"
                        echo "🚀 =============================================="
                        
                        # Crear directorio para reportes
                        mkdir -p reports
                        
                        # Ejecuta locust dentro de un contenedor docker
                        # --network host: Permite al contenedor acceder a localhost del host
                        # -v \${WORKSPACE}:/mnt/locust: Monta tu código
                        docker run --rm --network host -v "\${WORKSPACE}":/mnt/locust -w /mnt/locust \
                            locustio/locust \
                            -f tests/performance/ecommerce_load_test.py \
                            --host \$BASE_URL \
                            --users 50 --spawn-rate 5 --run-time 1m \
                            --headless \
                            --csv=reports/locust --exit-code-on-fail 0
                        
                        echo "✅ Performance tests completados"
                        
                        # Limpiar port-forward
                        echo "🧹 Limpiando port-forward..."
                        kill \$PORT_FORWARD_PID 2>/dev/null || true
                        
                        # Mostrar estadísticas si existen
                        if [ -f "reports/locust_stats.csv" ]; then
                            echo ""
                            echo "📊 =============================================="
                            echo "📊 RESUMEN DE PERFORMANCE TESTS"
                            echo "📊 =============================================="
                            cat reports/locust_stats.csv
                        fi
                    """
                }
            }
            post {
                always {
                    script {
                        sh "pkill -f 'kubectl port-forward.*proxy-client.*8100' || true"
                    }
                    archiveArtifacts artifacts: 'reports/**/*', allowEmptyArchive: true
                    publishHTML([
                        allowMissing: true,
                        alwaysLinkToLastBuild: true,
                        keepAll: true,
                        reportDir: 'reports',
                        reportFiles: 'load_test_report.html',
                        reportName: 'Locust Performance Report',
                        reportTitles: 'Performance Test Results'
                    ])
                }
            }
        }
    }

    post {
        success {
            script {
                sh """
                    echo "🎉 ✅ STAGING DEPLOY EXITOSO"
                    echo "📦 Imagen desplegada: \${FULL_IMAGE_NAME}:\${IMAGE_TAG}"
                    gcloud auth activate-service-account --key-file=\${GCP_CREDENTIALS}
                    gcloud auth revoke --all || true
                """
                echo "📧 Enviando notificación de ÉXITO a ${params.NOTIFICATION_EMAIL}..."
                // mail to: "${params.NOTIFICATION_EMAIL}",
                //      subject: "Deploy Staging Exitoso: ${IMAGE_NAME}",
                //      body: "El despliegue a Staging de ${IMAGE_NAME}:${IMAGE_TAG} ha sido exitoso."
            }
        }
        failure {
            script {
                sh """
                    echo "🔐 Re-autenticando para operaciones de rollback..."
                    gcloud auth activate-service-account --key-file=\${GCP_CREDENTIALS}
                    gcloud config set project \${GCP_PROJECT}
                    gcloud container clusters get-credentials \${CLUSTER_NAME} \${CLUSTER_LOCATION_FLAG} --project \${GCP_PROJECT}
                """
                
                def failedStage = env.STAGE_NAME ?: 'Unknown'
                
                sh """
                    echo "❌ 💥 STAGING DEPLOY FALLÓ"
                    echo "🔍 Fallo detectado en stage: ${failedStage}"
                    
                    if [ "${failedStage}" = "Deploy to Staging (Helm)" ]; then
                        echo "🔄 Realizando rollback del despliegue fallido..."
                        helm rollback \${K8S_DEPLOYMENT_NAME} 0 -n \${K8S_NAMESPACE} || echo "⚠️ No hay revisión anterior para rollback."
                    else
                        echo "⚠️ Fallo en stage '${failedStage}'. El despliegue NO será revertido."
                    fi
                    
                    echo "📋 Información de debug:"
                    kubectl get events -n \${K8S_NAMESPACE} --sort-by='.lastTimestamp' | tail -20
                    gcloud auth revoke --all || true
                """
                echo "📧 Enviando notificación de FALLO a ${params.NOTIFICATION_EMAIL}..."
                // mail to: "${params.NOTIFICATION_EMAIL}",
                //      subject: "Deploy Staging FALLIDO: ${IMAGE_NAME}",
                //      body: "El despliegue a Staging de ${IMAGE_NAME} ha fallado en el stage '${failedStage}'. Revisar logs en Jenkins."
            }
        }
        always {
            cleanWs()
        }
    }
}