# syntax=docker/dockerfile:1
# Backend image: build with the Maven Wrapper, run on a JRE as a non-root user.
# Tests are not run here; CI runs ./mvnw verify before images are built.

FROM eclipse-temurin:24.0.2_12-jdk AS build
# unzip lets the wrapper use the .zip distribution whose SHA-256 is pinned in
# .mvn/wrapper/maven-wrapper.properties (without it, mvnw falls back to .tar.gz and the check fails).
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/wallet-*.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:24.0.2_12-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
