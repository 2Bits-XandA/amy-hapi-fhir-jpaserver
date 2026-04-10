# AmyVet HAPI FHIR JPA Server

A customized [HAPI FHIR JPA Server](https://github.com/hapifhir/hapi-fhir-jpaserver-starter) for the **AmyVet** veterinary domain. This is an independent project derived from the upstream HAPI FHIR JPA Server Starter — not a GitHub fork.

## Features

- **FHIR R4** compliant (also supports DSTU2/DSTU3/R4B/R5)
- **Authentication** via Keycloak + JWT/JWKS validation
- **Role-based authorization** with patient-practitioner relationships (owner, vet, other) and time-limited access
- **Patient auto-linking** — creator is automatically linked as owner
- **Custom FHIR extensions** for link types and time periods
- **Custom CodeSystem** for breed types (`codesystem-breed.json`)
- **Distroless Docker image** for production (non-root, minimal attack surface)

## Quickstart with Docker

```bash
docker pull ghcr.io/2bits-xanda/amy-hapi-fhir-jpaserver:latest
docker run -p 8080:8080 ghcr.io/2bits-xanda/amy-hapi-fhir-jpaserver:latest
```

The server will be accessible at `http://localhost:8080/fhir` with the CapabilityStatement at `http://localhost:8080/fhir/metadata`.

## Building locally

### Prerequisites

- Java JDK 17+
- Apache Maven 3.8.3+

### Build & Run

```bash
# Run with embedded Tomcat (default)
mvn spring-boot:run

# Run with Jetty
mvn -Pjetty spring-boot:run

# Build WAR (skip tests)
mvn clean package -DskipTests

# Run all tests
mvn verify
```

### Docker Image bauen & publishen

Das Build-Script baut ein Multi-Arch Image (amd64 + arm64) und pusht es nach ghcr.io:

```bash
# Einmaliger Login (erfordert Personal Access Token classic mit write:packages)
# Token erstellen: https://github.com/settings/tokens
docker login ghcr.io -u <GITHUB_USERNAME>

# Bauen und publishen
./build-docker-image.sh           # als :latest
./build-docker-image.sh v1.0.0    # mit Version-Tag
```

Das Script erkennt automatisch ob Docker oder Podman verwendet wird und startet auf macOS bei Bedarf die Podman VM.

## Configuration

Configuration is done via `src/main/resources/application.yaml`. See [CLAUDE.md](CLAUDE.md) for a detailed overview of the project structure, customizations, and available feature flags.

### Docker Compose

A full development environment with PostgreSQL and Keycloak is available:

```bash
docker-compose up -d
```

For production deployment with Docker Swarm, see the `stack/` directory.

## Upstream Sync

This project is derived from [hapifhir/hapi-fhir-jpaserver-starter](https://github.com/hapifhir/hapi-fhir-jpaserver-starter). To pull in upstream changes:

```bash
# Add upstream remote (once)
git remote add upstream https://github.com/hapifhir/hapi-fhir-jpaserver-starter.git

# Fetch and merge upstream changes
git fetch upstream
git merge upstream/master
```

Resolve any merge conflicts, paying attention to files with AmyVet-specific customizations (security interceptors, authorization, custom extensions).

## Code Style

This project uses Spotless for formatting (tabs, indent size 3). Always run before committing:

```bash
mvn spotless:apply
```

## License

This project is licensed under the [Apache License 2.0](LICENSE).
