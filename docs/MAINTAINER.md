# Glacier — Wartungsleitfaden (Maintainer Guide)

Dieser Leitfaden richtet sich an den **menschlichen Maintainer** von Glacier. Er beschreibt,
was die CI/CD-Pipeline bei jedem Commit, jedem Pull Request und jedem Release tatsächlich
tut, welche Sicherheitsinvarianten als ausführbare Tests erzwungen werden, und welche
Schutzschichten du manuell in den GitHub-Repository-Einstellungen pflegen musst, weil sie
sich nicht aus Repo-Dateien ableiten lassen.

Wenn du Code beisteuerst statt die Pipeline zu warten, lies zuerst den
[Entwickler-Leitfaden](DEVELOPER.md). Für die Architektur- und Sicherheitsentscheidungen,
die zu dieser Pipeline-Topologie geführt haben, siehe das
[Planning-ADR](decisions/2026-07-03-planning-secure-ci-pipeline.md).

## Inhalt

- [Grundidee: Trust-Tier-Topologie](#grundidee-trust-tier-topologie)
- [Bei jedem Commit](#bei-jedem-commit)
- [Bei Pull Requests](#bei-pull-requests)
- [Bei Releases](#bei-releases)
- [Sicherheitsmodell der Pipeline](#sicherheitsmodell-der-pipeline)
- [Manuelle Einstellungen (nicht repo-versioniert)](#manuelle-einstellungen-nicht-repo-versioniert)
- [Bekannte akzeptierte Restrisiken](#bekannte-akzeptierte-restrisiken)

## Grundidee: Trust-Tier-Topologie

Die Pipeline ist nicht nach *Ereignis* (push vs. pull_request), sondern nach *Vertrauensstufe*
partitioniert:

- Ein **secret-freier, wiederverwendbarer Kern** (`_build.yml`, `_e2e.yml`,
  `_security-dast.yml`) — aufrufbar per `workflow_call`, deklariert seine eigenen minimalen
  `permissions:` und referenziert nie ein Repository-Secret außer dem Ambient-`GITHUB_TOKEN`.
  Diesen Kern rufen `quality.yml`, `pull-request.yml`, `full-suite.yml` und `security.yml`
  auf — auch von einem Fork-PR aus ist dieser Pfad also für einen Angreifer nutzlos.
- Ein **einziger privilegierter Kontext** (`build-and-deploy.yml`), der als einziger jemals
  die Deploy-Secrets (`SSH_KEY`, `KNOWN_HOSTS`, `SSH_USER`, `SSH_PORT`) erreichen kann. Er
  wird ausschließlich durch das Pushen eines Release-Tags ausgelöst — es gibt dort keinen
  `pull_request`-Trigger mehr.

Details siehe ADR-CI-01 im Planning-ADR.

## Bei jedem Commit

Jeder Push auf **jeden Branch** (`push: branches: ['**']`) löst `quality.yml` aus. Das ist
die direkte Umsetzung der Anforderung *"Every commit on a branch should trigger quality
checks"*.

`quality.yml` ruft ausschließlich den secret-freien `_build`-Kern auf (Cache-Familie
`main`). Dabei laufen:

- Compile (`./mvnw clean compile`)
- Frontend-Lint (`npm run lint`)
- Frontend-Architektur-Gate (`npm run arch`, Sheriff — DDD-Bounded-Contexts + Layer-Regeln)
- i18n-Paritätscheck (`npm run i18n:check` — Template-`@@id`s vs. `messages.en.json`)
- SHA-256-Checksum-Stolperdraht für `sqlite-jdbc` und `bigbone` (Supply-Chain-Pin, siehe
  `dependency-vetting`-Skill)
- Unit- und Integrationstests (`./mvnw verify`) inkl. Jacoco-Coverage-Gate
- Frontend-Dependency-Audit (`npm audit --audit-level=high --omit=dev`)
- Secret-Scan (`gitleaks`)

**Was hier bewusst NICHT passiert:** kein Deploy, kein Zugriff auf ein Repository-Secret
außer dem Ambient-`GITHUB_TOKEN` (nur für die gitleaks-Ratenbegrenzung), kein Docker-Image-
Push. `quality.yml` filtert nicht auf `tags:` — ein Release-Tag-Push läuft ausschließlich
über `build-and-deploy.yml`, damit beide Workflows nie um denselben Lauf konkurrieren.

**Cache-Familien-Trennung:** Jeder Aufrufer übergibt dem `_build`-Kern einen eigenen
`cache-prefix` (`main` für `quality.yml`, `pull` für `pull-request.yml`, `dispatch` für
`full-suite.yml`, `security` für `security.yml`). Ein Fork-PR-Lauf kann dadurch niemals die
`main`-Cache-Familie lesen oder vergiften (ADR-CI-09) — sicherheitsrelevant, weil ein
kompromittierter Cache-Eintrag sonst ein Einfallstor für Supply-Chain-Angriffe wäre.

## Bei Pull Requests

`pull-request.yml` läuft bei **jedem** Pull Request — inklusive Fork-PRs — und prüft
deutlich mehr als `quality.yml`:

- den vollen `_build`-Kern (Cache-Familie `pull`)
- den kompletten 5-Leg-e2e-Lauf über `_e2e.yml`: `standard`, `killswitch`, `a11y`,
  `insecure`, `share-https` (siehe `glacier-fallback-mode-discipline` — kein Leg darf beim
  Bearbeiten dieser Datei entfallen)
- den dynamischen Sicherheitsscan `_security-dast.yml` (ZAP-Baseline, Security-Header-Audit,
  WebSocket-BOLA-Probe, Trivy Image- und Filesystem-Scan) als eigenständiger Job
  `security-dast-sarif`

### Fork-PR-Besonderheiten

- Ein Fork-PR erhält von GitHub automatisch ein **read-only Token** ohne Zugriff auf
  Repository-Secrets — der `_build`/`_e2e`/`_security-dast`-Kern ist so gebaut, dass er
  dafür ohnehin nie ein Secret bräuchte.
- **SARIF-Uploads sind fork-tolerant** (`continue-on-error: true`, ADR-CI-15): ein Fork-PR
  darf nicht in den Security-Tab des Repos schreiben, daher würde ein SARIF-Upload dort
  fehlschlagen. Das ist erwartetes Verhalten (RR-1) — der eigentliche Merge-Gate bleibt auf
  dem **Exit-Code des Scanners** (Trivy `exit-code: '1'` bei HIGH/CRITICAL), nicht auf dem
  Erfolg des Uploads. Für einen Fork-PR siehst du Findings also nur als roten Run-Check, nie
  im Security-Tab.
- Der Coverage-Kommentar auf dem PR kommt **nicht** aus `pull-request.yml` selbst, sondern
  aus dem separaten, privilegierten `pr-comment.yml`, ausgelöst über `workflow_run` (ADR-
  CI-14). Das untrusted `pull-request.yml` schreibt lediglich die PR-Nummer
  (`github.event.number`) in eine Artefakt-Datei; `pr-comment.yml` lädt sie `run-id`-
  gebunden herunter, validiert sie fail-closed gegen `^[0-9]+$` und postet den Kommentar
  erst danach über `gh pr comment --body-file`. So kann eine Fork-PR niemals ihren eigenen,
  unvertrauenswürdigen Code in den privilegierten `pull-requests: write`-Kontext einschleusen.

### Vor-Merge-Checkliste für den Maintainer

Bevor du einen PR mergst, prüfe:

- [ ] Alle **5 e2e-Legs** grün (`standard`, `killswitch`, `a11y`, `insecure`, `share-https`)
- [ ] `security-dast-sarif` grün — Trivy- und ZAP-Exit-Code ohne HIGH/CRITICAL-Findings
- [ ] `CodeQL / Analyze (java-kotlin)` und `CodeQL / Analyze (javascript-typescript)` grün
      (separater Workflow `codeql.yml`, läuft auch auf `pull_request` gegen `main`)
- [ ] alle `ci`-Struktur-Gates grün (siehe [Sicherheitsmodell](#sicherheitsmodell-der-pipeline))
- [ ] bei einem Fork-PR: SARIF-Findings tauchen nur als Run-Check auf, nicht im Security-Tab
      — dort trotzdem nachsehen, nicht nur auf den Security-Tab verlassen (RR-1)

`full-suite.yml` (`workflow_dispatch`) steht dir zusätzlich zur Verfügung, um denselben
vollen Kern + 5-Leg-e2e + DAST-Scan manuell auf einem beliebigen Branch/Ref laufen zu
lassen — praktisch, um einen Feature-Branch vorab vollständig zu prüfen, bevor überhaupt ein
PR existiert.

## Bei Releases

Releases werden als **annotierte git-Tags** (`v<version>`) geschnitten (ADR-CI-18) — nicht
mehr als Branches.

### 1. Version bumpen und Tag erzeugen

```bash
./push_version.sh <version>   # z. B. ./push_version.sh 0.0.9
```

`push_version.sh` bumpt `pom.xml`, `README.md` (Jar-Dateiname) und
`frontend/package.json`/`package-lock.json`, committet den Bump und legt lokal den
annotierten Tag `v<version>` an. **Das Skript pusht nichts automatisch** — das ist
Absicht, weil das Pushen des Tags eine irreversible Produktionsaktion auslöst.

### 2. Manuell pushen, wenn du bereit bist

```bash
git push origin HEAD
git push origin v<version>
```

Erst der zweite Befehl (`git push origin v<version>`) startet `build-and-deploy.yml`.

### Was der Tag-Push auslöst

`build-and-deploy.yml` reagiert auf `push: tags: ['v*.*.*']` und durchläuft, in dieser
Reihenfolge:

1. **`test`** — delegiert Compile → Test → Package an denselben secret-freien `_build`-Kern,
   den auch `quality.yml`/`pull-request.yml` nutzen — inklusive des sqlite-jdbc/bigbone-
   Checksum-Stolperdrahts. Der Release-Pfad ist damit nicht von der
   Dependency-Substitutions-Prüfung ausgenommen (OWASP A08:2021).
2. **`mutation`** — PITest-Mutationstest, informativ/gatend für `main`, kein Vorbedingung für
   `publish-image` oder `deploy`.
3. **`dependency-submission`** — Dependency-Graph-Submission (braucht `contents: write`,
   deshalb ausschließlich in diesem einen privilegierten Workflow, nie im secret-freien Kern).
4. **`e2e`** — ein **4-Leg**-Playwright-Lauf (`standard`, `killswitch`, `a11y`, `insecure`),
   baut zusätzlich das Docker-Image (Standard-Leg) und lädt es als Artefakt hoch. **Ohne
   `share-https`** — anders als bei `pull-request.yml`/`full-suite.yml` (siehe
   [Vor-Merge-Checkliste](#vor-merge-checkliste-für-den-maintainer)) läuft das fünfte Leg
   aus `_e2e.yml` auf dem Release-Pfad nicht mit.

   **Bekanntes Konsolidierungs-Residuum (Drift-Risiko):** `build-and-deploy.yml` pflegt hier
   eine **eigene, inline definierte** e2e-Matrix statt — wie `pull-request.yml` und
   `full-suite.yml` — per `uses:` an `_e2e.yml` zu delegieren. Dadurch fehlt `share-https`
   nicht aus einer bewussten Design-Entscheidung, sondern weil diese Matrix beim Anlegen des
   fünften Legs in `_e2e.yml` nicht mitgezogen wurde. Beide Matrizen müssen von Hand
   synchron gehalten werden, bis diese Datei ebenfalls auf `_e2e.yml` umgestellt ist (offenes
   Follow-up, noch nicht behoben).
5. **`trivy-fs-sarif`** / **`trivy-image-sarif`** — Trivy-Scan auf Jar-Abhängigkeiten bzw. auf
   das exakte, noch ungepushte Docker-Image.
6. **`zap-and-headers`** — ZAP-Baseline, Security-Header-Audit, WebSocket-BOLA-Probe gegen
   den laufenden Container.
7. **`publish-image`** — läuft erst, wenn `e2e` + `trivy-fs-sarif` + `trivy-image-sarif` +
   `zap-and-headers` **alle** grün sind. Signiert das Image keyless mit **cosign** (Sigstore/
   Fulcio-OIDC, kein Long-Lived-Key), erzeugt eine **SLSA-Provenance**-Attestierung und
   attestiert das **SBOM** — jeweils in die GHCR-Registry gepusht.
8. **`deploy`** — läuft erst nach `publish-image`, mit `environment: glacier.seism0saurus.de`
   als GitHub-Environment-Gate. Erst in diesem einen Job sind die SSH-Deploy-Secrets
   (`SSH_KEY`, `KNOWN_HOSTS`, `SSH_USER`, `SSH_PORT`) überhaupt sichtbar.

Verifizieren kannst du eine signierte Image-Version so:

```bash
cosign verify ghcr.io/seism0saurus/glacier@<digest> \
  --certificate-identity-regexp 'https://github.com/seism0saurus/glacier/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
cosign verify-attestation --type slsaprovenance ... ghcr.io/seism0saurus/glacier@<digest>
```

### Das Deploy-Gate

Der Deploy-Job ist nicht durch den Tag-Push allein geschützt (der ist laut ADR-CI-08
sicherheitsneutral) — der eigentliche Schutz ist die `needs`-Kette
(`deploy` → `publish-image` → `e2e` + `trivy-fs-sarif` + `trivy-image-sarif` +
`zap-and-headers`) kombiniert mit dem GitHub-`environment:`-Gate. Ohne eine manuell
konfigurierte Environment-Protection-Regel (siehe
[Manuelle Einstellungen](#manuelle-einstellungen-nicht-repo-versioniert)) ist dieses Gate
aber nur eine Ablauf-Reihenfolge, kein hartes Zugriffs-Gate.

## Sicherheitsmodell der Pipeline

Die Trust-Tier-Topologie in einem Absatz: es gibt genau **einen** secret-freien,
wiederverwendbaren Kern (`_build`/`_e2e`/`_security-dast`), den jeder untrusted-tier-
Aufrufer — auch ein Fork-PR — erreichen kann, und genau **einen** privilegierten Kontext
(`build-and-deploy.yml`), der die Deploy-Secrets je zu Gesicht bekommt. Kein Workflow ruft
den Kern jemals mit `secrets: inherit` auf; jeder Reusable-Workflow deklariert seine eigenen
minimalen `permissions:`. Diese Trennung ist die direkte Antwort auf die Feature-Anforderung
*"i can do a full test of my feature branch without worying that bad actors could extract
secrets from ci or trigger malicious actions through pull requests"*.

Die Sicherheitsinvarianten dieser Topologie sind nicht nur beschrieben, sondern als
**ausführbare SnakeYAML-Struktur-Gates** unter `src/test/java/de/seism0saurus/glacier/ci/`
hinterlegt — sie laufen bei jedem `./mvnw verify` mit und schlagen fehl, sobald jemand eine
Workflow-Datei so ändert, dass eine Invariante bricht. Die wichtigsten Gates:

| Gate-Klasse | Erzwingt |
|---|---|
| `WorkflowSecretIsolationTest` | Kein `pull_request_target` irgendwo im Repo; kein Nicht-`GITHUB_TOKEN`-Secret in einem untrusted-tier-Workflow; kein `secrets: inherit` |
| `WorkflowPermissionsMinimizationTest` | Jeder Workflow hat ein explizites, minimales Top-Level-`permissions:`; erweiterte Scopes nur auf einer Allowlist; `deploy`/`publish-image`/`dependency-submission` existieren ausschließlich in `build-and-deploy.yml` |
| `WorkflowDeployTriggerTest` | `build-and-deploy.yml` hat keinen `pull_request`-Trigger; Deploy-Secrets sind nur aus dem `environment:`-gegateten Job erreichbar; kein `set -x`/Shell-Tracing im Deploy-Schritt; `deploy` hängt transitiv von `publish-image` → `e2e`+Trivy+ZAP ab |
| `WorkflowRunPrCommentSafetyTest` | `pr-comment.yml` läuft nur über `workflow_run`, lädt Artefakte ausschließlich `run-id`-gebunden, validiert die PR-Nummer fail-closed gegen `^[0-9]+$` und baut oder führt niemals PR-kontrollierten Code aus |
| `EveryBranchQualityTriggerTest` | `quality.yml` triggert auf `push: branches: ['**']`, ist secret-frei und ruft ausschließlich den `_build`-Kern auf |
| `SecurityDastSelfContainedTest` | `_security-dast.yml` baut Jar und Docker-Image im eigenen Lauf, ohne eine Vorbedingung auf ein bereits existierendes GHCR-Image |
| `WorkflowCachePoisoningIsolationTest` | PR-getriggerte Workflows verwenden ausschließlich `pull`/`pr`-präfixierte Cache-Schlüssel, nie die `main`-Familie |
| `WorkflowScriptInjectionTest` | Kein `run:`-Skript interpoliert `${{ github.event.* }}` direkt — immer über `env:` + `"$VAR"` |
| `WorkflowActionsPinnedTest` | Jede Drittanbieter-`uses:`-Referenz ist auf einen vollen 40-Hex-Commit-SHA gepinnt |
| `WorkflowNoSelfHostedRunnerTest` | Kein Job läuft auf einem `self-hosted`-Runner |
| `WorkflowSarifForkToleranceTest` | Jeder `upload-sarif`-Schritt ist fork-tolerant (`continue-on-error`); der eigentliche Finding-Gate bleibt auf dem Scanner-Exit-Code |
| `WorkflowEnvironmentSecretTest` | Jeder Job, der ein Deploy-Secret referenziert, deklariert ein `environment:` |
| `WorkflowYamlInventoryTest` | Bijektive Vollständigkeit: jede Workflow-Datei im Repo ist im Inventar erfasst, keine vergisst |
| `BigboneChecksumPinTest` / `BuildAndDeployChecksumTripwireTest` | SHA-256-Stolperdraht für `sqlite-jdbc`/`bigbone` ist tatsächlich in `_build.yml` verankert |

Wenn du eine dieser Dateien änderst, lies zuerst den zugehörigen Test — er ist die
maßgebliche, ausführbare Spezifikation der Invariante, nicht dieser Text hier.

## Manuelle Einstellungen (nicht repo-versioniert)

Die folgenden Schutzschichten liegen in den **GitHub-Repository-Einstellungen** und lassen
sich von keinem Repo-Gate erzwingen — ein Struktur-Test kann prüfen, dass ein Workflow ein
`environment:` referenziert, aber nicht, dass für dieses Environment in den Settings auch
tatsächlich eine Protection-Regel konfiguriert ist. Checkliste:

- [x] **Environment-scoped Deploy-Secrets** (RR-4/SR-CI-16, offener Follow-up #10): `SSH_KEY`,
      `KNOWN_HOSTS`, `SSH_USER`, `SSH_PORT` sind ausschließlich im Environment
      `glacier.seism0saurus.de` hinterlegt (Settings → Environments), nicht als
      Repository- oder Organization-Secrets.
- [x] **Deployment-Tag-Restriction** auf diesem Environment: nur Refs, die zu `v*.*.*`
      passen, dürfen das Environment referenzieren (Settings → Environments →
      `glacier.seism0saurus.de` → Deployment branches and tags).
- [x] **Branch-Protection auf `main`**: Pull-Request-Pflicht vor dem Merge, keine
      Direkt-Pushes.
- [x] **CodeQL als Required Status Check**: `CodeQL / Analyze (java-kotlin)` und
      `CodeQL / Analyze (javascript-typescript)` unter Settings → Branches → `main` →
      Require status checks to pass eingetragen.

**Zuletzt verifiziert am:** 04.07.2026 (bitte bei jeder Überprüfung dieses Datum
aktualisieren)

## Bekannte akzeptierte Restrisiken

Die folgenden Restrisiken wurden am 2026-07-03 vom Maintainer bewusst akzeptiert. Details,
Begründung und die zugehörigen GO-Bedingungen stehen im
[Planning-ADR](decisions/2026-07-03-planning-secure-ci-pipeline.md#open-risks-accepted-by-the-user).

| Risiko | Kurzbeschreibung |
|---|---|
| RR-1 | Fork-PR-SARIF erreicht nicht den Security-Tab (nur Run-Checks); Merge-Gate bleibt auf dem Scanner-Exit-Code |
| RR-2 | Der `workflow_run`-Coverage-Kommentar hat eine minimale, datenbasierte Artefakt-Angriffsfläche (durch ADR-CI-14 gehärtet) |
| RR-3 | Ein Wegwerf-Fixture-Token erscheint in CI-Logs/Artefakten (committeter Dummy-Wert, `.gitleaks.toml`-Allowlist) |
| RR-4 | Environment-Protection + Tag-Restriction liegen in GitHub-Settings, nicht in Repo-Dateien — manuell zu verifizieren (siehe oben) |
| RR-5 | `quality.yml` läuft bei jedem Push und kostet Runner-Minuten; optionales `paths-ignore` für reine Doku-Commits ist ein offener Follow-up |
| RR-6 | `syft` wird per unversioniertem `curl\|sh` von `main` installiert (`build-and-deploy.yml`) — der SHA-Pin-Gate deckt `curl\|sh`-Installer nicht ab; Pinnen/Härten ist ein offener Follow-up |
