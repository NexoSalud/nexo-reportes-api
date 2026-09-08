package com.nexo.reportes.domain;

import java.time.LocalDateTime;

/**
 * Proyeccion de una fila de cita (vacunacion) leida desde el esquema compartido
 * de citas, enriquecida con el perfil del paciente (snapshot JSON) y el nombre
 * de la especialidad/vacuna.
 */
public class CitaReporteRow {

    private final String id;
    private final String pacienteId;
    private final String especialidadCodigo;   // ej. VACUNACION
    private final String especialidadNombre;   // ej. Vacunación
    private final LocalDateTime fecha;
    private final String estado;
    private final Integer edad;
    private final String genero;
    private final String etnia;
    private final String ubicacion;            // departamento/municipio libre

    public CitaReporteRow(String id, String pacienteId, String especialidadCodigo,
                          String especialidadNombre, LocalDateTime fecha, String estado,
                          Integer edad, String genero, String etnia, String ubicacion) {
        this.id = id;
        this.pacienteId = pacienteId;
        this.especialidadCodigo = especialidadCodigo;
        this.especialidadNombre = especialidadNombre;
        this.fecha = fecha;
        this.estado = estado;
        this.edad = edad;
        this.genero = genero;
        this.etnia = etnia;
        this.ubicacion = ubicacion;
    }

    public boolean esVacunacion() {
        return "VACUNACION".equalsIgnoreCase(especialidadCodigo);
    }

    public boolean estaAplicada() {
        return "APLICADA".equalsIgnoreCase(estado) || "COMPLETADA".equalsIgnoreCase(estado);
    }

    public String getId() { return id; }
    public String getPacienteId() { return pacienteId; }
    public String getEspecialidadCodigo() { return especialidadCodigo; }
    public String getEspecialidadNombre() { return especialidadNombre; }
    public LocalDateTime getFecha() { return fecha; }
    public String getEstado() { return estado; }
    public Integer getEdad() { return edad; }
    public String getGenero() { return genero; }
    public String getEtnia() { return etnia; }
    public String getUbicacion() { return ubicacion; }
}
