package app.senyap.shared.security;

import com.nimbusds.jose.jwk.JWKSet;

/**
 * JWKS publik milik penerbit token di proses yang sama (modul auth). Dipakai pemverifikasi bila
 * {@code senyap.security.jwks-uri} tidak di-set. Isinya sama persis dengan yang dilayani di
 * {@code /.well-known/jwks.json}, jadi pemverifikasi tetap hanya bergantung pada public key (M12).
 */
public interface LocalJwks {

    JWKSet publicJwks();
}
