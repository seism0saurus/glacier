FROM docker.io/eclipse-temurin:21-jre as builder
ARG JAR_FILE=glacier.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=layertools -jar application.jar extract


FROM docker.io/eclipse-temurin:21-jre

RUN groupadd spring && useradd -g spring spring
USER spring:spring
WORKDIR application

COPY --from=builder dependencies/ ./
COPY --from=builder snapshot-dependencies/ ./
COPY --from=builder spring-boot-loader/ ./
COPY --from=builder application/ ./
ENTRYPOINT ["java", "org.springframework.boot.loader.JarLauncher"]
