# nexo-reportes-api

Microservicio de reportes PAI de vacunacion. Genera un XLSX a partir de las
citas de vacunacion aplicadas y expone una vista previa JSON.

## Contrato HTTP

El backend escucha en `8091` y publica mediante KrakenD:

- `GET /api/v1/reportes/vacunacion?mes=JULIO&anio=2026`: descarga el XLSX.
- `GET /api/v1/reportes/vacunacion/preview?mes=JULIO&anio=2026`: devuelve hasta
	`REPORTE_PREVIEW_MAX_ROWS` filas JSON.

El mes se filtra en SQL por la fecha de la cita. Solo los estados `APLICADA` y
`COMPLETADA` alimentan el reporte. Sin citas aplicadas, el resultado queda
vacio; no se incluyen citas pendientes o canceladas como fallback.

## Integracion

`frontend-citas` consume los endpoints mediante:

```text
/api/proxy/reportes/vacunacion
/api/proxy/reportes/vacunacion/preview
```

Las respuestas llevan `Cache-Control: no-store` porque contienen identificadores
y datos demograficos de pacientes. No agregar cache HTTP a estos endpoints.

El servicio consulta directamente `citas_db` (`cita` y `especialidad_tipo`).
Esto es una excepcion de arquitectura heredada y debe tratarse como una
integracion de lectura: no crear escrituras ni migraciones de citas en este
repositorio. A medio plazo, reemplazarla por una API o replica de lectura.

## Desarrollo y validacion

```bash
mvn test
```

La plantilla se busca en `REPORTE_TEMPLATE_PATH`; en Docker se copia a
`/app/templates/plantilla_vacunacion.xlsx`. El healthcheck es:

```text
GET http://localhost:8091/actuator/health
```

## Coolify

Construir desde `Dockerfile` o desplegar una imagen publicada. Configurar el
servicio en el puerto `8091`, PostgreSQL accesible con `DB_*`, y montar o
incluir la plantilla oficial. En el gateway configurar:

```text
REPORTES_SERVICE_HOST=http://nexo-reportes-api:8091
```

Gateway y reportes deben compartir la red de Coolify. Configurar tambien
`REPORTE_PREVIEW_MAX_ROWS` para limitar memoria y respuesta del preview.
