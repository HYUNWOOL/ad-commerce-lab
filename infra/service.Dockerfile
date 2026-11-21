# 모든 Kotlin 서비스가 같이 쓰는 이미지. 먼저 ./gradlew bootJar로 jar를 만든다.
FROM eclipse-temurin:25-jre
ARG SERVICE
COPY services/${SERVICE}/build/libs/app.jar /app/app.jar
# 서비스가 여러 개 뜨므로 메모리 상한을 둔다(PC 32GB, Docker 16GB).
ENV JAVA_TOOL_OPTIONS="-Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
