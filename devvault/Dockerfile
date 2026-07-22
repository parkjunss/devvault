FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system devvault \
    && useradd --system --gid devvault devvault
WORKDIR /app
COPY --from=build /workspace/build/libs/devvault-*.jar app.jar
RUN mkdir -p /data/uploads && chown -R devvault:devvault /app /data/uploads

USER devvault
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
