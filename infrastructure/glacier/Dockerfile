# Base image pinned by digest (supply-chain hardening — see dependency-vetting skill).
# The :23-jre tag is kept for readability; the @sha256 digest is what Docker resolves.
# Dependabot (docker ecosystem) keeps the digest current via reviewable PRs.
FROM docker.io/eclipse-temurin:23-jre@sha256:4972459272d4050ab14700677ac7a7511194f1d9ed333b95e886c3c8c80dedae as builder
ARG JAR_FILE=glacier.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=layertools -jar application.jar extract

FROM docker.io/eclipse-temurin:23-jre@sha256:4972459272d4050ab14700677ac7a7511194f1d9ed333b95e886c3c8c80dedae
EXPOSE 8080

RUN groupadd spring && useradd -g spring spring
USER spring:spring
WORKDIR application

COPY --from=builder dependencies/ ./
COPY --from=builder snapshot-dependencies/ ./
COPY --from=builder spring-boot-loader/ ./
COPY --from=builder application/ ./
CMD ["java", "org.springframework.boot.loader.launch.JarLauncher"]
