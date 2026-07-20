FROM eclipse-temurin:17-jdk-alpine AS build

WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre-alpine

RUN addgroup -S devvault && adduser -S devvault -G devvault
WORKDIR /app
COPY --from=build /workspace/build/libs/devvault-*.jar app.jar
RUN mkdir -p /data/uploads && chown -R devvault:devvault /app /data/uploads

USER devvault
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
