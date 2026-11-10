# ---------- frontend ----------
FROM node:22-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------- backend ----------
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /src/backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /src/frontend/dist ./src/main/resources/static
RUN mvn -B -q package -DskipTests

# ---------- runtime ----------
FROM eclipse-temurin:21-jre
ARG SCRCPY_VERSION=3.1
ARG SCRCPY_SHA256=958f0944a62f23b1f33a16e9eb14844c1a04b882ca175a738c16d23cb22b86c0
RUN apt-get update \
 && apt-get install -y --no-install-recommends adb curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL -o /opt/scrcpy-server "https://github.com/Genymobile/scrcpy/releases/download/v${SCRCPY_VERSION}/scrcpy-server-v${SCRCPY_VERSION}" \
 && echo "${SCRCPY_SHA256}  /opt/scrcpy-server" | sha256sum -c -
COPY --from=backend /src/backend/target/android-emul-backend-*.jar /app/app.jar
ENV SCRCPY_SERVER_PATH=/opt/scrcpy-server \
    SCRCPY_VERSION=${SCRCPY_VERSION}
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
