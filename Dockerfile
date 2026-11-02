FROM gcr.io/distroless/java25-debian13:nonroot@sha256:a90a5fadd78dc37b643da72dca2f744ef67eba625eca7227a5b375b548e6b1c2

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
