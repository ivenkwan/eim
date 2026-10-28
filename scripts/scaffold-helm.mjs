#!/usr/bin/env node
// TG0-INF-6: scaffolds the eim-platform umbrella Helm chart with
// per-component subcharts (arch §13.2). Idempotent — existing files are kept.
import { mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.cwd();
const UMBRELLA = 'platform/helm/eim-platform';

function put(relPath, content) {
  const abs = join(ROOT, relPath);
  if (existsSync(abs)) {
    console.log(`[helm-scaffold] skip (exists) ${relPath}`);
    return;
  }
  mkdirSync(join(abs, '..'), { recursive: true });
  writeFileSync(abs, content);
  console.log(`[helm-scaffold] wrote ${relPath}`);
}

const APP_CHARTS = [
  { name: 'eim-web', image: 'eim-web', tag: '0.1.0-dev', port: 80, targetPort: 8080 },
  { name: 'eim-core-api', image: 'eim-core-api', tag: '0.1.0-dev', port: 8080, quarkus: true },
  { name: 'eim-connectors', image: 'eim-connectors', tag: '0.1.0-dev', port: 8081, quarkus: true },
  { name: 'eim-calc-engine', image: 'eim-calc-engine', tag: '0.1.0-dev', port: 8082, quarkus: true },
  { name: 'eim-process', image: 'eim-process', tag: '0.1.0-dev', port: 8083, quarkus: true },
  { name: 'eim-plan-compiler', image: 'eim-plan-compiler', tag: '0.1.0-dev', port: 8084, quarkus: true },
];

// ---------------------------------------------------------------- umbrella
put(
  `${UMBRELLA}/Chart.yaml`,
  `apiVersion: v2
name: eim-platform
description: EIM platform umbrella chart (cloud-agnostic, Kubernetes-only — arch §13)
type: application
version: 0.1.0
appVersion: "0.1.0"
dependencies:
${[...APP_CHARTS.map((c) => c.name), 'eim-postgres', 'eim-kafka', 'eim-minio', 'eim-apisix', 'eim-keycloak']
  .map((n) => `  - name: ${n}\n    version: 0.1.0\n    repository: file://charts/${n}\n    condition: ${n}.enabled`)
  .join('\n')}
`,
);

put(
  `${UMBRELLA}/values.yaml`,
  `# Default posture: single-namespace dev/embedded (TG0-INF-3 dev cluster).
# Production/substitution postures: see ci/*.yaml and scripts/substitution-matrix.sh.
global:
  databaseHost: eim-postgres
  databasePort: "5432"
  databaseName: eim
  databaseUser: eim
  # databasePassword: REQUIRED via --set / external secrets (never defaulted)
  kafkaBootstrapServers: eim-kafka:9092

eim-web:
  enabled: true
eim-core-api:
  enabled: true
eim-connectors:
  enabled: true
eim-calc-engine:
  enabled: true
eim-process:
  enabled: true
eim-plan-compiler:
  enabled: true

eim-postgres:
  enabled: true
  image: apache/age:PG16_latest
  auth:
    database: eim
    username: eim
    # password: REQUIRED via --set (no default credentials in source — NFR-53)

eim-kafka:
  enabled: true
  image: apache/kafka:3.9.0
  clusterId: eim-dev-kraft-cluster-01

eim-minio:
  enabled: true
  image: minio/minio:RELEASE.2024-08-29T01-40-52Z
  # rootUser / rootPassword: REQUIRED via --set (NFR-53)

eim-apisix:
  enabled: true
  image: apache/apisix:3.11.0

eim-keycloak:
  enabled: true
  image: quay.io/keycloak/keycloak:26.0.7
  # adminUsername / adminPassword: REQUIRED via --set (NFR-53)
`,
);

put(
  `${UMBRELLA}/README.md`,
  `# eim-platform (umbrella chart)

Deploys the full EIM platform. Cloud-agnostic: CNCF-conformant Kubernetes only (NFR-80).

\`\`\`bash
helm dependency build platform/helm/eim-platform
helm upgrade --install eim platform/helm/eim-platform \\
  --namespace eim --create-namespace \\
  --set eim-postgres.auth.password=... \\
  --set eim-minio.rootUser=... --set eim-minio.rootPassword=... \\
  --set eim-keycloak.adminUsername=... --set eim-keycloak.adminPassword=...
\`\`\`

Substitution posture is values-only (NFR-81): embedded PostgreSQL/Kafka can be
swapped for external endpoints via \`global.databaseHost\` /
\`global.kafkaBootstrapServers\` plus \`eim-postgres.enabled=false\` /
\`eim-kafka.enabled=false\` — verified by \`scripts/substitution-matrix.sh\`.
`,
);

