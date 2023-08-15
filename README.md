# NJU30-云原生大作业说明文档
## 1. 项目说明

| 姓名           | 学号      |
| -------------- | --------- |
| 刘子恒 |  211870018   |
| 陈宇航         | 211850104 |
| 王柏霏         | 211870213 |

项目地址：https://gitee.com/duasoihvod/Cloud-Native.git

## 2. 部署过程

### 2.1 实现一个简单REST接口

```java
@RestController
public class DemoController {

    private final RateLimiter rateLimiter = RateLimiter.create(100.0);

    @GetMapping("/hello")
    public ResponseEntity<String> hello(@RequestParam(value = "name", defaultValue = "World") String name) {
        if (rateLimiter.tryAcquire()) {
            return ResponseEntity.ok(String.format("Hello %s!", name));
        } else {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("Too many requests");
        }
    }
}
```

例如，访问`http://localhost:8080/hello?name=NJU` ，将会返回：

```
Hello NJU!
```

### 2.2 限流功能

```
参考：
https://segmentfault.com/a/1190000040805974
https://juejin.cn/post/6986574899338805261
```
实现途径：
```java
private final RateLimiter rateLimiter = RateLimiter.create(100.0);
```
RateLimiter的原理类似于令牌桶，它主要由许可发出的速率来定义，如果没有额外的配置，许可证将按每秒许可证规定的固定速度分配，许可将被平滑地分发，若请求超过permitsPerSecond则RateLimiter按照每秒 1/permitsPerSecond 的速率释放许可。
RateLimiter.create(100.0) 创建了一个限流器对象，它的参数表示每秒允许通过的请求数。在这里设置为每秒最多允许 100 次请求通过。

使用Jmeter测试结果如下：
![](./img/2.2/1.png)
![](./img/2.2/2.png)
![](./img/2.2/3.png)
![](./img/2.2/4.png)
![](./img/2.2/5.png)

## 2.3 编写Dokcerfile并制作镜像

#### 编写Dockerfile

```dockerfile
# /demo/Dockerfile
FROM openjdk
ADD target/*.jar /application.jar
ENTRYPOINT ["java", "-jar","/application.jar"]
```

##### 解释

以opendjk为基础镜像，将target文件夹中，demo项目构建好的jar包复制到镜像中根目录下，且重命名为application.jar，然后指定容器启动时要运行`java -jar /application.jar`命令，执行该jar文件

#### 制作镜像

在`/demo`目录下

```shell
docker build -t demo:v1  .
```

即可制作镜像，名称和tag分别为demo和v1

#### 测试

执行命令

```shell
docker run -p 8080:8080  -d demo:v1
```

然后访问`http://localhost:8080/hello?name=NJU`

![](img/2.3/1.png)

### 2.4 在 Kubernetes 集群上创建 Deployment 和 Service
Deployment & Service
```yaml
#/demo/jenkins/scripts/demo.yaml

apiVersion: apps/v1
kind: Deployment #对象类型
metadata:
  labels:
    app: demo
  name: demo
  namespace: nju30
spec:
  replicas: 1  #运行容器的副本数
  selector:
    matchLabels:
      app: demo
  template:
    metadata:
      annotations:
        prometheus.io/path: /actuator/prometheus
        prometheus.io/port: "8080"
        prometheus.io/scheme: http
        prometheus.io/scrape: "true"
      labels:
        app: demo
    spec:
      containers: #docker容器的配置
        - image: harbor.edu.cn/nju30/demo:18 #pull镜像的地址
          name: demo
      imagePullSecrets:
        - name: nju30-secret
---
apiVersion: v1
kind: Service
metadata:
  name: demo
  namespace: nju30
  labels:
    app: demo
spec:
  type: NodePort
  selector:
    app: demo
  ports:
    - name: tcp
      nodePort: 31109  #host's port
      protocol: TCP
      port: 8080  #service's port
      targetPort: 8080  #target pod's port
```
### 2.5 持续集成流水线和持续部署流水线
jenkins pipeline
```groovy
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
                dir('/var/jenkins_home/workspace/030demo/demo') {
                    sh 'mvn -B clean package -Dmaven.test.skip=true'
                }
            }
        }
        stage('Image Build') {
            agent {
                label 'master'
            }
            steps {
                echo "3.Image Build Stage"
                dir('/var/jenkins_home/workspace/030demo/demo') {
                    sh 'docker build -f Dockerfile --build-arg jar_name=target/cloud-native-0.0.1-SNAPSHOT.jar -t demo:${BUILD_ID} . '
                    sh 'docker tag  demo:${BUILD_ID}  harbor.edu.cn/nju30/demo:${BUILD_ID}'
                }
            }
        }
        stage('Push') {
            agent {
                label 'master'
            }
            steps {
                echo "4.Push Docker Image Stage"
                dir('/var/jenkins_home/workspace/030demo/demo') {
                    sh "docker login --username=nju30 harbor.edu.cn -p nju302023"
                    sh "docker push harbor.edu.cn/nju30/demo:${BUILD_ID}"
                }

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
            sh 'sed -i "s#{VERSION}#${BUILD_ID}#g" ./demo/jenkins/scripts/demo.yaml'
        }

        stage('Deploy') {
            echo "7. Deploy To K8s Stage"
            sh 'kubectl apply -f ./demo/jenkins/scripts/demo.yaml -n nju30'
            sh 'kubectl apply -f ./demo/jenkins/scripts/demo-serviceMonitor.yaml -n nju30 --namespace=monitoring'
        }
    }
}
```
#### 流水线部署成功：
![](img/2.4/1.jpg)

#### 部署产物与浏览器验证
![](img/2.4/2.jpg)

#### 镜像仓库：
![](img/2.4/1.png)

## 3.扩容场景

### 3.1 Prometheus 采集监控指标

在springboot项目中配置Prometheus metrics 接口


```properties
#application.properties
server.port=8080
management.endpoints.web.base-path=/actuator
management.server.port=8080
management.endpoints.web.exposure.include=prometheus
management.prometheus.metrics.export.enabled=true
management.endpoint.health.show-details=always
management.metrics.tags.application=${spring.application.name}
```


声明一个ServiceMonitor对象，在流水线中的Deploy阶段部署
```yaml
#/demo/jenkins/scripts/demo-serviceMonitor.yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  labels:
    k8s-app: demo
  name: demo
  namespace: monitoring
spec:
  endpoints:
  - interval: 30s
    port: tcp
    path: /actuator/prometheus
    scheme: 'http'
  selector:
    matchLabels:
      app: demo
  namespaceSelector:
    matchNames:
    - nju30
```

验证Prometheus
![](img/3.1/1.jpg)
![](img/3.1/3.jpg)
![](img/3.1/2.png)
Prometheus

### 3.2 Grafana

![](img/3.2/monitor.jpg)
1. JVM usage 容器jvm使用情况
![](img/3.2/jvm.png)

2. container cpu usage 容器CPU使用情况

![](img/3.2/cpu.png)

3. container memory usage 容器内存使用情况

![](img/3.2/CMU.png)

### 3.3 压测并观察监控数据
![](img/3.3/1.jpg)
![](img/3.3/2.jpg)
### 3.4 手动扩容并观察监控数据
扩容前pod数为1:

![](img/3.1/2.png)

更改yaml文件重新构建流水线：

![](img/3.3/change.png)

扩容成功：

![](img/3.3/after.png)

扩容过后的监控数据：
![](img/3.3/change_monitor.png)
