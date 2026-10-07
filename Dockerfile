FROM eclipse-temurin:25-jdk@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc AS build

RUN apt-get update \
    && apt-get install --yes --no-install-recommends unzip=6.0-29ubuntu1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
COPY src/ src/
RUN chmod +x ./mvnw && ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636

ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"
WORKDIR /app

RUN groupadd --gid 10001 ayuvahak \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin ayuvahak
COPY --from=build --chown=10001:10001 /workspace/target/ayuvahak-platform-0.1.0-SNAPSHOT.jar /app/ayuvahak.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/ayuvahak.jar"]
