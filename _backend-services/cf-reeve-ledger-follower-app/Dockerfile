FROM dhi.io/eclipse-temurin:21-jdk-debian-dev AS build
WORKDIR /app
COPY . /app
RUN ./gradlew clean build --console=plain --no-daemon --parallel --build-cache

FROM dhi.io/eclipse-temurin:21-debian AS follower-app
COPY --from=build /app/build/libs/*-all.jar /app.jar
COPY --from=build /app/build/libs/healthcheck.jar /healthcheck.jar
ENTRYPOINT ["java", "--enable-preview", "-jar", "/app.jar"]
