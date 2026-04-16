# Stage 1: Build the project
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && \
    ./gradlew :eureka-server:war --no-daemon -x test

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S eureka && adduser -S eureka -G eureka

WORKDIR /app

# Download Jetty Runner (matching project's Jetty 10.0.24) for WAR deployment
RUN wget -q -O /app/jetty-runner.jar \
    "https://repo1.maven.org/maven2/org/eclipse/jetty/jetty-runner/10.0.24/jetty-runner-10.0.24.jar"

COPY --from=build /workspace/eureka-server/build/libs/eureka-server-*.war /app/eureka-server.war

RUN chown -R eureka:eureka /app
USER eureka

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/jetty-runner.jar", "--port", "8080", "/app/eureka-server.war"]
