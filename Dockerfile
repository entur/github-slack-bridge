FROM bellsoft/liberica-runtime-container:jre-23-slim-musl

WORKDIR /srv
COPY build/libs/github-slack-bridge-all.jar app.jar
RUN chown -R 1000:1000 /srv
USER 1000:1000

CMD ["sh", "-c", "exec java -jar app.jar"]
