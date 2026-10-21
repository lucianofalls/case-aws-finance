package br.com.itau.financas.autorizacao.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Hash do conteúdo de negócio da requisição. Mesma Idempotency-Key com impressão diferente
 * significa reuso indevido da chave → 422 (nunca reprocessa com outro payload).
 */
public record ImpressaoDigital(String hash) {

    public ImpressaoDigital {
        Objects.requireNonNull(hash, "hash é obrigatório");
    }

    public static ImpressaoDigital de(String... partes) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (String parte : partes) {
                sha256.update((parte == null ? "" : parte).getBytes(StandardCharsets.UTF_8));
                sha256.update((byte) 0x1f);
            }
            return new ImpressaoDigital(HexFormat.of().formatHex(sha256.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }
}
