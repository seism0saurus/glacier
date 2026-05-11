# Glacier — Operator / Admin Guide

This guide is for people who want to **deploy their own Glacier instance**.
If you just want to use the demo, see the [main README](../README.md).
If you want to contribute code, see the [developer guide](DEVELOPER.md).

## Contents

- [Prerequisites](#prerequisites)
- [Reverse proxy and subdomain setup](#reverse-proxy-and-subdomain-setup)
- [Build the jar](#build-the-jar)
- [Build a container image](#build-a-container-image)
- [Run the container](#run-the-container)
  - [Required variables](#required-variables)
  - [Share link variables](#share-link-variables)
  - [Optional tuning variables](#optional-tuning-variables)
  - [Docker](#docker)
  - [Containerd with nerdctl](#containerd-with-nerdctl)
- [Operational modes](#operational-modes)

## Prerequisites

- **Java 23 JDK** — required to build and run the jar. [Temurin](https://adoptium.net/de/temurin/releases/) is recommended.
- **A Mastodon account** for the bot. Create a dedicated bot account on any Mastodon instance and generate a read-access API token under *Settings → Applications*.

## Reverse proxy and subdomain setup

Glacier is designed to run behind a reverse proxy (e.g. Traefik or nginx) that handles TLS termination.

**Two hostnames** are required:
- `glacier.example.com` — the main wall application
- `share.glacier.example.com` — read-only share views (set via `GLACIER_SHARE_HOST`)

Both hostnames must route to the same Glacier container on port 8080. Glacier uses the `Host` header to distinguish them internally.

Configure your reverse proxy to forward the real client IP so that rate limiting works correctly:

```
# nginx
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
```

For plain-HTTP development or staging deployments, set `COOKIE_SECURE=false` — this removes the `Secure` attribute from cookies so they work over HTTP.

## Build the jar

```bash
git clone git@github.com:seism0saurus/glacier.git
cd glacier
./mvnw clean package -DskipTests
```

The resulting jar is `target/glacier-0.0.9.jar` and includes the pre-built Angular frontend.

To verify the build with all tests before packaging:
```bash
./mvnw clean verify
```

## Build a container image

After building the jar, copy it into the container image directory and build the image.

```bash
cp target/glacier-0.0.9.jar infrastructure/glacier/
cd infrastructure/glacier
```

### Docker

```bash
docker build -t glacier --build-arg JAR_FILE=glacier-0.0.9.jar .
```

### Buildah

```bash
buildah build --build-arg JAR_FILE=glacier-0.0.9.jar -f Dockerfile -t glacier .
```

A pre-built image for the latest stable release is also available:
```
ghcr.io/seism0saurus/glacier:main
```

## Run the container

### Required variables

| Variable | Description | Example |
|---|---|---|
| `INSTANCE` | Mastodon instance hostname for the bot account | `botsin.space` |
| `HANDLE` | Full Mastodon handle of the bot account | `glacier@glacier.events` |
| `ACCESS_KEY` | API access token for the bot account (read access) | `abc123...` |
| `MY_DOMAIN` | Public hostname of your Glacier deployment; include port for non-standard ports | `glacier.example.com` |
| `MY_NAME` | Your name or organisation (Legal Notice / GDPR page) | `Jane Doe` |
| `MY_STREET_AND_NUMBER` | Street address for the Legal Notice | `Example Street 1` |
| `MY_ZIP_CODE` | Postal code for the Legal Notice | `12345` |
| `MY_CITY` | City for the Legal Notice | `Berlin` |
| `MY_COUNTRY` | Country for the Legal Notice | `Germany` |
| `MY_PHONE` | Phone number for the Legal Notice | `+49 30 12345678` |
| `MY_MAIL` | Contact email address for the Legal Notice | `admin@example.com` |
| `MY_WEBSITE` | Website URL for the Legal Notice | `https://example.com` |
| `GLACIER_SHARE_IMGPROXY_HMAC_SECRET` | HMAC-SHA256 secret (minimum 32 characters) for signing image proxy URLs. The backend refuses to start in secure mode (`COOKIE_SECURE=true`) if this value is absent or shorter than 32 characters. Use a cryptographically random value — never reuse a password or a predictable string. | *(no default — must be set)* |

> **Why the Legal Notice fields?** In some countries (e.g. Germany) operators of public websites are legally required to provide contact information (Impressum). These fields populate Glacier's built-in Legal Notice and GDPR pages.

> **Why is `GLACIER_SHARE_IMGPROXY_HMAC_SECRET` required?** Without it the image proxy endpoint is either disabled (causing share views to load without toot images) or signs URLs with an empty secret — both are insecure. In production (`COOKIE_SECURE=true`, the default) the application performs a startup check and fails fast if the secret is missing or too short, preventing an accidentally-insecure deployment.

### Share link variables

The following variables tune the share link and QR code feature. `GLACIER_SHARE_HOST` must also be set for share links to work; the remaining variables have sensible defaults.

| Variable | Description | Default |
|---|---|---|
| `GLACIER_SHARE_HOST` | Hostname for read-only share views — must be a subdomain of `MY_DOMAIN` | `share.example.com` |
| `GLACIER_SHARE_TTL` | How long a share link stays valid (ISO-8601 duration) | `P7D` (7 days) |
| `GLACIER_SHARE_MAX_VIEWERS_PER_LINK` | Maximum concurrent viewers per share link | `100` |
| `GLACIER_SHARE_MAX_ACTIVE_PER_SHARER` | Maximum active links a single wall owner can have at once | `3` |
| `GLACIER_SHARE_MAX_ACTIVE_PER_IP` | Maximum active links per source IP | `10` |

### Optional tuning variables

| Variable | Description | Default |
|---|---|---|
| `COOKIE_SECURE` | Set `false` for plain-HTTP deployments (dev/staging only) | `true` |
| `GLACIER_FALLBACK_ENABLED` | Set `false` to disable the fallback polling path entirely (killswitch mode) | `true` |
| `GLACIER_CACHE_SIZE` | Number of toots retained per (wall, hashtag) pair for fallback replay | `20` |
| `GLACIER_CACHE_MAX_HASHTAGS_PER_PRINCIPAL` | Maximum hashtags a single wall can subscribe to | `10` |
| `GLACIER_CACHE_MAX_PRINCIPALS` | Maximum number of concurrent wall sessions | `10000` |

### Docker

```bash
docker run -ti \
  -e INSTANCE=my-mastodon-instance \
  -e HANDLE=my-mastodon-handle \
  -e ACCESS_KEY=my-secret-mastodon-api-key \
  -e MY_DOMAIN=localhost:8080 \
  -e MY_NAME="Jane Doe" \
  -e MY_STREET_AND_NUMBER="Example Street 1" \
  -e MY_ZIP_CODE=12345 \
  -e MY_CITY=Berlin \
  -e MY_COUNTRY=Germany \
  -e MY_PHONE="+49 30 12345678" \
  -e MY_MAIL=admin@example.com \
  -e MY_WEBSITE=https://example.com \
  -p 8080:8080 \
  ghcr.io/seism0saurus/glacier:main
```

### Containerd with nerdctl

```bash
nerdctl run -ti \
  -e INSTANCE=my-mastodon-instance \
  -e HANDLE=my-mastodon-handle \
  -e ACCESS_KEY=my-secret-mastodon-api-key \
  -e MY_DOMAIN=localhost:8080 \
  -e MY_NAME="Jane Doe" \
  -e MY_STREET_AND_NUMBER="Example Street 1" \
  -e MY_ZIP_CODE=12345 \
  -e MY_CITY=Berlin \
  -e MY_COUNTRY=Germany \
  -e MY_PHONE="+49 30 12345678" \
  -e MY_MAIL=admin@example.com \
  -e MY_WEBSITE=https://example.com \
  -p 8080:8080 \
  ghcr.io/seism0saurus/glacier:main
```

## Configuration property key migration

If you set operator fields via `-D` JVM system properties rather than the `MY_*` environment variables, the property keys are now `glacier.operator.name`, `glacier.operator.mail`, etc. (previously `glacier.operatorName`, `glacier.operatorMail`). Environment-variable-based configuration (`MY_NAME`, `MY_MAIL`, …) is unchanged.

## Operational modes

Glacier has three modes controlled by configuration:

| Mode | Condition | Behaviour |
|---|---|---|
| **Live** | Default (`GLACIER_FALLBACK_ENABLED=true`, Mastodon stream connected) | Toots arrive in real time via WebSocket streaming |
| **Fallback** | `GLACIER_FALLBACK_ENABLED=true`, stream unavailable | Frontend polls `/rest/messages`; Glacier serves the last `GLACIER_CACHE_SIZE` toots from its in-memory cache |
| **Killswitch** | `GLACIER_FALLBACK_ENABLED=false` | Fallback polling endpoint returns 404; only live streaming works |

The switch between live and fallback is automatic — Glacier degrades gracefully when the Mastodon connection drops and recovers when it comes back. Set `GLACIER_FALLBACK_ENABLED=false` only if you want to disable the polling path entirely (e.g. to reduce attack surface on a hardened deployment).
