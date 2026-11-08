/**
 * Infrastruktur bersama semua modul: TSID, format error, cursor pagination, database per modul,
 * outbox + relay. Tidak berisi logika bisnis. Saat sebuah modul dipecah jadi service, package ini
 * ikut disalin (build guide §3.1, langkah 1).
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package app.senyap.shared;
