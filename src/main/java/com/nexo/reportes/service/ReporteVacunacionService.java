package com.nexo.reportes.service;

import com.nexo.reportes.domain.CitaReporteRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.IOUtils;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Genera el reporte mensual de vacunacion (plantilla PAI de Cauca).
 *
 * Copia la plantilla de una ruta configurable (REPORTE_TEMPLATE_PATH) y sobre
 * la copia llena:
 *   - "HOJA DE LISTAS": una fila por dosis de vacuna aplicada (cita de vacunacion).
 *   - "PLANTILLA MENSUAL": metadatos (año/mes) y totales por municipio.
 *
 * Los campos ministeriales que una cita aun no captura (municipio DANE, tipo de
 * identificacion, regimen, aseguradora, dosis) se rellenan con valores de
 * demostracion (MapeoVacuna) para validar el mecanismo de punta a punta.
 */
@Service
public class ReporteVacunacionService {

    private static final Logger log = LoggerFactory.getLogger(ReporteVacunacionService.class);

    private static final String HOJA_LISTAS = "HOJA DE LISTAS";
    private static final String HOJA_MENSUAL = "PLANTILLA MENSUAL";

    // Indice de columnas (1-based) de HOJA DE LISTAS segun la plantilla
    private static final int COL_MUNICIPIO = 1;
    private static final int COL_TIPO_ID   = 2;
    private static final int COL_REGIMEN   = 3;
    private static final int COL_ASEG      = 4;
    private static final int COL_ETNIA     = 5;
    private static final int COL_DESPL     = 6;
    private static final int COL_DISCAP    = 7;
    private static final int COL_SEXO      = 8;
    private static final int COL_DEPTO     = 9;
    private static final int COL_AREA      = 10;
    private static final int COL_BCG       = 11;
    private static final int COL_HB        = 12;
    private static final int COL_NOVAC     = 13;
    private static final int COL_MES       = 14;
    private static final int COL_USUARIA   = 15;
    private static final int COL_HB2       = 16;
    private static final int COL_INMUNO    = 17;
    private static final int COL_POLIO_VPO = 18;
    private static final int COL_POLIO_VPI = 19;
    private static final int COL_PENTA     = 20;
    private static final int COL_DPT       = 21;
    private static final int COL_ROTA      = 22;
    private static final int COL_NEUMO     = 23;
    private static final int COL_TRIPLE    = 24;
    private static final int COL_HA        = 25;
    private static final int COL_FA        = 26;
    private static final int COL_TOXOIDE   = 27;
    private static final int COL_JERINGAS  = 29;

    @Value("${reporte.template-path:}")
    private String templatePath;

    @Value("${reporte.departamento:CAUCA}")
    private String departamento;

    public byte[] generar(List<CitaReporteRow> citas, Integer anio, String mesRaw) throws IOException {
        // La plantilla oficial incluye una hoja oculta "RD ESQUEMA" de ~112MB
        // descomprimidos (alto ratio de compresion). Se elevan los limites de POI
        // (array + ratio de zip) para poder cargar la plantilla completa tal cual.
        IOUtils.setByteArrayMaxOverride(1_000_000_000);
        ZipSecureFile.setMinInflateRatio(0.0001D);
        Map<String, Object> resumen = new HashMap<>();
        List<CitaReporteRow> aplicadas = aplicadas(citas);

        try (Workbook wb = abrirPlantilla()) {
            llenarHojaDeListas(wb, aplicadas, anio, mesRaw, resumen);
            llenarPlantillaMensual(wb, aplicadas, anio, mesRaw, resumen);
            return aBytes(wb);
        }
    }

    /**
     * Variante que vuelca el libro directamente al stream de salida de la
     * respuesta HTTP, sin materializar el byte[] completo en memoria.
     *
     * La plantilla (con la hoja "RD ESQUEMA" de ~112MB descomprimidos) ya exige
     * un heap alto; evitar el buffer de salida duplicado reduce el pico de
     * memoria durante {@code Workbook.write} y evita OutOfMemoryError
     * intermitentes (que un navegador percibe como "Failed to fetch" al cortarse
     * la conexion).
     */
    public void escribir(List<CitaReporteRow> citas, Integer anio, String mesRaw,
                         OutputStream out) throws IOException {
        IOUtils.setByteArrayMaxOverride(1_000_000_000);
        ZipSecureFile.setMinInflateRatio(0.0001D);
        Map<String, Object> resumen = new HashMap<>();
        List<CitaReporteRow> aplicadas = aplicadas(citas);

        try (Workbook wb = abrirPlantilla()) {
            llenarHojaDeListas(wb, aplicadas, anio, mesRaw, resumen);
            llenarPlantillaMensual(wb, aplicadas, anio, mesRaw, resumen);
            wb.write(out);
        }
        out.flush();
    }

