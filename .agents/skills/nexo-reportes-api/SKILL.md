---
name: nexo-reportes-api
description: >-
  Mantener, depurar, extender y desplegar el modulo de reportes PAI de Nexo 2.0
  (nexo-reportes-api + frontend-citas + KrakenD). Usar cuando una peticion
  mencione reportes, vacunacion PAI, XLSX, preview, plantilla_vacunacion,
  Cache-Control, citas aplicadas o Coolify para este modulo.
---

# Nexo 2.0 - Skill del modulo de reportes

Esta skill concentra el contexto especifico de `nexo-reportes-api` para evitar
asumir que es un CRUD comun. El modulo genera archivos XLSX pesados a partir
de una lectura heredada de `citas_db` y se consume desde `frontend-citas`.

## Alcance

- Backend: `repos/nexo-reportes-api`.
- Frontend consumidor: `repos/Frontend/apps/frontend-citas`.
- Gateway: `repos/gateway/config/templates/reportes.tmpl`.
- Puerto backend: `8091`.
- Ruta frontend: `/api/proxy/reportes/...`.

Leer tambien `.agents/skills/nexo-frontend/SKILL.md` y
`.agents/skills/nexo-infrastructure/SKILL.md` cuando el cambio atraviese
proxy, gateway, Keycloak o Coolify.

## Contrato real

| Metodo | Ruta | Resultado |
|---|---|---|
| GET | `/api/v1/reportes/vacunacion?mes=JULIO&anio=2026` | XLSX PAI. |
| GET | `/api/v1/reportes/vacunacion/preview?mes=JULIO&anio=2026` | JSON limitado para preview. |

El navegador nunca llama al gateway directamente. El flujo es:

```text
frontend-citas -> /api/proxy/reportes -> KrakenD -> nexo-reportes-api:8091
```

La plantilla oficial se carga desde `REPORTE_TEMPLATE_PATH` o desde
`/app/templates/plantilla_vacunacion.xlsx` dentro de Docker.

## Reglas funcionales

- `mes` debe filtrar por `EXTRACT(MONTH FROM c.fecha)`, no solo rotular el XLSX.
- `anio` debe filtrar por `EXTRACT(YEAR FROM c.fecha)`.
- Solo estados `APLICADA` y `COMPLETADA` alimentan el reporte.
- Si no hay citas aplicadas, el resultado debe quedar vacio; nunca incluir
  canceladas, pendientes o creadas como fallback.
- El preview debe tener limite configurable mediante
  `REPORTE_PREVIEW_MAX_ROWS` para evitar respuestas y tablas ilimitadas.
- Validar el nombre del mes antes de usarlo en SQL o headers.
- La plantilla puede tener un alto consumo de memoria; no materializar buffers
  duplicados si el flujo puede escribirse por streaming.

## Dependencia de datos heredada

El servicio consulta directamente `citas_db`, tablas `cita` y
`especialidad_tipo`. Esta es una excepcion heredada al aislamiento de bases:

- Solo lectura desde reportes.
- No crear FK ni escrituras hacia tablas de citas.
- No agregar migraciones de dominio de citas aquí.
- Documentar cualquier cambio de columnas compartidas.
- Evolucion recomendada: API de lectura o replica dedicada.

## Cache y privacidad

Los reportes contienen identificadores y datos demograficos de pacientes.
Por eso, XLSX y preview deben ser `no-store`:

- Backend: `Cache-Control: no-store, max-age=0` y `Pragma: no-cache`.
- Proxy Next.js: conservar esos headers en binario y JSON.
- Gateway: definir `cache_ttl: 0s` en cada endpoint de reportes.
- No agregar cache de respuesta salvo que la clave incluya identidad y exista
  una justificacion de privacidad revisada.

La cache apropiada para este modulo es cachear la plantilla o reducir llamadas,
no cachear respuestas de pacientes. Evitar cargar dos veces el XLSX completo.

## Coolify y Docker

Verificar siempre las cuatro superficies del puerto `8091`:

1. `application.yml` (`SERVER_PORT`).
2. `Dockerfile` (`EXPOSE`, `ENV`, healthcheck).
3. Gateway (`REPORTES_SERVICE_HOST`).
4. Configuracion de red/servicio en Coolify.

El contenedor debe exponer:

```text
GET /actuator/health
```

Variables minimas:

```text
SERVER_PORT=8091
DB_HOST=<host de citas_db>
DB_PORT=5432
DB_NAME=citas_db
DB_USER=<usuario>
DB_PASSWORD=<secreto>
REPORTE_TEMPLATE_PATH=/app/templates/plantilla_vacunacion.xlsx
REPORTE_DEPARTAMENTO=CAUCA
REPORTE_PREVIEW_MAX_ROWS=1000
```

Gateway y reportes deben compartir red y resolver `nexo-reportes-api`.

## Flujo para cada peticion

1. Leer el controlador, repositorio, servicio y consumidor frontend implicados.
2. Formular una hipotesis local y una prueba barata que pueda refutarla.
3. Revisar el diff existente; no sobrescribir cambios del usuario.
4. Aplicar el cambio minimo preservando el streaming XLSX y el contrato.
5. Validar inmediatamente:

```bash
cd /home/jhordy/nexo/v3
mvn -q -f repos/nexo-reportes-api/pom.xml test
cd repos/Frontend
pnpm --filter frontend-citas type-check
pnpm --filter frontend-citas build
cd /home/jhordy/nexo/v3
cd repos/nexo-reportes-api
docker build -t nexo-reportes-api:check .
```

6. Validar gateway:

```bash
docker compose -f repos/gateway/docker-compose.yml config -q
```

7. Con servicios levantados, comprobar que una ruta protegida sin JWT devuelve
   `401`, y que una petición autenticada conserva `mes` y `anio`.
8. Comparar dos meses del mismo año: sus conjuntos de citas deben diferir si
   existen datos en meses distintos.

## Diagnostico rapido

| Sintoma | Causa a revisar primero |
|---|---|
| XLSX de JULIO contiene todo el año | El repositorio no recibió `mes` o el gateway perdió query strings. |
| Preview demasiado grande | `REPORTE_PREVIEW_MAX_ROWS` y filtro mensual. |
| Reporte incluye canceladas | Fallback en `ReporteVacunacionService.aplicadas`. |
| `Failed to fetch` al descargar | Heap/streaming, timeout de 120s y plantilla. |
| 502 | Puerto 8091, `REPORTES_SERVICE_HOST`, red Coolify o DB. |
| Datos antiguos | Cache global del gateway; reportes siempre debe usar `no-store`. |
| Healthcheck falla | Actuator, curl en imagen y `/actuator/health`. |
| SQL no inicia | Acceso a `citas_db`, esquema compartido o parametros JDBC. |

## Refinamiento continuo

Después de cada petición nueva sobre reportes:

1. Registrar síntoma y causa confirmada sin secretos ni datos personales.
2. Convertir la solución en regla, prueba o diagnóstico reutilizable.
3. Actualizar este archivo y el README del módulo.
4. Añadir una entrada al historial con fecha.
5. Validar frontmatter, comandos y contrato afectado.

### Historial

- `2026-09-11`: versión inicial creada tras corregir el filtro mensual, limitar
  preview, eliminar fallback de estados, desactivar cache de datos sensibles y
  preparar healthcheck/variables para Coolify.

No guardar tokens, JWT, contraseñas, identificadores reales de pacientes ni
contenido de producción en esta skill.
