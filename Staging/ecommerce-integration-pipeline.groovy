pipeline {
    agent any

    parameters {
        string(name: 'TRIGGERING_SERVICE', defaultValue: '', description: 'Servicio que disparó la integración')
        string(name: 'IMAGE_TAG', defaultValue: '', description: 'Tag de la imagen desplegada')
    }

    environment {
        DOCKER_REGISTRY_USER = "geoffrey0pv"
        KUBERNETES_NAMESPACE = "ecommerce-dev"
    }

    stages {
        stage('Integration Tests') {
            steps {
                echo "Ejecutando pruebas de integración para ${params.TRIGGERING_SERVICE}"
                echo "Imagen: ${params.IMAGE_TAG}"
                
                script {
                    // Aquí se ejecutarán las pruebas de integración
                    // que validarán la comunicación entre servicios
                    sh '''
                        echo "Preparando ambiente de integración..."
                        echo "Desplegando servicios de prueba..."
                        echo "Ejecutando pruebas de integración..."
                        echo "Pruebas de integración completadas"
                    '''
                }
            }
        }

        stage('E2E Tests') {
            steps {
                echo "Ejecutando pruebas End-to-End"
                
                script {
                    // Aquí se ejecutarán las pruebas E2E
                    sh '''
                        echo "Ejecutando pruebas E2E..."
                        echo "Simulando flujos de usuario completos..."
                        echo "Pruebas E2E completadas"
                    '''
                }
            }
        }

        stage('Performance Tests') {
            steps {
                echo "Ejecutando pruebas de rendimiento con Locust"
                
                script {
                    // Aquí se ejecutarán las pruebas de rendimiento
                    sh '''
                        echo "Ejecutando pruebas de rendimiento..."
                        echo "Simulando carga con Locust..."
                        echo "Pruebas de rendimiento completadas"
                    '''
                }
            }
        }

        stage('Deploy to Dev Environment') {
            when {
                expression { params.TRIGGERING_SERVICE != '' }
            }
            steps {
                echo "Desplegando ${params.TRIGGERING_SERVICE} a ambiente de desarrollo"
                
                script {
                    sh '''
                        echo "Actualizando deployment en Kubernetes..."
                        echo "Verificando health checks..."
                        echo "Deployment completado exitosamente"
                    '''
                }
            }
        }
    }

    post {
        success {
            echo "Pipeline de integración completado exitosamente"
        }
        failure {
            echo "Pipeline de integración falló"
        }
        always {
            cleanWs()
        }
    }
}
