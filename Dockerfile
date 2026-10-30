# Stage 1: build the jar with the JDK and Maven; none of this reaches the final image
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Dependencies first, in their own layer: rebuilt only when pom.xml changes, not on every code change
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline

# Then the code. Tests run in CI before the image is built: Testcontainers would need Docker inside this build
COPY src src
RUN ./mvnw -q package -DskipTests

# Split the fat jar into Spring Boot layers, ordered from rarely to often changed
RUN cp target/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Stage 2: only the JRE and the application
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# A system user without a login shell: the app does not run as root
RUN groupadd --system app && useradd --system --gid app --no-create-home app

# One COPY per layer: when only our code changes, the first three come from the cache
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

USER app
EXPOSE 8080

# Size the heap from the container's memory limit, not the host's
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# The OpenAI key and the database settings come as environment variables at run time, never at build time
ENTRYPOINT ["java", "-jar", "application.jar"]
