{{/*
Shared container spec for both the API and worker roles.
Kept in one place so the two roles can never drift on security context,
resource shape or health configuration.
*/}}
{{- define "hatis.containerCommon" -}}
securityContext:
  {{- toYaml .ctx.Values.containerSecurityContext | nindent 2 }}
env:
  - name: HATIS_ROLE
    value: {{ .role | quote }}
  - name: SPRING_PROFILES_ACTIVE
    value: "production"
  - name: SERVER_PORT
    value: "8080"
  - name: HATIS_TRUST_PROXY
    value: "true"
  - name: HATIS_EVENT_TRANSPORT
    value: {{ .ctx.Values.events.transport | quote }}
  - name: HATIS_SECRETS_PROVIDER
    value: {{ .ctx.Values.secretsProvider | quote }}
  - name: HATIS_STORAGE_PROVIDER
    value: {{ .ctx.Values.storage.provider | quote }}
  - name: HATIS_STORAGE_S3_BUCKET
    value: {{ .ctx.Values.storage.s3.bucket | quote }}
  - name: HATIS_STORAGE_S3_REGION
    value: {{ .ctx.Values.storage.s3.region | quote }}
  {{- if .ctx.Values.storage.s3.endpointOverride }}
  - name: HATIS_STORAGE_S3_ENDPOINT_OVERRIDE
    value: {{ .ctx.Values.storage.s3.endpointOverride | quote }}
  - name: HATIS_STORAGE_S3_PATH_STYLE_ACCESS
    value: {{ .ctx.Values.storage.s3.pathStyleAccess | quote }}
  {{- end }}
  - name: HATIS_ASSETS_SCANNER_MODE
    value: {{ .ctx.Values.assets.scanner.mode | quote }}
  {{- if eq (toString .ctx.Values.assets.scanner.mode) "clamav" }}
  - name: HATIS_ASSETS_SCANNER_CLAMAV_HOST
    value: {{ .ctx.Values.assets.scanner.clamav.host | quote }}
  - name: HATIS_ASSETS_SCANNER_CLAMAV_PORT
    value: {{ .ctx.Values.assets.scanner.clamav.port | quote }}
  - name: HATIS_ASSETS_SCANNER_CLAMAV_TIMEOUT_MILLIS
    value: {{ .ctx.Values.assets.scanner.clamav.timeoutMillis | quote }}
  {{- end }}
  - name: SPRING_DATASOURCE_URL
    valueFrom:
      secretKeyRef: { name: {{ include "hatis.secretName" .ctx }}, key: db-url }
  - name: SPRING_DATASOURCE_USERNAME
    valueFrom:
      secretKeyRef: { name: {{ include "hatis.secretName" .ctx }}, key: db-username }
  - name: SPRING_DATASOURCE_PASSWORD
    valueFrom:
      secretKeyRef: { name: {{ include "hatis.secretName" .ctx }}, key: db-password }
  - name: SPRING_DATA_REDIS_HOST
    valueFrom:
      secretKeyRef: { name: {{ include "hatis.secretName" .ctx }}, key: redis-host }
  - name: HATIS_TOKEN_SIGNING_KEY
    valueFrom:
      secretKeyRef: { name: {{ include "hatis.secretName" .ctx }}, key: token-signing-key }
  {{- if eq .ctx.Values.events.transport "kafka" }}
  - name: SPRING_KAFKA_BOOTSTRAP_SERVERS
    value: {{ .ctx.Values.events.kafkaBootstrap | quote }}
  {{- end }}
  - name: JAVA_TOOL_OPTIONS
    value: >-
      -XX:MaxRAMPercentage=75
      -XX:+UseContainerSupport
      -XX:+ExitOnOutOfMemoryError
      -XX:+HeapDumpOnOutOfMemoryError
      -XX:HeapDumpPath=/tmp/hatis
ports:
  - name: http
    containerPort: 8080
    protocol: TCP
livenessProbe:
  httpGet: { path: /internal/health/liveness, port: http }
  initialDelaySeconds: 45
  periodSeconds: 10
  timeoutSeconds: 3
  failureThreshold: 6
readinessProbe:
  httpGet: { path: /internal/health/readiness, port: http }
  initialDelaySeconds: 20
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 3
startupProbe:
  httpGet: { path: /internal/health/liveness, port: http }
  periodSeconds: 5
  failureThreshold: 60
resources:
  {{- toYaml .resources | nindent 2 }}
volumeMounts:
  - name: tmp
    mountPath: /tmp
  - name: work
    mountPath: /app/work
{{- end -}}
