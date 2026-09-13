FROM eclipse-temurin:21-jdk AS build

WORKDIR /build

# Dependencies resolve in their own layer, so a source-only change does not re-download them.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
# Package the application without running tests
RUN ./mvnw -B -ntp -DskipTests package


FROM eclipse-temurin:21-jre AS runtime

RUN useradd --system --create-home --uid 10001 spring

WORKDIR /app
COPY --from=build /build/target/secure-ticketing-api-*.jar app.jar

USER spring
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
