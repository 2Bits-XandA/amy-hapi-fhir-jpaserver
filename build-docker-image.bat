@echo off
REM Login (einmalig): docker login ghcr.io -u <GITHUB_USERNAME>
REM Erfordert ein Personal Access Token (classic) mit Scope "write:packages"
REM Token erstellen unter: https://github.com/settings/tokens

set IMAGE=ghcr.io/2bits-xanda/amy-hapi-fhir-jpaserver
set TAG=%1
if "%TAG%"=="" set TAG=latest

docker build ^
	--label "org.opencontainers.image.source=https://github.com/2Bits-XandA/amy-hapi-fhir-jpaserver" ^
	--label "org.opencontainers.image.description=AmyVet HAPI FHIR JPA Server - customized for veterinary domain" ^
	--label "org.opencontainers.image.licenses=Apache-2.0" ^
	-t %IMAGE%:%TAG% .
docker push %IMAGE%:%TAG%
