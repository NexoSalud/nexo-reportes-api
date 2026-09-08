# Build Stage
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline -q || true
COPY src ./src
RUN mvn -B -DskipTests clean package

# Runtime Stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copia la plantilla oficial de vacunacion a una ruta por defecto.
# Se puede sobreescribir con la env REPORTE_TEMPLATE_PATH.
COPY templates/plantilla_vacunacion.xlsx /app/templates/plantilla_vacunacion.xlsx

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8086

ENV SERVER_PORT=8086
ENV REPORTE_TEMPLATE_PATH=/app/templates/plantilla_vacunacion.xlsx

CMD ["java", "-Xmx6g", "-jar", "app.jar"]
