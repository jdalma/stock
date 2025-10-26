# Build stage
FROM gradle:8.10-jdk21-alpine AS build
WORKDIR /app

# 의존성 캐싱을 위해 gradle 파일들을 먼저 복사
COPY gradle gradle
COPY gradlew .
COPY build.gradle.kts .
COPY settings.gradle.kts .

# 의존성만 먼저 다운로드 (캐시 활용)
RUN ./gradlew dependencies --no-daemon

# 소스 코드 복사 및 빌드
COPY src src
RUN ./gradlew bootJar --no-daemon --build-cache

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 비root 사용자 생성 (보안)
RUN addgroup -g 1000 app && adduser -u 1000 -G app -s /bin/sh -D app

# JAR 파일 복사
COPY --from=build --chown=app:app /app/build/libs/*.jar app.jar

# 애플리케이션 사용자로 전환
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
