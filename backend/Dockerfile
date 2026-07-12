# 1. Build Stage
FROM gradle:8-jdk17 AS builder
WORKDIR /app

# Gradle 캐시 활용을 위해 빌드 파일을 먼저 복사하고 의존성을 받는다.
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon || true

# 전체 소스를 복사하고 실행 가능한 jar를 빌드한다.
COPY src src
RUN ./gradlew bootJar --no-daemon

# 2. Run Stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

# OPENROUTER_API_KEY 환경변수를 주입해야 실제 AI 초안이 생성된다(미설정 시 프론트가 mock 폴백).
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