    private static List<CitaReporteRow> aplicadas(List<CitaReporteRow> citas) {
        List<CitaReporteRow> aplicadas =
                citas.stream().filter(CitaReporteRow::estaAplicada).toList();
        if (aplicadas.isEmpty()) {
            aplicadas = citas; // demo: si ninguna esta APLICADA, se toman todas
        }
        return aplicadas;
    }

    // ------------------------------------------------------------------
    private Workbook abrirPlantilla() throws IOException {
        InputStream in;
        if (templatePath != null && !templatePath.isBlank()
                && Files.exists(Path.of(templatePath))) {
            Path target = Path.of(templatePath);
            // Si la ruta es un directorio, buscar un .xlsx dentro (demo/robustez)
            if (Files.isDirectory(target)) {
                java.util.Optional<Path> archivo;
                try (var stream = Files.list(target)) {
                    archivo = stream
                            .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xlsx"))
                            .findFirst();
                }
                if (archivo.isEmpty()) {
                    throw new IOException("Directorio de plantilla sin .xlsx: " + templatePath);
                }
                target = archivo.get();
            }
            log.info("Abriendo plantilla desde: {}", target);
            in = Files.newInputStream(target);
        } else {
            log.warn("No se encontro REPORTE_TEMPLATE_PATH ('{}'); usando plantilla incrustada",
                    templatePath);
            in = getClass().getResourceAsStream("/plantilla_vacunacion.xlsx");
            if (in == null) {
                throw new IOException(
                        "Plantilla de vacunacion no disponible: configure REPORTE_TEMPLATE_PATH");
            }
        }
        return new XSSFWorkbook(in);
    }

    private byte[] aBytes(Workbook wb) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------
    // HOJA DE LISTAS
    // ------------------------------------------------------------------
    private void llenarHojaDeListas(Workbook wb, List<CitaReporteRow> aplicadas,
                                    Integer anio, String mesRaw, Map<String, Object> resumen) {
        Sheet hoja = wb.getSheet(HOJA_LISTAS);
        if (hoja == null) {
            log.warn("Hoja '{}' no encontrada; se omite.", HOJA_LISTAS);
            return;
        }
        int filaInicio = primeraFilaVacia(hoja, 2);
        log.info("HOJA DE LISTAS: escribiendo {} dosis desde la fila {}", aplicadas.size(), filaInicio + 1);

        String mes = mesEspanol(mesRaw);
        int idx = 0;
        for (CitaReporteRow c : aplicadas) {
            String[] vacuna = MapeoVacuna.vacunaPara(c, idx);
            Row r = hoja.getRow(filaInicio) != null ? hoja.getRow(filaInicio)
                    : hoja.createRow(filaInicio);
            set(r, COL_MUNICIPIO, municipioDe(c));
            set(r, COL_TIPO_ID, "Cédula de Ciudadanía");
            set(r, COL_REGIMEN, "Contributivo");
            set(r, COL_ASEG, "Emssanar");
            set(r, COL_ETNIA, etniaDe(c));
            set(r, COL_DESPL, "NO");
            set(r, COL_DISCAP, "NO");
            set(r, COL_SEXO, sexoDe(c));
            set(r, COL_DEPTO, departamento);
            set(r, COL_AREA, "Cabecera");
            set(r, COL_NOVAC, "");
            set(r, COL_MES, mes);
            set(r, COL_USUARIA, "No aplica");

            // Columna de la vacuna segun MapeoVacuna: [columnaVaccina, dosis]
            int colVacuna = vacuna[0].equals("BCG") ? COL_BCG
                    : vacuna[0].equals("HB") ? COL_HB
                    : vacuna[0].equals("PENTA") ? COL_PENTA
                    : vacuna[0].equals("DPT") ? COL_DPT
                    : vacuna[0].equals("ROTA") ? COL_ROTA
                    : vacuna[0].equals("NEUMO") ? COL_NEUMO
                    : vacuna[0].equals("TRIPLE") ? COL_TRIPLE
                    : vacuna[0].equals("FA") ? COL_FA
                    : vacuna[0].equals("TOXOIDE") ? COL_TOXOIDE
                    : vacuna[0].equals("POLIO_VPI") ? COL_POLIO_VPI
                    : COL_HB2;
            set(r, colVacuna, vacuna[1]);
            set(r, COL_JERINGAS, 1);
            filaInicio++;
            idx++;
        }
        resumen.put("listas", aplicadas.size());
    }

