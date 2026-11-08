/**
 * Kontrak bersama antara {@code api} dan {@code realtime} (aturan M11): frame WebSocket dan envelope
 * event. JSON Schema versinya ada di {@code resources/contracts/}. Ditandai modul terbuka supaya
 * Spring Modulith di {@code api} tidak menganggapnya modul bisnis.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package app.senyap.contracts;