// ---------------------------------------------------------------- app charts
for (const app of APP_CHARTS) {
  put(
    `${UMBRELLA}/charts/${app.name}/Chart.yaml`,
    `apiVersion: v2
name: ${app.name}
description: ${app.name} (EIM platform)
type: application
version: 0.1.0
appVersion: "0.1.0"
`,
  );
  put(
    `${UMBRELLA}/charts/${app.name}/values.yaml`,
    `replicas: 1
image:
  repository: ${app.image}
  tag: ${app.tag}
resources:
  requests: { cpu: 100m, memory: 192Mi }
  limits: { cpu: "1", memory: 512Mi }
`,
  );
  put(
    `${UMBRELLA}/charts/${app.name}/templates/deployment.yaml`,
    `apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Chart.Name }}
  labels: { app: {{ .Chart.Name }} }
spec:
  replicas: {{ .Values.replicas }}
  selector:
    matchLabels: { app: {{ .Chart.Name }} }
  template:
    metadata:
      labels: { app: {{ .Chart.Name }} }
    spec:
${app.quarkus
  ? `      initContainers:
        - name: wait-for-postgres
          image: {{ $.Values.waitImage | default "apache/age:PG16_latest" }}
          command: ["sh", "-c", "until pg_isready -h ${'${databaseHost}'} -p ${'${databasePort}'} -U ${'${databaseUser}'}; do sleep 2; done"]
          env:
            - name: databaseHost
              value: {{ .Values.global.databaseHost | quote }}
            - name: databasePort
              value: {{ .Values.global.databasePort | quote }}
            - name: databaseUser
              value: {{ .Values.global.databaseUser | quote }}
`
  : ''}      containers:
        - name: {{ .Chart.Name }}
          image: {{ .Values.image.repository }}:{{ .Values.image.tag }}
          imagePullPolicy: IfNotPresent
          ports:
            - name: http
              containerPort: ${app.quarkus ? app.port : app.targetPort}
          env:
${app.quarkus
  ? `            - name: DB_HOST
              value: {{ .Values.global.databaseHost | quote }}
            - name: DB_PORT
              value: {{ .Values.global.databasePort | quote }}
            - name: DB_NAME
              value: {{ .Values.global.databaseName | quote }}
            - name: DB_USERNAME
              value: {{ .Values.global.databaseUser | quote }}
            - name: DB_PASSWORD
              value: {{ required "global.databasePassword is required" .Values.global.databasePassword | quote }}
            - name: KAFKA_BOOTSTRAP_SERVERS
              value: {{ .Values.global.kafkaBootstrapServers | quote }}
            - name: QUARKUS_HTTP_PORT
              value: "${app.port}"
`
  : '            []\n'}${app.quarkus
  ? `          readinessProbe:
            httpGet: { path: /q/health/ready, port: http }
            initialDelaySeconds: 5
            periodSeconds: 5
          livenessProbe:
            httpGet: { path: /q/health/live, port: http }
            initialDelaySeconds: 10
            periodSeconds: 10
`
  : `          readinessProbe:
            httpGet: { path: /, port: http }
            initialDelaySeconds: 3
            periodSeconds: 5
`}          resources: {{- toYaml .Values.resources | nindent 12 }}
`,
  );
  put(
    `${UMBRELLA}/charts/${app.name}/templates/service.yaml`,
    `apiVersion: v1
kind: Service
metadata:
  name: {{ .Chart.Name }}
  labels: { app: {{ .Chart.Name }} }
spec:
  selector: { app: {{ .Chart.Name }} }
  ports:
    - name: http
      port: ${app.port}
      targetPort: http
`,
  );
}

