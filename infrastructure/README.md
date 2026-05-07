# E2E Testing

To ensure a working application when releasing and to provide feedback for developers there is an extensive end-to-end testing suite using containers.

## Content

- [Completely automated E2E Testing](#completely-automated-e2e-testing)
- [Manual E2E Testing for debugging](#manual-e2e-testing-for-debugging)


## Completely automated E2E Testing

These are the more convenient steps for end-to-end testing without the need to install playwright or run Glacier and Playwright in your IDE.

### Dependencies

To execute the end-to-end tests you need `docker` with `docker compose` functionality.
It's sad but `containerd` will not be able to run the compose file because the `traefik` loadbalancer needs a docker socket to work.

The ports `80`, `443`, `8080` and `8090` need to be free on your system, and you need the privileges to use them.

### Step by step to a successful test

- Go into a shell in the root of your local copy of the repository. Execute the maven package goal:
  ```bash
  ./mvnw clean package
  ```
- Then unpack the content of the mastodon server. Switch to the (infrastructure)[./infrastructure] folder.
  Then untar (infrastructure-content.tar.gz)[infrastructure-content.tar.gz]:
  ```bash
  cd infrastructure
  tar -xf infrastructure-content.tar.gz -C ./
  ```
- Start the infrastructure and end-to-end tests with a single command:
  ```bash
  docker compose -f docker-compose.yaml up --build --abort-on-container-exit playwright --exit-code-from playwright
  ```

## Manual E2E Testing for debugging

These are the manual steps to give you more control and the ability to debug with the tools of your IDE.

### Dependencies

To execute the end-to-end tests locally in your IDE you need additional tooling.

Java should already be configured so that you can execute the maven goals with the maven wrapper `mvnw` (or `mvnw.cmd` if you can't use unixlike OS).

You also need `docker` with `docker compose` functionality.
It's sad but `containerd` will not be able to run the compose file because the `traefik` loadbalancer needs a docker socket to work.

You also need `playwright` and browsers. We install them during the step-by-step guide.

The ports `80`, `443`, `8080` and `8090` need to be free on your system, and you need the privileges to use them.


### Step by step to a successful test

- Go into a shell in the root of your local copy of the repository. Switch to the (frontend)[../frontend] folder and install the dependencies:
  ```bash
  cd frontend
  npm install
  npx playwright install --with-deps
  ```
- Then unpack the content of the mastodon server. Switch to the (infrastructure)[./infrastructure] folder.
  Then untar (infrastructure-content.tar.gz)[infrastructure-content.tar.gz]:
  ```bash
  cd infrastructure
  tar -xf infrastructure-content.tar.gz -C ./
  ```
- Start the mastodon server with additional services and a loadbalancer. Stay in your shell and execute the following commands:
  ```bash
  docker compose -f docker-compose.only-mastodon.yaml up -d
  ```
- Wait until **all** containers are healthy. Check with the `docker compose ps`:
  ```bash
  docker compose ps
  
    NAME        IMAGE                                        COMMAND                  SERVICE     CREATED          STATUS                        PORTS
    db          postgres:14-alpine                           "docker-entrypoint.s…"   db          39 minutes ago   Up About a minute (healthy)   5432/tcp
    proxy       traefik:3                                    "/entrypoint.sh trae…"   proxy       39 minutes ago   Up 39 minutes (healthy)       0.0.0.0:80->80/tcp, [::]:80->80/tcp, 0.0.0.0:443->443/tcp, [::]:443->443/tcp, 0.0.0.0:8090->8080/tcp, [::]:8090->8080/tcp
    redis       redis:7-alpine                               "docker-entrypoint.s…"   redis       39 minutes ago   Up 39 minutes (healthy)       6379/tcp
    sidekiq     ghcr.io/mastodon/mastodon:v4.3.3             "/usr/bin/tini -- bu…"   sidekiq     39 minutes ago   Up 2 minutes (healthy)        3000/tcp
    streaming   ghcr.io/mastodon/mastodon-streaming:v4.3.3   "docker-entrypoint.s…"   streaming   39 minutes ago   Up 39 minutes (healthy)       4000/tcp
    web         ghcr.io/mastodon/mastodon:v4.3.3             "/usr/bin/tini -- bu…"   web         39 minutes ago   Up 2 minutes (healthy)        3000/tcp
  ```
- Create a Run/Debug configuration for glacier in your IDE. I use a Spring Configuration in IntelliJ IDEA, but it should be similar in other ones.
  The main class is `de.seism0saurus.glacier.GlacierApplication`. The Spring Profile is default.
  It's important to set the environment variables, to connect glacier to the containerized Mastodon server and to set the variables in the frontend.
  ```bash
    ACCESS_KEY=hMfsEYl9Hgk2Pt-iTyZyvKvfbXh9tjXV41-tsr3vRak;
    DEVMODE=true;
    HANDLE=glacier_e2e_test@proxy;
    INSTANCE=proxy;
    MY_CITY=somecity;
    MY_COUNTRY=Germany;
    MY_DOMAIN=localhost:8080;
    MY_MAIL=kontakt@seism0saurus.de;
    MY_NAME=seism0saurus;
    MY_PHONE=+1234567890;
    MY_STREET_AND_NUMBER=sometherestreet 1;
    MY_WEBSITE=seism0saurus.de;
    MY_ZIP_CODE=12345;
  ```
  Here is a screenshot of my config.

  ![Screenshot of the Run/Debug configuration of the glacier backend](run_configuration_glacier.png){width=400px}
- Run the glacier configuration in your IDE
- Create a Run/Debug configuration for playwright in your IDE.
  The working directory is `frontend`.
  It's important to set the environment variables to run the tests against the local glacier instance and to publish toots in the containerized Mastodon during the tests.
  ```bash
    MASTODON_USER_API_URL=https://proxy;
    MASTODON_USER_ACCESS_TOKEN=pyPuRhw4cZJHN4QJuMX8mo9CFmziZp_BjvuCf71sV34;
    GLACIER_HANDLE=@glacier_e2e_test@proxy;
    BASE_URL=http://glacier:8080;
  ```
  ![Screenshot of the Run/Debug configuration of the playwright test](run_configuration_playwright.png){width=400px}
- Run the playwright configuration in your IDE


## Mode-specific debug recipes

Glacier has five Playwright projects, each targeting a distinct backend state. When debugging test failures, use the recipe that matches the failing project. Start the Mastodon-only stack first (per the [Manual E2E Testing for debugging](#manual-e2e-testing-for-debugging) section above), then start Glacier with the required environment variables, and finally run only the relevant Playwright project.

### Standard modes (chromium / firefox / webkit)

These three projects run against the default live stack. No special backend flags are needed beyond the base environment variables from the manual debug section.

**Backend start (example):**
```bash
ACCESS_KEY=hMfsEYl9Hgk2Pt-iTyZyvKvfbXh9tjXV41-tsr3vRak \
DEVMODE=true \
HANDLE=glacier_e2e_test@proxy \
INSTANCE=proxy \
MY_CITY=somecity \
MY_COUNTRY=Germany \
MY_DOMAIN=localhost:8080 \
MY_MAIL=kontakt@seism0saurus.de \
MY_NAME=seism0saurus \
MY_PHONE=+1234567890 \
MY_STREET_AND_NUMBER="sometherestreet 1" \
MY_WEBSITE=seism0saurus.de \
MY_ZIP_CODE=12345 \
GLACIER_SHARE_HOST=share.proxy \
GLACIER_SHARE_IMGPROXY_HMAC_SECRET=dev-only-secret-replace-in-prod \
  java -jar target/glacier-0.0.9.jar
```

**Run specific Playwright project:**
```bash
cd frontend
# Run only the chromium project (all standard specs):
MASTODON_USER_API_URL=https://proxy \
MASTODON_USER_ACCESS_TOKEN=pyPuRhw4cZJHN4QJuMX8mo9CFmziZp_BjvuCf71sV34 \
GLACIER_HANDLE=@glacier_e2e_test@proxy \
BASE_URL=http://localhost:8080 \
  npx playwright test --project=chromium

# Or firefox / webkit:
npx playwright test --project=firefox
npx playwright test --project=webkit
```

### Killswitch mode

**What it tests:** `GLACIER_FALLBACK_ENABLED=false` disables the HTTP fallback polling path. `FallbackController` returns 404 for `/rest/messages` and `/rest/share/{id}/messages`. The frontend enters the "Limited" (`KILLSWITCHED`) indicator state.

**Config key:** `glacier.fallback.enabled` (set via `GLACIER_FALLBACK_ENABLED` env var, default `true`).

**Backend start — add `GLACIER_FALLBACK_ENABLED=false`:**
```bash
ACCESS_KEY=hMfsEYl9Hgk2Pt-iTyZyvKvfbXh9tjXV41-tsr3vRak \
DEVMODE=true \
HANDLE=glacier_e2e_test@proxy \
INSTANCE=proxy \
MY_CITY=somecity \
MY_COUNTRY=Germany \
MY_DOMAIN=localhost:8080 \
MY_MAIL=kontakt@seism0saurus.de \
MY_NAME=seism0saurus \
MY_PHONE=+1234567890 \
MY_STREET_AND_NUMBER="sometherestreet 1" \
MY_WEBSITE=seism0saurus.de \
MY_ZIP_CODE=12345 \
GLACIER_SHARE_HOST=share.proxy \
GLACIER_SHARE_IMGPROXY_HMAC_SECRET=dev-only-secret-replace-in-prod \
GLACIER_FALLBACK_ENABLED=false \
  java -jar target/glacier-0.0.9.jar
```

**Run the killswitch Playwright project:**
```bash
cd frontend
MASTODON_USER_API_URL=https://proxy \
MASTODON_USER_ACCESS_TOKEN=pyPuRhw4cZJHN4QJuMX8mo9CFmziZp_BjvuCf71sV34 \
GLACIER_HANDLE=@glacier_e2e_test@proxy \
BASE_URL=http://localhost:8080 \
  npx playwright test --project=killswitch
```

**Specs covered:** `frontend/e2e/workflows/fallback-killswitch.spec.ts`, `frontend/e2e/workflows/share-link-killswitch.spec.ts`, `frontend/e2e/workflows/fallback-unsubscribe-prune.spec.ts`.

### Insecure transport mode

**What it tests:** Glacier served over plain HTTP (no TLS). `COOKIE_SECURE=false` removes the `Secure` attribute and `__Host-` prefix from `wallId` and `__Host-shareViewerId` cookies so they are sent by the browser over HTTP. The frontend enters the "Insecure Connection" indicator state.

**Config key:** `glacier.cookie.secure` (set via `COOKIE_SECURE` env var, default `true`). The backend binds an additional plain-HTTP port at `:8081` via `docker-compose.override.insecure.yaml`; in the IDE debug flow you can pass `COOKIE_SECURE=false` and use port `8080` directly (it is already plain HTTP when running from the IDE without Traefik).

**Backend start — add `COOKIE_SECURE=false`:**
```bash
ACCESS_KEY=hMfsEYl9Hgk2Pt-iTyZyvKvfbXh9tjXV41-tsr3vRak \
DEVMODE=true \
HANDLE=glacier_e2e_test@proxy \
INSTANCE=proxy \
MY_CITY=somecity \
MY_COUNTRY=Germany \
MY_DOMAIN=localhost:8080 \
MY_MAIL=kontakt@seism0saurus.de \
MY_NAME=seism0saurus \
MY_PHONE=+1234567890 \
MY_STREET_AND_NUMBER="sometherestreet 1" \
MY_WEBSITE=seism0saurus.de \
MY_ZIP_CODE=12345 \
GLACIER_SHARE_HOST=share.proxy \
GLACIER_SHARE_IMGPROXY_HMAC_SECRET=dev-only-secret-replace-in-prod \
COOKIE_SECURE=false \
  java -jar target/glacier-0.0.9.jar
```

**Run the insecure Playwright project** — note `BASE_URL_INSECURE` points to the loopback HTTP port (port 8080 in the IDE debug flow, or 8081 when using the compose override):
```bash
cd frontend
MASTODON_USER_API_URL=https://proxy \
MASTODON_USER_ACCESS_TOKEN=pyPuRhw4cZJHN4QJuMX8mo9CFmziZp_BjvuCf71sV34 \
GLACIER_HANDLE=@glacier_e2e_test@proxy \
BASE_URL=http://localhost:8080 \
BASE_URL_INSECURE=http://localhost:8080 \
  npx playwright test --project=insecure
```

**Specs covered:** `frontend/e2e/workflows/fallback-insecure.spec.ts`, `frontend/e2e/workflows/share-link-insecure.spec.ts`, `frontend/e2e/workflows/fallback-unsubscribe-prune.spec.ts`.

### Accessibility mode (a11y)

**What it tests:** WCAG 2.2 AA axe-core scans and keyboard/zoom accessibility checks against the live Glacier + Mastodon stack. Runs chromium-only. No special backend flags are needed beyond the base configuration.

**Backend start:** same as [Standard modes](#standard-modes-chromium--firefox--webkit) above.

**Run the a11y Playwright project:**
```bash
cd frontend
MASTODON_USER_API_URL=https://proxy \
MASTODON_USER_ACCESS_TOKEN=pyPuRhw4cZJHN4QJuMX8mo9CFmziZp_BjvuCf71sV34 \
GLACIER_HANDLE=@glacier_e2e_test@proxy \
BASE_URL=http://localhost:8080 \
  npx playwright test --project=a11y
```

**Specs covered:** all files matching `**/*-a11y.spec.ts` (e.g. `share-link-a11y.spec.ts`, `subscriptions-prune-a11y.spec.ts`).

