FROM bellsoft/liberica-runtime-container:jre-25-slim-musl@sha256:38c7dde7cda9b46bf512688e63a6c6ecf9f38ba05338c1df18994ad3f927c819

WORKDIR /srv
COPY build/libs/github-slack-bridge-all.jar app.jar
RUN chown -R 1000:1000 /srv
USER 1000:1000

CMD ["sh", "-c", "exec java -jar app.jar"]
