FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY src/main/java ./src/main/java

RUN mkdir -p /app/build/classes \
  && javac -encoding UTF-8 -d /app/build/classes $(find /app/src/main/java -name "*.java")

EXPOSE 8080
EXPOSE 8081

CMD ["java", "-cp", "/app/build/classes", "com.classroom.api.Main"]
