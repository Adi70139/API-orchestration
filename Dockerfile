FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

RUN chmod +x mvnw

RUN ./mvnw clean package -DskipTests

EXPOSE 8060

ENTRYPOINT ["java","-jar","target/api-flow-engine-1.0.0.jar"]