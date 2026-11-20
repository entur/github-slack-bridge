FROM gcr.io/distroless/java25-debian13:nonroot@sha256:573ef04c0f84aaf4b34e0cb6eeef91c25a83290334586c4c44ef41ecae13a25e

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
