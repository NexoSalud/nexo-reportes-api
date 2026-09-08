package com.nexo.reportes.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexo.reportes.domain.CitaReporteRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Lee las citas directamente del esquema compartido de citas (citas_db).
 * Aunque el reporte es otro microservicio, comparte el mismo PostgreSQL,
 * por lo que consulta la tabla cita + catalogo de especialidades.
 */
@Repository
public class CitaReporteRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CitaReporteRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Trae las citas de vacunacion (todas, o filtradas por anio de la fecha).
     * @param anio si no es null, filtra por EXTRACT(YEAR FROM fecha) = anio
     */
    public List<CitaReporteRow> listarCitasVacunacion(Integer anio) {
        String sql = """
            SELECT c.id,
                   c.paciente_id,
                   e.codigo  AS especialidad_codigo,
                   e.nombre  AS especialidad_nombre,
                   c.fecha,
                   c.estado,
                   c.perfil_paciente_snapshot
            FROM cita c
            LEFT JOIN especialidad_tipo e ON e.id = c.especialidad_tipo_id
            WHERE e.codigo = 'VACUNACION'
              AND (? IS NULL OR EXTRACT(YEAR FROM c.fecha) = ?)
            ORDER BY c.fecha
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String perfilJson = rs.getString("perfil_paciente_snapshot");
            JsonNode p = leerPerfil(perfilJson);
            return new CitaReporteRow(
                    rs.getString("id"),
                    rs.getString("paciente_id"),
                    rs.getString("especialidad_codigo"),
                    rs.getString("especialidad_nombre"),
                    rs.getTimestamp("fecha").toLocalDateTime(),
                    rs.getString("estado"),
                    p != null && p.hasNonNull("edad") ? p.get("edad").asInt() : null,
                    p != null && p.hasNonNull("genero") ? p.get("genero").asText() : null,
                    p != null && p.hasNonNull("etnia") ? p.get("etnia").asText() : null,
                    p != null && p.hasNonNull("ubicacion") ? p.get("ubicacion").asText() : null
            );
        }, anio, anio);
    }

    private JsonNode leerPerfil(String perfilJson) {
        if (perfilJson == null || perfilJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(perfilJson);
        } catch (Exception e) {
            return null;
        }
    }
}
