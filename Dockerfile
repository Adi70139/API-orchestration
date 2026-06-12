FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

RUN chmod +x mvnw

# Install Playwright dependencies
RUN apt-get update && apt-get install -y \
    libglib2.0-0 \
    libnss3 \
    libnspr4 \
    libdbus-1-3 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libdrm2 \
    libatspi2.0-0 \
    libx11-6 \
    libxcomposite1 \
    libxdamage1 \
    libxext6 \
    libxfixes3 \
    libxrandr2 \
    libgbm1 \
    libxcb1 \
    libxkbcommon0 \
    libpango-1.0-0 \
    libpangocairo-1.0-0 \
    libcairo2 \
    libcairo-gobject2 \
    libgdk-pixbuf-2.0-0 \
    libxcursor1 \
    libgtk-3-0 \
    libasound2t64 \
    && rm -rf /var/lib/apt/lists/*

# Build application
RUN ./mvnw clean package -DskipTests

# Download Playwright browsers during image build
RUN ./mvnw exec:java \
    -Dexec.mainClass=com.microsoft.playwright.CLI \
    -Dexec.args="install"

ENV UI_AUTOMATION_HEADLESS=true

EXPOSE 8060

ENTRYPOINT ["java","-jar","target/api-flow-engine-1.0.0.jar"]