// ---------------------------------------------------------------- infra charts
// --- PostgreSQL + AGE (StatefulSet) ---
put(`${UMBRELLA}/charts/eim-postgres/Chart.yaml`, `apiVersion: v2\nname: eim-postgres\ndescription: PostgreSQL 16 with Apache AGE (graph extension)\ntype: application\nversion: 0.1.0\nappVersion: "16"\n`);
put(`${UMBRELLA}/charts/eim-postgres/values.yaml`, `image: apache/age:PG16_latest\nauth:\n  database: eim\n  username: eim\npersistence:\n  size: 5Gi\n`);
put(
  `${UMBRELLA}/charts/eim-postgres/templates/statefulset.yaml`,
  `apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: {{ .Chart.Name }}
  labels: { app: {{ .Chart.Name }} }
spec:
  serviceName: {{ .Chart.Name }}
  replicas: 1
  selector:
    matchLabels: { app: {{ .Chart.Name }} }
  template:
    metadata:
      labels: { app: {{ .Chart.Name }} }
    spec:
      containers:
        - name: postgres
          image: {{ .Values.image }}
          command: ["docker-entrypoint.sh", "postgres", "-c", "shared_preload_libraries=age"]
          env:
            - name: POSTGRES_DB
              value: {{ .Values.auth.database | quote }}
            - name: POSTGRES_USER
              value: {{ .Values.auth.username | quote }}
            - name: POSTGRES_PASSWORD
              value: {{ required "eim-postgres.auth.password is required (NFR-53: no default credentials)" .Values.auth.password | quote }}
            - name: PGDATA
              value: /var/lib/postgresql/data/pgdata
          ports:
            - name: pg
              containerPort: 5432
          readinessProbe:
            exec:
              command: ["sh", "-c", "pg_isready -U $POSTGRES_USER -d $POSTGRES_DB"]
            initialDelaySeconds: 5
            periodSeconds: 5
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
            - name: initdb
              mountPath: /docker-entrypoint-initdb.d
      volumes:
        - name: initdb
          configMap:
            name: {{ .Chart.Name }}-initdb
  volumeClaimTemplates:
    - metadata: { name: data }
      spec:
        accessModes: ["ReadWriteOnce"]
        resources: { requests: { storage: {{ .Values.persistence.size }} } }
`,
);
put(
  `${UMBRELLA}/charts/eim-postgres/templates/configmap-initdb.yaml`,
  `apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ .Chart.Name }}-initdb
  labels: { app: {{ .Chart.Name }} }
data:
  01-age.sql: |
    -- TG-0: enable AGE and create the tenant graph (AD-3 / arch §9.2).
    CREATE EXTENSION IF NOT EXISTS age;
    LOAD 'age';
    SET age.age_graph_path = 'eim_graph';
    SELECT ag_catalog.create_graph('eim_graph');
`,
);
put(`${UMBRELLA}/charts/eim-postgres/templates/service.yaml`, `apiVersion: v1\nkind: Service\nmetadata:\n  name: {{ .Chart.Name }}\nspec:\n  selector: { app: {{ .Chart.Name }} }\n  ports:\n    - name: pg\n      port: 5432\n      targetPort: pg\n`);

