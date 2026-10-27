FROM bellsoft/liberica-runtime-container:jre-27-slim-musl@sha256:12b1c2c044fe633ad7c6f1cc8ecfdaf5a5d70bc1248d484c460dad8e0a21c63b

WORKDIR /srv
COPY build/libs/github-slack-bridge-all.jar app.jar
RUN chown -R 1000:1000 /srv
USER 1000:1000

CMD ["sh", "-c", "exec java -jar app.jar"]
