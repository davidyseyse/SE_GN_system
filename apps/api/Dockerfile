FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY lib ./lib
COPY src/main/java ./src/main/java

RUN mkdir -p /app/build/classes \
  && javac -encoding UTF-8 -d /app/build/classes $(find /app/src/main/java -name "*.java")

EXPOSE 8080
EXPOSE 8081

CMD ["java", "-cp", "/app/build/classes:/app/lib/postgresql-42.7.4.jar", "com.classroom.api.Main"]
