FROM eclipse-temurin:21-jdk-jammy

WORKDIR /app

COPY . .

RUN chmod +x mvnw



# Build application
RUN ./mvnw clean package -DskipTests

# Download Playwright browsers and their OS dependencies during image build
# Need to run apt-get update first so the package lists are populated
RUN apt-get update && NODE_TLS_REJECT_UNAUTHORIZED=0 ./mvnw exec:java \
    -Dexec.mainClass=com.microsoft.playwright.CLI \
    -Dexec.args="install --with-deps"

ENV UI_AUTOMATION_HEADLESS=true

EXPOSE 8060

ENTRYPOINT ["java","-jar","target/api-flow-engine-1.0.0.jar"]