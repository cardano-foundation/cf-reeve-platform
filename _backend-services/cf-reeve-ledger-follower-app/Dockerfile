FROM eclipse-temurin:21 AS build
WORKDIR /app
COPY . /app
RUN ./gradlew clean build

FROM eclipse-temurin:21-jre AS follower-app
COPY --from=build /app/build/libs/*-all.jar /app.jar
ENTRYPOINT ["java", "--enable-preview", "-jar", "/app.jar"]
