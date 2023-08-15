pipeline {
    agent none
    stages {
        stage('Clone Code') {
            agent {
                label 'master'
            }
            steps {
                echo "1.Git Clone Code"
                git branch: 'main', url: "https://gitee.com/duasoihvod/Cloud-Native.git"
            }
        }
        stage('Maven Build') {
            agent {
                docker {
                    image 'maven:latest'
                    args '-v /root/.m2:/root/.m2'
                }
            }
            steps {
                echo "2.Maven Build Stage"
                sh 'mvn -B clean package -Dmaven.test.skip=true'
            }
        }
        stage('Image Build') {
            agent {
                label 'master'
            }
            steps {
                echo "3.Image Build Stage"
                sh 'docker build -f Dockerfile --build-arg jar_name=target/cloud-native-0.0.1-SNAPSHOT.jar -t cloud-native:${BUILD_ID} . '
                sh 'docker tag  cloud-native:${BUILD_ID}  harbor.edu.cn/nju30/cloud-native:${BUILD_ID}'
            }
        }
        stage('Push') {
            agent {
                label 'master'
            }
            steps {
                echo "4.Push Docker Image Stage"
                sh "docker login --username=nju30 harbor.edu.cn -p nju302023"
                sh "docker push harbor.edu.cn/nju30/cloud-native:${BUILD_ID}"
            }
        }
    }
}


node('slave') {
    container('jnlp-kubectl') {

        stage('Clone YAML') {
            echo "5. Git Clone YAML To Slave"
            git url: "https://gitee.com/duasoihvod/Cloud-Native.git", branch: 'main'
        }

        stage('YAML') {
            echo "6. Change YAML File Stage"
            sh 'sed -i "s#{VERSION}#${BUILD_ID}#g" ./jenkins/scripts/cloud-native.yaml'
        }

        stage('Deploy') {
            echo "7. Deploy To K8s Stage"
            sh 'kubectl apply -f ./jenkins/scripts/cloud-native.yaml -n nju30'
        }
    }
}
