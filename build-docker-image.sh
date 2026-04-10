#!/bin/sh
set -e

# Login (einmalig): docker login ghcr.io -u <GITHUB_USERNAME>
# Erfordert ein Personal Access Token (classic) mit Scope "write:packages"
# Token erstellen unter: https://github.com/settings/tokens

IMAGE="ghcr.io/2bits-xanda/amy-hapi-fhir-jpaserver"
TAG="${1:-latest}"

# Prüfe ob "docker" eigentlich podman ist (Symlink oder Binary-Name)
DOCKER_BIN=$(command -v docker 2>/dev/null || true)
DOCKER_REAL=$(readlink -f "${DOCKER_BIN}" 2>/dev/null || readlink "${DOCKER_BIN}" 2>/dev/null || echo "${DOCKER_BIN}")
if echo "${DOCKER_REAL}" | grep -qi podman; then
	echo "Podman erkannt (docker -> ${DOCKER_REAL})."

	# Auf macOS: sicherstellen, dass die Podman VM läuft
	if [ "$(uname)" = "Darwin" ]; then
		if ! podman machine inspect --format '{{.State}}' 2>/dev/null | grep -qi running; then
			echo "Podman VM ist nicht gestartet. Starte VM..."
			podman machine start
		fi
	fi
fi

PLATFORMS="linux/amd64,linux/arm64"

# Multi-Arch Build + Push
if echo "${DOCKER_REAL}" | grep -qi podman; then
	# Podman: manifest-basierter Multi-Arch Build
	# Vorheriges Image/Manifest mit gleichem Tag entfernen
	podman manifest rm "${IMAGE}:${TAG}" 2>/dev/null || true
	podman rmi "${IMAGE}:${TAG}" 2>/dev/null || true
	podman manifest create "${IMAGE}:${TAG}"

	for PLATFORM in $(echo "${PLATFORMS}" | tr ',' ' '); do
		echo "Baue fuer ${PLATFORM}..."
		podman build \
			--platform "${PLATFORM}" \
			--label "org.opencontainers.image.source=https://github.com/2Bits-XandA/amy-hapi-fhir-jpaserver" \
			--label "org.opencontainers.image.description=AmyVet HAPI FHIR JPA Server - customized for veterinary domain" \
			--label "org.opencontainers.image.licenses=Apache-2.0" \
			--manifest "${IMAGE}:${TAG}" .
	done

	echo "Publishing Multi-Arch Image ${IMAGE}:${TAG}"
	podman manifest push "${IMAGE}:${TAG}" "docker://${IMAGE}:${TAG}"
else
	# Docker: buildx-basierter Multi-Arch Build + Push
	docker buildx create --name multiarch --use 2>/dev/null || docker buildx use multiarch
	echo "Baue fuer ${PLATFORMS}..."
	docker buildx build \
		--platform "${PLATFORMS}" \
		--label "org.opencontainers.image.source=https://github.com/2Bits-XandA/amy-hapi-fhir-jpaserver" \
		--label "org.opencontainers.image.description=AmyVet HAPI FHIR JPA Server - customized for veterinary domain" \
		--label "org.opencontainers.image.licenses=Apache-2.0" \
		-t "${IMAGE}:${TAG}" \
		--push .
fi
