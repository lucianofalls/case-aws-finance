# EKS Auto Mode workload templates — NOT automatically applied by Terraform.
# All placeholders rendered by k8s/render.py. No AWS resources created during render.
apiVersion: v1
kind: Namespace
metadata:
  name: financas
  labels:
    app.kubernetes.io/part-of: financas-pod99
    pod-security.kubernetes.io/enforce: restricted
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: autorizacao
  namespace: financas
automountServiceAccountToken: true # EKS Pod Identity uses service account identity
---
apiVersion: v1
kind: Service
metadata:
  name: autorizacao
  namespace: financas
spec:
  selector:
    app: autorizacao
  ports:
    - name: http
      port: 8080
      targetPort: 8080
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: autorizacao
  namespace: financas
spec:
  replicas: 3
  revisionHistoryLimit: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  selector:
    matchLabels:
      app: autorizacao
  template:
    metadata:
      labels:
        app: autorizacao
        app.kubernetes.io/part-of: financas-pod99
    spec:
      serviceAccountName: autorizacao
      terminationGracePeriodSeconds: 40
      securityContext:
        runAsNonRoot: true
        seccompProfile:
          type: RuntimeDefault
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels:
              app: autorizacao
      containers:
        - name: app
          image: ${IMAGE_AUTORIZACAO}
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
              name: http
          resources:
            requests:
              cpu: "500m"
              memory: "768Mi"
            limits:
              memory: "2Gi"
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
          volumeMounts:
            - name: tmp
              mountPath: /tmp
          startupProbe:
            httpGet:
              path: /actuator/health
              port: http
            failureThreshold: 30
            periodSeconds: 5
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            periodSeconds: 15
          env:
            - name: AWS_REGION
              value: "$REGION"
            - name: TABELA_AUTORIZACOES
              value: "$TABELA_AUTORIZACOES"
            - name: TABELA_PERMISSOES_CONTRATOS
              value: "$TABELA_PERMISSOES_CONTRATOS"
            - name: LIMITES_URL
              value: "http://limites.financas.svc.cluster.local:8080"
            - name: RETENCAO_AUTORIZACOES
              value: "35d"
            - name: FILA_RESERVAS_A_EXPIRAR
              value: "$FILA_EXPIRACAO"
      volumes:
        - name: tmp
          emptyDir: {}
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: autorizacao
  namespace: financas
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: autorizacao
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: autorizacao
  namespace: financas
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: autorizacao
  minReplicas: 3
  maxReplicas: 40
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: limites
  namespace: financas
automountServiceAccountToken: true # EKS Pod Identity uses service account identity
---
apiVersion: v1
kind: Service
metadata:
  name: limites
  namespace: financas
spec:
  selector:
    app: limites
  ports:
    - name: http
      port: 8080
      targetPort: 8080
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: limites
  namespace: financas
spec:
  replicas: 3
  revisionHistoryLimit: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  selector:
    matchLabels:
      app: limites
  template:
    metadata:
      labels:
        app: limites
        app.kubernetes.io/part-of: financas-pod99
    spec:
      serviceAccountName: limites
      terminationGracePeriodSeconds: 40
      securityContext:
        runAsNonRoot: true
        seccompProfile:
          type: RuntimeDefault
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels:
              app: limites
      containers:
        - name: app
          image: ${IMAGE_LIMITES}
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
              name: http
          resources:
            requests:
              cpu: "500m"
              memory: "768Mi"
            limits:
              memory: "2Gi"
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
          volumeMounts:
            - name: tmp
              mountPath: /tmp
          startupProbe:
            httpGet:
              path: /actuator/health
              port: http
            failureThreshold: 30
            periodSeconds: 5
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            periodSeconds: 15
          env:
            - name: AWS_REGION
              value: "$REGION"
            - name: TABELA_LIMITES
              value: "$TABELA_LIMITES"
            - name: FILA_LIMITES_CONCEDIDOS
              value: "$FILA_LIMITES"
      volumes:
        - name: tmp
          emptyDir: {}
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: limites
  namespace: financas
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: limites
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: limites
  namespace: financas
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: limites
  minReplicas: 3
  maxReplicas: 40
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: contabilizacao
  namespace: financas
automountServiceAccountToken: true # EKS Pod Identity uses service account identity
---
apiVersion: v1
kind: Service
metadata:
  name: contabilizacao
  namespace: financas
spec:
  selector:
    app: contabilizacao
  ports:
    - name: http
      port: 8080
      targetPort: 8080
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: contabilizacao
  namespace: financas
spec:
  replicas: 2
  revisionHistoryLimit: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  selector:
    matchLabels:
      app: contabilizacao
  template:
    metadata:
      labels:
        app: contabilizacao
        app.kubernetes.io/part-of: financas-pod99
    spec:
      serviceAccountName: contabilizacao
      terminationGracePeriodSeconds: 40
      securityContext:
        runAsNonRoot: true
        seccompProfile:
          type: RuntimeDefault
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels:
              app: contabilizacao
      containers:
        - name: app
          image: ${IMAGE_CONTABILIZACAO}
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
              name: http
          resources:
            requests:
              cpu: "500m"
              memory: "768Mi"
            limits:
              memory: "2Gi"
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
          volumeMounts:
            - name: tmp
              mountPath: /tmp
          startupProbe:
            httpGet:
              path: /actuator/health
              port: http
            failureThreshold: 30
            periodSeconds: 5
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            periodSeconds: 15
          env:
            - name: AWS_REGION
              value: "$REGION"
            - name: DB_URL
              value: "jdbc:postgresql://$RDS_HOST:5432/contabilizacao?sslmode=require"
            - name: DB_USUARIO
              value: "contabilizacao_app"
            # Migracoes executadas fora dos Pods de runtime, com usuario DDL separado.
            - name: SPRING_FLYWAY_ENABLED
              value: "false"
            - name: DB_SENHA
              valueFrom:
                secretKeyRef:
                  name: rds-contabilizacao
                  key: password
            - name: STREAM_TRANSACOES
              value: "$STREAM_TRANSACOES"
            - name: KCL_APLICACAO
              value: "$KCL_APLICACAO"
            - name: FILA_QUARENTENA_URL
              value: "$FILA_QUARENTENA"
      volumes:
        - name: tmp
          emptyDir: {}
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: contabilizacao
  namespace: financas
spec:
  minAvailable: 1
  selector:
    matchLabels:
      app: contabilizacao
---
apiVersion: eks.amazonaws.com/v1
kind: TargetGroupBinding
metadata:
  name: autorizacao-nlb
  namespace: financas
spec:
  serviceRef:
    name: autorizacao
    port: 8080
  targetType: ip
  targetGroupARN: ${TG_AUTORIZACAO}
---
apiVersion: eks.amazonaws.com/v1
kind: TargetGroupBinding
metadata:
  name: limites-nlb
  namespace: financas
spec:
  serviceRef:
    name: limites
    port: 8080
  targetType: ip
  targetGroupARN: ${TG_LIMITES}
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: limites-ingress
  namespace: financas
spec:
  podSelector:
    matchLabels: {app: limites}
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector: {matchLabels: {app: autorizacao}}
        - ipBlock: {cidr: "$VPC_CIDR"} # internal NLB/Lambda; protected by NLB SG as well
      ports: [{port: 8080, protocol: TCP}]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: contabilizacao-ingress-deny
  namespace: financas
spec:
  podSelector:
    matchLabels: {app: contabilizacao}
  policyTypes: [Ingress]
  ingress: []
---
