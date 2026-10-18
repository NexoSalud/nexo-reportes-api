package com.nexo.reportes.service;

import com.nexo.reportes.domain.CitaReporteRow;

/**
 * Asigna una vacuna + dosis a una cita de vacunacion (logica de demostracion).
 *
 * Una cita de "Vacunación" actualmente solo lleva la especialidad y el perfil
 * (edad/genero/etnia). Como el reporte PAI exige el detalle de vacuna y dosis,
 * aqui se aproxima el esquema 3280 por edad para que la plantilla quede con
 * datos coherentes de punta a punta.
 */
final class MapeoVacuna {

    private MapeoVacuna() {
    }

    /** @return [columnaVacuna, dosis] */
    static String[] vacunaPara(CitaReporteRow c, int idx) {
        Integer edad = c.getEdad();
        if (edad == null) {
            return idx % 2 == 0 ? new String[]{"PENTA", "Primera Dosis"}
                    : new String[]{"TRIPLE", "Primera Dosis"};
        }
        if (edad == 0) {
            return new String[]{"BCG", "Dosis única"};
        }
        if (edad <= 1) {
            return switch (idx % 4) {
                case 0 -> new String[]{"PENTA", "Primera Dosis"};
                case 1 -> new String[]{"ROTA", "Primera Dosis"};
                case 2 -> new String[]{"NEUMO", "Primera Dosis"};
                default -> new String[]{"POLIO_VPI", "Primera Dosis"};
            };
        }
        if (edad <= 5) {
            return switch (idx % 5) {
                case 0 -> new String[]{"DPT", "Refuerzo"};
                case 1 -> new String[]{"TRIPLE", "Primera Dosis"};
                case 2 -> new String[]{"FA", "Dosis única"};
                case 3 -> new String[]{"NEUMO", "Refuerzo"};
                default -> new String[]{"ROTA", "Refuerzo"};
            };
        }
        if (edad <= 17) {
            return new String[]{"TRIPLE", "Refuerzo"};
        }
        // Adultos / gestantes: Td / Tdap
        if ("F".equalsIgnoreCase(c.getGenero())) {
            return new String[]{"TOXOIDE", "Refuerzo"};
        }
        return new String[]{"DPT", "Refuerzo"};
    }
}
