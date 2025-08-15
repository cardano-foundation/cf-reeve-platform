FROM openjdk:21-jdk-slim AS build
WORKDIR /app
COPY . /app
RUN ./gradlew clean build

FROM openjdk:21-jdk-slim AS follower-app
COPY --from=build /app/build/libs/*-all.jar /app.jar
ENTRYPOINT ["java", "--enable-preview", "-jar", "/app.jar"]
