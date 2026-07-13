# 단일 배포용 Dockerfile — 프론트엔드(Vite)를 빌드해 백엔드(Spring Boot) 정적 리소스로
# 넣고, 하나의 jar/컨테이너가 정적 화면과 /api/* 를 모두 서빙한다.

# 1) 프론트엔드 빌드
FROM node:20-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2) 백엔드 빌드 (프론트 산출물을 static 으로 포함)
FROM gradle:8-jdk17 AS backend
WORKDIR /app
COPY backend/gradlew ./
COPY backend/gradle gradle
COPY backend/build.gradle backend/settings.gradle ./
RUN ./gradlew dependencies --no-daemon || true

COPY backend/src src
# 프론트 빌드 결과를 Spring 이 서빙하는 정적 경로로 복사
COPY --from=frontend /app/frontend/dist src/main/resources/static
RUN ./gradlew bootJar --no-daemon

# 3) 런타임
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=backend /app/build/libs/*.jar app.jar

# OPENROUTER_API_KEY 를 주입하면 실제 AI 초안 생성, 미설정 시 프론트가 mock 폴백.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
