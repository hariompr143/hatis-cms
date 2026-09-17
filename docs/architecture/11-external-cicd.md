# 11 — External CI/CD integration

Customers bring their own pipelines. The platform does not require them to build
inside it, does not require a proprietary YAML dialect, and does not become the
only way to ship. This document defines the contract.

## 11.1 Principle

The platform is a **deployment target**, not a CI provider. A pipeline that builds
an image anywhere can deploy it here. A pipeline that wants the platform to build
for it can also do that, but nothing in the product depends on it.

## 11.2 The deploy contract

Three calls cover the whole flow.

**1. Register the release**

```http
POST /v1/releases
Authorization: Bearer <deployment token>
Idempotency-Key: 8f14e45f-...

{
  "applicationId": "6f1c...",
  "version": "1.4.2",
  "image": "registry.customer.internal/app:1.4.2",
  "digest": "sha256:9b2f...",
  "sourceType": "GITHUB",
  "sourceRef": "refs/heads/main",
  "gitCommit": "64028dac4428ee3939c458645d9c415c646b3479"
}
```

`201 Created`. An image tagged `latest`, or with no tag and no digest, is rejected:
a release that can change underneath its own name cannot be rolled back to or
audited.

**2. Scan**

The platform scans the registered image and records the verdict on the release. A
release whose scan has not passed cannot be deployed — the check is in
`DeploymentService.deploy`, not in the UI, so no client can skip it.

**3. Deploy**

```http
POST /v1/deployments
Authorization: Bearer <deployment token>
Idempotency-Key: 2c6ee24b-...

{ "environmentId": "a1b2...", "applicationId": "6f1c...", "releaseId": "d3e4...",
  "strategy": "ROLLING", "replicas": 3 }
```

`202 Accepted` with `Location: /v1/operations/{operationId}`. The rollout is
asynchronous; the pipeline polls:

```http
GET /v1/operations/{operationId}
```

until `state` is `SUCCESS` or `FAILED`. A pipeline that treats `202` as success
will report green on a failed rollout, which is why the operation id is in the
response body as well as the header.

**Rollback**

```http
POST /v1/deployments/rollback
{ "environmentId": "a1b2...", "applicationId": "6f1c..." }
```

Restores the previous deployable release. Nothing is rebuilt.

## 11.3 Deployment tokens

A deployment token is a scoped API key, not a user credential:

- issued per application, revocable independently;
- `hatis_` prefix, 32 random bytes, stored as SHA-256 — the plaintext is shown once;
- bound to one organization and one application, so a leaked token cannot deploy
  another customer's application;
- expires; the platform refuses an expired token rather than warning about it;
- carries `deployment:write` and `release:write` and nothing else.

Tokens are rotated by issuing a new one and revoking the old, with an overlap
window so a pipeline is never broken by a rotation.

## 11.4 Idempotency

Retries are expected — a pipeline step that times out will be re-run. Every
mutating deploy call accepts `Idempotency-Key`:

- the first request is executed and its response stored;
- a repeat with the same key returns the stored response, without re-executing;
- the same key with a **different body** returns `409 idempotency_conflict`, which
  means the pipeline has a bug that would otherwise deploy the wrong thing twice.

## 11.5 Webhooks

The platform emits events for every state change (`release.scanned`,
`deployment.started`, `deployment.finished`, `domain.provisioned`) to registered
endpoints. Deliveries are:

- signed with a per-endpoint secret (`Hatis-Signature: t=…,v1=…`, HMAC-SHA256 over
  the timestamp and raw body, so replay is detectable);
- retried with exponential backoff;
- recorded with their response status so a customer can see what their endpoint did.

A pipeline can therefore be driven by events instead of polling.

## 11.6 Reference integrations

GitHub Actions:

```yaml
- name: Register release
  id: release
  run: |
    curl -fsS -X POST "$HATIS_URL/v1/releases" \
      -H "Authorization: Bearer $HATIS_DEPLOY_TOKEN" \
      -H "Idempotency-Key: ${{ github.run_id }}-${{ github.run_attempt }}-release" \
      -H 'Content-Type: application/json' \
      -d "{\"applicationId\":\"$APP_ID\",\"version\":\"$VERSION\",
           \"image\":\"$IMAGE\",\"digest\":\"$DIGEST\",\"sourceType\":\"GITHUB\",
           \"gitCommit\":\"${{ github.sha }}\"}"

- name: Deploy
  id: deploy
  run: |
    curl -fsS -X POST "$HATIS_URL/v1/deployments" \
      -H "Authorization: Bearer $HATIS_DEPLOY_TOKEN" \
      -H "Idempotency-Key: ${{ github.run_id }}-${{ github.run_attempt }}-deploy" \
      -H 'Content-Type: application/json' \
      -d "{\"environmentId\":\"$ENV_ID\",\"applicationId\":\"$APP_ID\",
           \"releaseId\":\"$RELEASE_ID\",\"replicas\":3}"

- name: Wait for rollout
  run: |
    OPERATION=$(jq -r .operationId <<<"$DEPLOY_RESPONSE")
    while :; do
      STATE=$(curl -fsS "$HATIS_URL/v1/operations/$OPERATION" \
        -H "Authorization: Bearer $HATIS_DEPLOY_TOKEN" | jq -r .state)
      [ "$STATE" = SUCCESS ] && exit 0
      [ "$STATE" = FAILED ] && exit 1
      sleep 5
    done
```

Jenkins, GitLab CI, Argo Workflows and Tekton use the same three calls; there is no
platform-specific plugin to install and no agent to run in the customer's network.

## 11.7 What the platform will not do

It will not store a customer's registry credentials beyond what an image pull
requires, will not run arbitrary pipeline steps, and will not expose a shell into a
build. Those are the customer's pipeline's responsibility, and keeping them there
is what limits the blast radius of a platform compromise.