// --- Kafka (apache/kafka, single-node KRaft) ---
put(`${UMBRELLA}/charts/eim-kafka/Chart.yaml`, `apiVersion: v2\nname: eim-kafka\ndescription: Kafka (KRaft single-node dev; Strimzi posture via substitution)\ntype: application\nversion: 0.1.0\nappVersion: "3.9.0"\n`);
put(`${UMBRELLA}/charts/eim-kafka/values.yaml`, `image: apache/kafka:3.9.0\nclusterId: eim-dev-kraft-cluster-01\npersistence:\n  size: 5Gi\n`);
put(
  `${UMBRELLA}/charts/eim-kafka/templates/configmap.yaml`,
  `apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ .Chart.Name }}-config
  labels: { app: {{ .Chart.Name }} }
data:
  server.properties: |
    process.roles=broker,controller
    node.id=1
    controller.quorum.voters=1@{{ .Chart.Name }}:9093
    listeners=PLAINTEXT://:9092,CONTROLLER://:9093
    advertised.listeners=PLAINTEXT://{{ .Chart.Name }}:9092
    controller.listener.names=CONTROLLER
    listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
    inter.broker.listener.name=PLAINTEXT
    log.dirs=/var/lib/kafka/data
    num.partitions=3
    offsets.topic.replication.factor=1
    transaction.state.log.replication.factor=1
    transaction.state.log.min.isr=1
    group.initial.rebalance.delay.ms=0
    auto.create.topics.enable=true
`,
);
put(
  `${UMBRELLA}/charts/eim-kafka/templates/statefulset.yaml`,
  `apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: {{ .Chart.Name }}
  labels: { app: {{ .Chart.Name }} }
spec:
  serviceName: {{ .Chart.Name }}
  replicas: 1
  selector:
    matchLabels: { app: {{ .Chart.Name }} }
  template:
    metadata:
      labels: { app: {{ .Chart.Name }} }
    spec:
      containers:
        - name: kafka
          image: {{ .Values.image }}
          command: ["/bin/bash", "-c"]
          args:
            - |
              if [ ! -f /var/lib/kafka/data/meta.properties ]; then
                /opt/kafka/bin/kafka-storage.sh format -t {{ .Values.clusterId }} -c /etc/kafka/server.properties --standalone
              fi
              exec /opt/kafka/bin/kafka-server-start.sh /etc/kafka/server.properties
          ports:
            - name: plaintext
              containerPort: 9092
            - name: controller
              containerPort: 9093
          readinessProbe:
            tcpSocket: { port: plaintext }
            initialDelaySeconds: 10
            periodSeconds: 10
          volumeMounts:
            - name: config
              mountPath: /etc/kafka
            - name: data
              mountPath: /var/lib/kafka
      volumes:
        - name: config
          configMap:
            name: {{ .Chart.Name }}-config
  volumeClaimTemplates:
    - metadata: { name: data }
      spec:
        accessModes: ["ReadWriteOnce"]
        resources: { requests: { storage: {{ .Values.persistence.size }} } }
`,
);
put(`${UMBRELLA}/charts/eim-kafka/templates/service.yaml`, `apiVersion: v1\nkind: Service\nmetadata:\n  name: {{ .Chart.Name }}\nspec:\n  selector: { app: {{ .Chart.Name }} }\n  ports:\n    - name: plaintext\n      port: 9092\n      targetPort: plaintext\n`);

// --- MinIO ---
put(`${UMBRELLA}/charts/eim-minio/Chart.yaml`, `apiVersion: v2\nname: eim-minio\ndescription: S3-compatible object storage (snapshots, statements, attachments)\ntype: application\nversion: 0.1.0\n`);
put(`${UMBRELLA}/charts/eim-minio/values.yaml`, `image: minio/minio:RELEASE.2024-08-29T01-40-52Z\npersistence:\n  size: 10Gi\n`);
put(
  `${UMBRELLA}/charts/eim-minio/templates/deployment.yaml`,
  `apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Chart.Name }}
  labels: { app: {{ .Chart.Name }} }
spec:
  replicas: 1
  selector:
    matchLabels: { app: {{ .Chart.Name }} }
  template:
    metadata:
      labels: { app: {{ .Chart.Name }} }
    spec:
      containers:
        - name: minio
          image: {{ .Values.image }}
          args: ["server", "/data", "--console-address", ":9001"]
          env:
            - name: MINIO_ROOT_USER
              value: {{ required "eim-minio.rootUser is required (NFR-53)" .Values.rootUser | quote }}
            - name: MINIO_ROOT_PASSWORD
              value: {{ required "eim-minio.rootPassword is required (NFR-53)" .Values.rootPassword | quote }}
          ports:
            - name: api
              containerPort: 9000
            - name: console
              containerPort: 9001
          readinessProbe:
            httpGet: { path: /minio/health/live, port: api }
            initialDelaySeconds: 5
            periodSeconds: 5
          volumeMounts:
            - name: data
              mountPath: /data
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: {{ .Chart.Name }}
`,
);
put(`${UMBRELLA}/charts/eim-minio/templates/pvc.yaml`, `apiVersion: v1\nkind: PersistentVolumeClaim\nmetadata:\n  name: {{ .Chart.Name }}\nspec:\n  accessModes: ["ReadWriteOnce"]\n  resources: { requests: { storage: {{ .Values.persistence.size }} } }\n`);
put(`${UMBRELLA}/charts/eim-minio/templates/service.yaml`, `apiVersion: v1\nkind: Service\nmetadata:\n  name: {{ .Chart.Name }}\nspec:\n  selector: { app: {{ .Chart.Name }} }\n  ports:\n    - name: api\n      port: 9000\n      targetPort: api\n    - name: console\n      port: 9001\n      targetPort: console\n`);

