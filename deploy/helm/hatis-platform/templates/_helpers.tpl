{{/* Expand the name of the chart. */}}
{{- define "hatis.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Fully qualified release name, capped at the 63-character DNS limit. */}}
{{- define "hatis.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "hatis.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "hatis.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "hatis.labels" -}}
helm.sh/chart: {{ include "hatis.chart" . }}
app.kubernetes.io/name: {{ include "hatis.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: hatis-platform
{{- end -}}

{{- define "hatis.selectorLabels" -}}
app.kubernetes.io/name: {{ include "hatis.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "hatis.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "hatis.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "hatis.image" -}}
{{- $registry := .Values.global.imageRegistry -}}
{{- $repository := .Values.platform.image.repository -}}
{{- $tag := default .Chart.AppVersion .Values.platform.image.tag -}}
{{- if $registry -}}{{ printf "%s/%s:%s" $registry $repository $tag }}{{- else -}}{{ printf "%s:%s" $repository $tag }}{{- end -}}
{{- end -}}

{{- define "hatis.secretName" -}}
{{- required "existingSecret must name a Secret holding db-url, db-username, db-password, redis-url and token-signing-key" .Values.existingSecret -}}
{{- end -}}
