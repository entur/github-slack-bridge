FROM gcr.io/distroless/java25-debian13:nonroot@sha256:8ac5525549d14b8ddd34562f9fc8e6ba4840c995363a1507b17cadf8bb7ab57e

WORKDIR /srv
COPY build/libs/github-slack-bridge-all.jar app.jar

EXPOSE 8080
# Distroless has no shell, so exec form only. The base image sets ENTRYPOINT ["java", "-jar"],
# which would swallow the JVM flags, so override it with the full command.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=65.0", \
  "-XX:+UseG1GC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "app.jar"]
