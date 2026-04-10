# CLAUDE.md

## Project Overview

Customized HAPI FHIR JPA Server for the **AmyVet** veterinary domain. Based on `hapi-fhir-jpaserver-starter` v8.0.0 with security, authorization, and patient-practitioner relationship extensions.

- **FHIR Version:** R4 (default), also supports DSTU2/DSTU3/R4B/R5
- **Java:** 17
- **Build:** Maven 3.8.3+
- **Packaging:** WAR (Spring Boot repackaged)
- **Server Port:** 8080
- **Default DB:** H2 in-memory (PostgreSQL/MSSQL supported)

## Build & Run Commands

```bash
# Run with embedded Tomcat (default)
mvn spring-boot:run

# Run with Jetty
mvn -Pjetty spring-boot:run

# Build WAR
mvn clean package

# Build skipping tests
mvn clean package -DskipTests

# Docker build (distroless)
mvn clean package com.google.cloud.tools:jib-maven-plugin:dockerBuild -Dimage=distroless-hapi
```

## Testing

```bash
# All tests (unit + integration)
mvn verify

# Integration tests only (suffix: *IT.java)
mvn failsafe:integration-test
```

Tests use JUnit 5 + Spring Boot Test + Testcontainers. Integration tests are in `src/test/java` with `IT` suffix. Smoke tests (HTTP-based) are in `src/test/smoketest/`.

## Code Style

- **Formatter:** Spotless (configured via parent POM)
- **Indent:** Tabs, size 3 (see `.editorconfig`)
- **Check:** `mvn spotless:check`
- **Fix:** `mvn spotless:apply`

Always run `mvn spotless:apply` before committing. CI enforces `spotless:check` on PRs.

## Project Structure

Single Maven module. Key packages under `ca.uhn.fhir.jpa.starter`:

| Package | Purpose |
|---------|---------|
| `security/` | JWT/JWKS validation, role-based authorization |
| `security/jwks/` | Keycloak JWKS token validation |
| `common/` | FHIR server configuration (version-specific) |
| `cdshooks/` | CDS Hooks (conditional, disabled by default) |
| `cr/` | Clinical Reasoning (conditional, disabled by default) |
| `mdm/` | Master Data Management (conditional) |
| `ips/` | International Patient Summary (conditional) |
| `ig/` | Implementation Guide operations |
| `web/` | Web controllers, file serving |
| `util/` | Environment & Hibernate utilities |
| `annotations/` | Custom Spring conditional annotations |

## Key Customizations (vs vanilla HAPI starter)

- **Authentication:** Keycloak + JWT/JWKS (`JwksValidator`, `JwtValidator`)
- **Authorization:** `RoleBasedAuthorizationInterceptor` with patient-practitioner link types (owner, vet, other) and time-limited access
- **Patient-Compartment:** Owner has full CRUD on all resources. Vet write access limited to `ALLOWED_RESOURCES` (see `RoleBasedAuthorizationInterceptor.java:34`):
  Observation, MedicationRequest, MedicationAdministration, DocumentReference, Immunization, Condition, Encounter
- **Patient linking:** `PatientCreateInterceptor` auto-links creator as owner
- **Custom FHIR extensions:**
  - `http://amyvet.org/fhir/StructureDefinition/link-type`
  - `http://amyvet.org/fhir/StructureDefinition/period-extension`
- **Custom CodeSystem:** `http://amyvet.org/fhir/CodeSystem/breed` (see `src/main/resources/codesystem-breed.json`)

## Configuration

Primary config: `src/main/resources/application.yaml`

Feature flags (all in `hapi.fhir.*`):
- `cr.enabled` - Clinical Reasoning
- `cdshooks.enabled` - CDS Hooks
- `mdm_enabled` - Master Data Management
- `custom-bean-packages` / `custom-interceptor-classes` / `custom-provider-classes` - Extension points

## Docker

```bash
# Build distroless (production)
docker build --target=default -t hapi-fhir:latest .

# Build Tomcat (debugging)
docker build --target=tomcat -t hapi-fhir:tomcat .
```

Docker Compose includes PostgreSQL 15 + Keycloak. Keycloak realm: "amy", client: "amy-client".

## CI/CD (GitHub Actions)

- `maven.yml` - Build + test on push/PR
- `spotless-check.yml` - Format check on PRs
- `smoke-tests.yml` - HTTP smoke tests with Jetty
- `build-images.yaml` - Multi-arch Docker images on tag `image/v*`
- `chart-test.yaml` / `chart-release.yaml` - Helm chart CI

## Helm

Chart in `/charts/hapi-fhir-jpaserver/` (v0.19.0). Includes PostgreSQL sub-chart.
