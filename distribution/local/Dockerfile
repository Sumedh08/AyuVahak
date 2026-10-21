FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636

ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"
ENV SPRING_WEB_RESOURCES_STATIC_LOCATIONS="file:/tmp/,file:/app/web/"
WORKDIR /app

RUN groupadd --gid 10001 ayuvahak \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin ayuvahak
COPY --chown=10001:10001 app.jar /app/ayuvahak.jar
COPY --chown=10001:10001 web/ /app/web/

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/ayuvahak.jar"]
