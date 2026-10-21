package br.com.itau.financas.autorizacao.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * Identidade da autorização.
 * <p>
 * É <b>derivada deterministicamente</b> do par (cliente, Idempotency-Key) via UUID v5 (RFC 9562):
 * a mesma requisição sempre gera o mesmo id. Isso faz a própria escrita condicional da autorização
 * (attribute_not_exists) ser o controle de idempotência — sem item extra de idempotência no banco
 * (economia de 1 WRU por transação, ver docs/custos.md).
 */
public record AutorizacaoId(UUID valor) {

    private static final UUID NAMESPACE = UUID.fromString("6f1c5a52-1d0e-4c0a-9a1e-7c1f0e2b9d10");

    public AutorizacaoId {
        Objects.requireNonNull(valor, "id da autorização é obrigatório");
    }

    public static AutorizacaoId deterministico(String clienteId, IdempotencyKey chave) {
        if (clienteId == null || clienteId.isBlank()) {
            throw new RegraDeNegocioVioladaException("cliente-nao-identificado", "cliente da API não identificado");
        }
        return new AutorizacaoId(uuidV5(clienteId + ":" + chave.valor()));
    }

    public static AutorizacaoId de(String texto) {
        try {
            return new AutorizacaoId(UUID.fromString(texto));
        } catch (IllegalArgumentException e) {
            throw new RegraDeNegocioVioladaException("id-autorizacao-invalido", "id de autorização inválido: " + texto);
        }
    }

    @Override
    public String toString() {
        return valor.toString();
    }

    private static UUID uuidV5(String nome) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(bytesDe(NAMESPACE));
            byte[] hash = sha1.digest(nome.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 indisponível na JVM", e);
        }
    }

    private static byte[] bytesDe(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }
}
