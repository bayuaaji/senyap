# Image runtime untuk api (juga dipakai worker) dan realtime. Jar dibangun dulu di CI:
#   cd backend && ./mvnw -B -DskipTests package
#   docker build -f backend/Dockerfile --build-arg MODULE=api      -t senyap/api:<sha>      backend
#   docker build -f backend/Dockerfile --build-arg MODULE=realtime -t senyap/realtime:<sha> backend
FROM eclipse-temurin:21-jre-alpine
ARG MODULE
RUN addgroup -S senyap && adduser -S -G senyap senyap
WORKDIR /app
COPY ${MODULE}/target/${MODULE}-*.jar app.jar
USER senyap
# Memori mengikuti limit container; container-aware sejak JDK 10.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
