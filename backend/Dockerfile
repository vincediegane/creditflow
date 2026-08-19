# ---------- Etape 1 : compilation ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Plafonne la memoire de compilation : sans cela, la construction echoue sur
# les machines ou Docker dispose de peu de RAM.
ENV MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m"

# Les dependances sont mises en cache tant que le pom ne change pas
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- Etape 2 : image d'execution ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# tzdata est indispensable : sans lui la JVM reste en UTC et les "paiements du
# jour" comme le calcul des retards basculeraient a la mauvaise heure locale.
RUN apk add --no-cache tzdata \
    && addgroup -S creditflow && adduser -S creditflow -G creditflow \
    && mkdir -p /app/data/uploads && chown -R creditflow:creditflow /app

ENV TZ=Africa/Dakar

COPY --from=build /build/target/creditflow-backend.jar /app/app.jar

USER creditflow
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=${TZ}"

# Adresse explicite plutot que "localhost" : insensible a l'ordre de resolution IPv4/IPv6
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
    CMD wget -q --spider http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