    // ------------------------------------------------------------------
    // PLANTILLA MENSUAL
    // ------------------------------------------------------------------
    private void llenarPlantillaMensual(Workbook wb, List<CitaReporteRow> aplicadas,
                                        Integer anio, String mesRaw, Map<String, Object> resumen) {
        Sheet hoja = wb.getSheet(HOJA_MENSUAL);
        if (hoja == null) {
            log.warn("Hoja '{}' no encontrada; se omite.", HOJA_MENSUAL);
            return;
        }
        String mes = mesEspanol(mesRaw);

        // AÑO (fila 3, col 4) y MES DE REPORTE (fila 4, col 4)
        if (anio != null) {
            Row rAño = hoja.getRow(2);
            if (rAño == null) { rAño = hoja.createRow(2); }
            set(rAño, 4, anio);
        }
        Row rMes = hoja.getRow(3);
        if (rMes == null) { rMes = hoja.createRow(3); }
        set(rMes, 4, mes);

        // Total dosis por municipio (col 475 = TOTAL DOSIS)
        Map<String, Long> porMunicipio = new HashMap<>();
        for (CitaReporteRow c : aplicadas) {
            porMunicipio.merge(municipioDe(c), 1L, Long::sum);
        }
        resumen.put("totalDosis", porMunicipio.values().stream().mapToLong(Long::longValue).sum());
        resumen.put("municipios", porMunicipio);

        // Recorre las filas de datos (empiezan en 8) y suma el total en la
        // fila de genero correspondiente por municipio (col 3 = NOMBRE MUNICIPIO).
        for (int i = 7; i < 12; i++) {
            Row r = hoja.getRow(i);
            if (r == null) continue;
            String nombre = celdaString(r.getCell(2));
            if (nombre != null && porMunicipio.containsKey(nombre)) {
                Long total = porMunicipio.get(nombre);
                // col 475 (1-based) = indice 474 (0-based)
                set(r, 475, total);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers de mapeo / utilidades
    // ------------------------------------------------------------------
    public static String municipioDe(CitaReporteRow c) {
        String ubi = c.getUbicacion();
        if (ubi == null || ubi.isBlank()) return "Popayán";
        if (ubi.contains(",")) return ubi.split(",")[0].trim();
        return ubi.trim();
    }

    private static String sexoDe(CitaReporteRow c) {
        String g = c.getGenero();
        if (g == null) return "FEMENINO";
        return switch (g.toUpperCase()) {
            case "M", "MASCULINO", "HOMBRE" -> "MASCULINO";
            default -> "FEMENINO";
        };
    }

    private static String etniaDe(CitaReporteRow c) {
        String e = c.getEtnia();
        if (e == null || e.isBlank()) return "Ninguno";
        return switch (e.toUpperCase()) {
            case "INDIGENA", "INDÍGENA" -> "Indigena";
            case "AFRO", "AFROCOLOMBIANO", "NEGRO" -> "Negro(a), Mulato(a), Afrocolombiano(a)";
            default -> "Ninguno";
        };
    }

    public static String mesEspanol(String raw) {
        if (raw == null || raw.isBlank()) return "Julio";
        return switch (raw.trim().toUpperCase()) {
            case "ENERO" -> "Enero"; case "FEBRERO" -> "Febrero"; case "MARZO" -> "Marzo";
            case "ABRIL" -> "Abril"; case "MAYO" -> "Mayo"; case "JUNIO" -> "Junio";
            case "JULIO" -> "Julio"; case "AGOSTO" -> "Agosto"; case "SEPTIEMBRE" -> "Septiembre";
            case "OCTUBRE" -> "Octubre"; case "NOVIEMBRE" -> "Noviembre"; case "DICIEMBRE" -> "Diciembre";
            default -> raw;
        };
    }

    private static int primeraFilaVacia(Sheet hoja, int inicio) {
        int last = hoja.getLastRowNum();
        int row = Math.max(inicio, last + 1);
        // Si la fila `row` ya tiene datos, avanzar hasta una vacia por col 1
        while (celdaString(hoja.getRow(row) != null ? hoja.getRow(row).getCell(0) : null) != null
                || (hoja.getRow(row) != null && celdaString(hoja.getRow(row).getCell(1)) != null)) {
            row++;
        }
        return row;
    }

    private static String celdaString(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default -> null;
        };
    }

    private static void set(Row row, int col1, Object value) {
        Cell cell = row.getCell(col1 - 1);
        if (cell == null) cell = row.createCell(col1 - 1);
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }
}
