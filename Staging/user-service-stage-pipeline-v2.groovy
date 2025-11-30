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
        
        API_GATEWAY_SERVICE_NAME = "api-gateway"
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
                dir('Scripts') {
                    git branch: 'main', url: 'https://github.com/Ecommerce-DevOps/Scripts.git', credentialsId: 'github-credentials'
                }
                dir('manifests-gcp') {
                    git branch: 'main', url: 'https://github.com/Ecommerce-DevOps/Manifests-kubernetes-helms.git', credentialsId: 'github-credentials'
                }
                dir('tests') {
                    git branch: 'main', url: 'https://github.com/Ecommerce-DevOps/Testing-unit-integration-e2e-locust.git', credentialsId: 'github-credentials'
                }
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
                    dir('user-service') {
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

        stage('Verify Image Exists in GCR') {
            steps {
                script {
                    sh """
                        echo "🔍 Verificando ${FULL_IMAGE_NAME}:${IMAGE_TAG}..."
                        gcloud artifacts docker images describe ${FULL_IMAGE_NAME}:${IMAGE_TAG} || {
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
                        echo "🚀 Desplegando a ${K8S_NAMESPACE} usando Helm..."
                        kubectl create namespace ${K8S_NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -
                        
                        echo "📋 Aplicando/Actualizando Chart de Helm: ${K8S_DEPLOYMENT_NAME}"
                        
                        helm upgrade --install ${K8S_DEPLOYMENT_NAME} manifests-gcp/${K8S_DEPLOYMENT_NAME}/ \
                            --namespace ${K8S_NAMESPACE} \
                            --set image.repository=${FULL_IMAGE_NAME} \
                            --set image.tag=${IMAGE_TAG} \
                            --set service.port=${SERVICE_PORT} \
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
                            -l app=${K8S_DEPLOYMENT_NAME} \
                            -n ${K8S_NAMESPACE} \
                            --timeout=300s
                        
                        echo "🎯 Verificando endpoint de salud internamente..."
                        
                        # USAR UN POD EXTERNO CON CURL EN LUGAR DE ENTRAR AL POD DE LA APP
                        kubectl run health-check-${BUILD_NUMBER} \
                            --image=curlimages/curl:latest \
                            -n ${K8S_NAMESPACE} \
                            --rm -i --restart=Never \
                            -- \
                            curl -f -v http://${K8S_SERVICE_NAME}:${SERVICE_PORT}/${K8S_SERVICE_NAME}/actuator/health || {
                                echo "⚠️ Health check falló"
                                kubectl logs -l app=${K8S_DEPLOYMENT_NAME} -n ${K8S_NAMESPACE} --tail=50
                                exit 1
                            }
                        
                        echo "✅ Health check passed!"
                    """
                }
            }
        }

        stage('Verify Gateway Availability') {
            steps {
                script {
                    sh """
                        echo "🌐 Verificando disponibilidad del API Gateway (${API_GATEWAY_SERVICE_NAME})..."
                        
                        kubectl wait --for=condition=ready pod \
                            -l app.kubernetes.io/name=${API_GATEWAY_SERVICE_NAME} \
                            -n ${K8S_NAMESPACE} \
                            --timeout=300s
                        
                        GATEWAY_IP=\$(kubectl get svc ${API_GATEWAY_SERVICE_NAME} -n ${K8S_NAMESPACE} \
                            -o jsonpath='{.spec.clusterIP}')
                        
                        if [ -z "\$GATEWAY_IP" ]; then
                            echo "❌ No se pudo obtener la IP del servicio ${API_GATEWAY_SERVICE_NAME}"
                            exit 1
                        fi
                        
                        echo "✅ Gateway ClusterIP: \$GATEWAY_IP"
                        echo "\$GATEWAY_IP" > gateway-ip.txt
                        
                        echo "🔍 Verificando conectividad al Gateway en http://\$GATEWAY_IP:8080/actuator/health"
                        kubectl run test-gateway-${BUILD_NUMBER} --image=curlimages/curl:latest \
                            -n ${K8S_NAMESPACE} --rm -i --restart=Never --timeout=60s -- \
                            curl -f --retry 5 --retry-delay 5 --retry-connrefused \
                            http://\$GATEWAY_IP:8080/actuator/health || {
                                echo "⚠️ No se pudo conectar al Gateway internamente"
                                exit 1
                            }
                        
                        echo "✅ Gateway respondiendo correctamente"
                    """
                }
            }
        }

        stage('Verify Service Registration') {
            steps {
                script {
                    sh """
                        echo "🔍 Verificando registro en Eureka..."
                        
                        # Retry loop for Eureka registration
                        for i in {1..30}; do
                            if kubectl run eureka-check-${BUILD_NUMBER} --image=curlimages/curl:latest \
                                -n ${K8S_NAMESPACE} --rm -i --restart=Never -- \
                                curl -s -f http://discovery:8761/eureka/apps/USER-SERVICE | grep -q "UP"; then
                                echo "✅ USER-SERVICE registrado y UP en Eureka"
                                break
                            fi
                            
                            echo "⏳ Esperando a que USER-SERVICE se registre en Eureka... (\$i/30)"
                            kubectl delete pod eureka-check-${BUILD_NUMBER} -n ${K8S_NAMESPACE} --force --grace-period=0 2>/dev/null || true
                            sleep 5
                            
                            if [ \$i -eq 30 ]; then
                                echo "❌ Timeout esperando registro en Eureka"
                                exit 1
                            fi
                        done
                    """
                }
            }
        }

        stage('Run E2E Tests (Maven)') {
            when {
                expression { fileExists('tests/e2e/pom.xml') }
            }
            steps {
                script {
                    sh """
                        echo "🌐 =============================================="
                        echo "🌐 Configurando URL del API Gateway"
                        echo "🌐 =============================================="
                        
                        # Usar api-gateway real
                        GATEWAY_URL="http://api-gateway.${K8S_NAMESPACE}:8080"
                        
                        echo "Gateway URL: \$GATEWAY_URL"
                        
                        # Verificar que el servicio está respondiendo
                        echo "🔍 Verificando conectividad con api-gateway..."
                        kubectl run test-gateway-conn --image=curlimages/curl:latest --rm -i --restart=Never -n \${K8S_NAMESPACE} -- \\
                            curl -s -o /dev/null -w "%{http_code}" \$GATEWAY_URL/actuator/health || {
                                echo "❌ api-gateway no responde. Abortando tests."
                                exit 1
                            }
                        
                        echo "✅ api-gateway respondiendo correctamente"
                        
                        echo "🧪 =============================================="
                        echo "🧪 Ejecutando E2E Tests contra: \$GATEWAY_URL"
                        echo "🧪 =============================================="
                        
                        # Ejecutar tests E2E dentro de un pod en el cluster (con acceso a la red del cluster)
                        echo "🧪 Desplegando pod de tests E2E en el cluster..."
                        
                        cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: e2e-test-runner-\${BUILD_NUMBER}
  namespace: \${K8S_NAMESPACE}
spec:
  restartPolicy: Never
  containers:
  - name: maven-tests
    image: maven:3.9.9-eclipse-temurin-17
    command: ["sleep"]
    args: ["3600"]
    workingDir: /workspace
EOF

                        # Esperar a que el pod esté listo
                        echo "⏳ Esperando a que el pod de tests esté listo..."
                        kubectl wait --for=condition=ready pod/e2e-test-runner-\${BUILD_NUMBER} -n \${K8S_NAMESPACE} --timeout=120s
                        
                        # Copiar el código al pod
                        echo "📦 Copiando código de tests al pod..."
                        kubectl cp tests/e2e e2e-test-runner-\${BUILD_NUMBER}:/workspace/e2e -n \${K8S_NAMESPACE}
                        
                        # Ejecutar tests dentro del pod
                        echo "🧪 Ejecutando tests E2E contra API Gateway..."
                        kubectl exec -n \${K8S_NAMESPACE} e2e-test-runner-\${BUILD_NUMBER} -- \\
                            mvn clean test -f /workspace/e2e/pom.xml \\
                            -Dapi.gateway.url=\$GATEWAY_URL \\
                            -Dmaven.test.failure.ignore=true \\
                            -Dorg.slf4j.simpleLogger.log.org.springframework.web.client=DEBUG || TEST_FAILED=true
                        
                        # Copiar resultados de vuelta
                        echo "📋 Copiando resultados de tests..."
                        kubectl cp e2e-test-runner-\${BUILD_NUMBER}:/workspace/e2e/target tests/e2e/ -n \${K8S_NAMESPACE} || true
                        
                        # Limpiar pod de tests
                        echo "🧹 Limpiando pod de tests..."
                        kubectl delete pod e2e-test-runner-\${BUILD_NUMBER} -n \${K8S_NAMESPACE} || true
                        
                        if [ "\$TEST_FAILED" = "true" ]; then
                            echo "❌ Tests E2E fallaron"
                            exit 1
                        fi
                        
                        echo "✅ E2E Tests completados exitosamente."
                    """
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'tests/e2e/target/surefire-reports/*.xml'
                    archiveArtifacts artifacts: 'tests/e2e/target/surefire-reports/**/*', allowEmptyArchive: true
                }
            }
        }

        stage('Security Scan (OWASP ZAP)') {
            steps {
                script {
                    sh """
                        echo "🛡️ =============================================="
                        echo "🛡️ Ejecutando Escaneo de Seguridad OWASP ZAP"
                        echo "🛡️ =============================================="
                        
                        # Obtener IP del Gateway
                        GATEWAY_IP=\$(kubectl get svc \${API_GATEWAY_SERVICE_NAME} -n \${K8S_NAMESPACE} -o jsonpath='{.spec.clusterIP}')
                        TARGET_URL="http://\$GATEWAY_IP:8080"
                        
                        mkdir -p reports/zap
                        chmod 777 reports/zap
                        
                        # Ejecutar ZAP Baseline Scan
                        # Nota: Usamos 'zap-baseline.py' para un escaneo rápido. Para full scan usar 'zap-full-scan.py'
                        docker run --rm -u 0 -v \$(pwd)/reports/zap:/zap/wrk/:rw \
                            --network host \
                            ghcr.io/zaproxy/zaproxy:stable zap-baseline.py \
                            -t \$TARGET_URL \
                            -r zap_report.html \
                            -I || echo "⚠️ ZAP encontró alertas, revisar reporte."
                            
                        echo "✅ Escaneo de seguridad completado."
                    """
                }
            }
            post {
                always {
                    publishHTML([
                        allowMissing: true,
                        alwaysLinkToLastBuild: true,
                        keepAll: true,
                        reportDir: 'reports/zap',
                        reportFiles: 'zap_report.html',
                        reportName: 'OWASP ZAP Security Report',
                        reportTitles: 'ZAP Security Scan Results'
                    ])
                }
            }
        }

        stage('Run Performance Tests (Locust)') {
            when {
                expression { fileExists('tests/performance/ecommerce_load_test.py') }
            }
            steps {
                script {
                    sh """
                        echo "🚀 =============================================="
                        echo "🚀 Ejecutando Performance Tests con Locust (In-Cluster)"
                        echo "🚀 =============================================="
                        
                        # URL interna del api-gateway
                        TARGET_HOST="http://api-gateway.${K8S_NAMESPACE}:8080"
                        
                        echo "Target Host: \$TARGET_HOST"
                        
                        # Crear directorio para reportes
                        mkdir -p reports
                        
                        # Ejecutar Locust dentro del cluster usando un Pod temporal
                        # Montamos el script de test usando ConfigMap o copiándolo (aquí usaremos copia)
                        
                        echo "📦 Preparando pod de Locust..."
                        
                        cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: locust-runner-\${BUILD_NUMBER}
  namespace: \${K8S_NAMESPACE}
spec:
  restartPolicy: Never
  containers:
  - name: locust
    image: locustio/locust
    command: ["sleep"]
    args: ["3600"]
    workingDir: /mnt/locust
EOF

                        # Esperar a que el pod esté listo
                        echo "⏳ Esperando a que el pod de Locust esté listo..."
                        kubectl wait --for=condition=ready pod/locust-runner-\${BUILD_NUMBER} -n \${K8S_NAMESPACE} --timeout=120s
                        
                        # Copiar el script de test al pod
                        echo "📦 Copiando script de tests al pod..."
                        kubectl cp tests/performance locust-runner-\${BUILD_NUMBER}:/mnt/locust -n \${K8S_NAMESPACE}
                        
                        # Ejecutar Locust dentro del pod
                        echo "🚀 Ejecutando Locust..."
                        kubectl exec -n \${K8S_NAMESPACE} locust-runner-\${BUILD_NUMBER} -- \
                            locust -f ecommerce_load_test.py \
                            --host \$TARGET_HOST \
                            --users 50 --spawn-rate 5 --run-time 1m \
                            --headless \
                            --csv=locust_stats --exit-code-on-fail 0 || LOCUST_FAILED=true
                            
                        # Copiar resultados de vuelta
                        echo "📋 Copiando reportes de Locust..."
                        kubectl cp locust-runner-\${BUILD_NUMBER}:/mnt/locust/locust_stats_stats.csv reports/locust_stats.csv -n \${K8S_NAMESPACE} || true
                        
                        # Limpiar pod
                        echo "🧹 Limpiando pod de Locust..."
                        kubectl delete pod locust-runner-\${BUILD_NUMBER} -n \${K8S_NAMESPACE} || true
                        
                        if [ "\$LOCUST_FAILED" = "true" ]; then
                            echo "❌ Performance tests fallaron"
                            exit 1
                        fi
                        
                        echo "✅ Performance tests completados"
                        
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
                    echo "📦 Imagen desplegada: ${FULL_IMAGE_NAME}:${IMAGE_TAG}"
                    gcloud auth activate-service-account --key-file=${GCP_CREDENTIALS}
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
                    gcloud auth activate-service-account --key-file=${GCP_CREDENTIALS}
                    gcloud config set project ${GCP_PROJECT}
                    gcloud container clusters get-credentials ${CLUSTER_NAME} ${CLUSTER_LOCATION_FLAG} --project ${GCP_PROJECT}
                """
                
                def failedStage = env.STAGE_NAME ?: 'Unknown'
                
                sh """
                    echo "❌ 💥 STAGING DEPLOY FALLÓ"
                    echo "🔍 Fallo detectado en stage: ${failedStage}"
                    
                    if [ "${failedStage}" = "Deploy to Staging (Helm)" ]; then
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
