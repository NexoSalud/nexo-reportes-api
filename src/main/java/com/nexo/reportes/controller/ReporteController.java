package com.nexo.reportes.controller;

import com.nexo.reportes.domain.CitaReporteRow;
import com.nexo.reportes.repository.CitaReporteRepository;
import com.nexo.reportes.service.ReporteVacunacionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

@RestController
@RequestMapping("/api/v1/reportes")
public class ReporteController {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ReporteVacunacionService reporteService;
    private final CitaReporteRepository citaRepository;

    @Value("${reporte.preview-max-rows:1000}")
    private int previewMaxRows;

    public ReporteController(ReporteVacunacionService reporteService,
                             CitaReporteRepository citaRepository) {
        this.reporteService = reporteService;
        this.citaRepository = citaRepository;
    }

    /**
     * Genera el reporte mensual de vacunacion (xlsx) a partir de las citas.
     * GET /api/v1/reportes/vacunacion?mes=JULIO&anio=2026
     *
     * Se vuela el libro directamente al OutputStream de la respuesta (streaming)
     * para no materializar el byte[] en memoria: la plantilla ya carga la hoja
     * "RD ESQUEMA" (~112MB descomprimidos) y evitar el buffer doble reduce el
     * pico de heap que disparaba OutOfMemoryError intermitentes.
     */
    @GetMapping(value = "/vacunacion", produces = XLSX_MIME)
    public void reporteVacunacion(
            @RequestParam(required = false) String mes,
            @RequestParam(required = false) Integer anio,
            HttpServletResponse response) throws IOException {

        Integer mesNumero = mesNumero(mes);
        List<CitaReporteRow> citas = citaRepository.listarCitasVacunacion(anio, mesNumero);
        Integer year = anio != null ? anio : java.time.LocalDate.now().getYear();
        String nombre = "Reporte_Vacunacion_" + (mes != null ? mes : "MENSUAL")
                + "_" + year + ".xlsx";

        response.setContentType(XLSX_MIME);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + nombre + "\"");

        try (OutputStream out = response.getOutputStream()) {
            reporteService.escribir(citas, year, mes, out);
        }
    }

    /** Vista previa (JSON) de las citas que alimentaran el reporte. */
    @GetMapping(value = "/vacunacion/preview", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<CitaReporteRow>> preview(
            @RequestParam(required = false) Integer anio,
            @RequestParam(required = false) String mes) {
        Integer mesNumero = mesNumero(mes);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(citaRepository.listarCitasVacunacion(anio, mesNumero, previewMaxRows));
    }

    private Integer mesNumero(String mes) {
        if (mes == null || mes.isBlank()) {
            return null;
        }
        return switch (mes.trim().toUpperCase()) {
            case "ENERO" -> 1; case "FEBRERO" -> 2; case "MARZO" -> 3;
            case "ABRIL" -> 4; case "MAYO" -> 5; case "JUNIO" -> 6;
            case "JULIO" -> 7; case "AGOSTO" -> 8; case "SEPTIEMBRE" -> 9;
            case "OCTUBRE" -> 10; case "NOVIEMBRE" -> 11; case "DICIEMBRE" -> 12;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Mes invalido: " + mes);
        };
    }
}