// --- APISIX (standalone, config-as-code routes) ---
put(`${UMBRELLA}/charts/eim-apisix/Chart.yaml`, `apiVersion: v2\nname: eim-apisix\ndescription: APISIX API gateway (standalone config-provider mode; C3 / arch §8)\ntype: application\nversion: 0.1.0\n`);
put(`${UMBRELLA}/charts/eim-apisix/values.yaml`, `image: apache/apisix:3.11.0\n`);
put(
  `${UMBRELLA}/charts/eim-apisix/templates/configmap.yaml`,
  `apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ .Chart.Name }}-config
  labels: { app: {{ .Chart.Name }} }
data:
  config.yaml: |
    # APISIX standalone (decoupled from etcd): routes are config-as-code,
    # mirrored into the audit plane by the GitOps sync watcher (FR-613.5).
    deployment:
      role: traditional
      role_traditional:
        config_provider: yaml
    routes:
      - uri: /api/v1/*
        upstream:
          type: roundrobin
          nodes:
            "eim-core-api:8080": 1
`,
);
put(
  `${UMBRELLA}/charts/eim-apisix/templates/deployment.yaml`,
  `apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Chart.Name }}
  labels: { app: {{ .Chart.Name }} }
spec:
  replicas: 1
  selector:
    matchLabels: { app: {{ .Chart.Name }} }
  template:
    metadata:
      labels: { app: {{ .Chart.Name }} }
    spec:
      containers:
        - name: apisix
          image: {{ .Values.image }}
          env:
            - name: APISIX_STAND_ALONE
              value: "true"
          ports:
            - name: gateway
              containerPort: 9080
          readinessProbe:
            tcpSocket: { port: gateway }
            initialDelaySeconds: 5
            periodSeconds: 5
          volumeMounts:
            - name: config
              mountPath: /usr/local/apisix/conf
      volumes:
        - name: config
          configMap:
            name: {{ .Chart.Name }}-config
`,
);
put(`${UMBRELLA}/charts/eim-apisix/templates/service.yaml`, `apiVersion: v1\nkind: Service\nmetadata:\n  name: {{ .Chart.Name }}\nspec:\n  selector: { app: {{ .Chart.Name }} }\n  ports:\n    - name: gateway\n      port: 9080\n      targetPort: gateway\n`);

// --- Keycloak ---
put(`${UMBRELLA}/charts/eim-keycloak/Chart.yaml`, `apiVersion: v2\nname: eim-keycloak\ndescription: Identity broker (OIDC/SAML federation — AD-8)\ntype: application\nversion: 0.1.0\n`);
put(`${UMBRELLA}/charts/eim-keycloak/values.yaml`, `image: quay.io/keycloak/keycloak:26.0.7\n`);
put(
  `${UMBRELLA}/charts/eim-keycloak/templates/deployment.yaml`,
  `apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Chart.Name }}
  labels: { app: {{ .Chart.Name }} }
spec:
  replicas: 1
  selector:
    matchLabels: { app: {{ .Chart.Name }} }
  template:
    metadata:
      labels: { app: {{ .Chart.Name }} }
    spec:
      containers:
        - name: keycloak
          image: {{ .Values.image }}
          args: ["start-dev", "--http-enabled=true", "--http-port=8080"]
          env:
            - name: KC_BOOTSTRAP_ADMIN_USERNAME
              value: {{ required "eim-keycloak.adminUsername is required (NFR-53)" .Values.adminUsername | quote }}
            - name: KC_BOOTSTRAP_ADMIN_PASSWORD
              value: {{ required "eim-keycloak.adminPassword is required (NFR-53)" .Values.adminPassword | quote }}
            - name: KC_HEALTH_ENABLED
              value: "true"
          ports:
            - name: http
              containerPort: 8080
          readinessProbe:
            httpGet: { path: /realms/master, port: http }
            initialDelaySeconds: 15
            periodSeconds: 10
`,
);
put(`${UMBRELLA}/charts/eim-keycloak/templates/service.yaml`, `apiVersion: v1\nkind: Service\nmetadata:\n  name: {{ .Chart.Name }}\nspec:\n  selector: { app: {{ .Chart.Name }} }\n  ports:\n    - name: http\n      port: 8080\n      targetPort: http\n`);

console.log('[helm-scaffold] done');
