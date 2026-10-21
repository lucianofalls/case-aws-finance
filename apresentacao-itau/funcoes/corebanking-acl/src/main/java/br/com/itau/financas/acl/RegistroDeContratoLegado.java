package br.com.itau.financas.acl;

/**
 * Registro posicional do core banking (layout de copybook, 76 posições):
 * <pre>
 *   pos  1-20  NUM-CONTRATO   alfanumérico, completado com espaços à direita
 *   pos 21-32  NUM-CONTA      alfanumérico
 *   pos 33-34  COD-PRODUTO    CP, CC, CE, IN, SG, CS
 *   pos 35-49  VLR-LIMITE     numérico em centavos, zeros à esquerda (15 dígitos)
 *   pos 50-52  COD-MOEDA      ISO 4217
 *   pos 53-62  NUM-SEQ-ATU    sequência monotônica de atualização (10 dígitos)
 *   pos 63-76  DAT-HOR-ATU    yyyyMMddHHmmss, horário de Brasília
 * </pre>
 * Este tipo representa o modelo do legado como ele é; nada daqui vaza para os outros contextos.
 */
public record RegistroDeContratoLegado(String numeroContrato, String numeroConta, String codigoProduto,
                                       String valorLimiteEmCentavos, String codigoMoeda, String sequenciaAtualizacao,
                                       String dataHoraAtualizacao) {

    public static final int TAMANHO = 76;

    public static RegistroDeContratoLegado ler(String linha) {
        if (linha == null || linha.length() != TAMANHO) {
            throw new RegistroLegadoInvalidoException("registro deve ter " + TAMANHO + " posições, recebido "
                    + (linha == null ? 0 : linha.length()));
        }
        return new RegistroDeContratoLegado(
                linha.substring(0, 20).strip(),
                linha.substring(20, 32).strip(),
                linha.substring(32, 34),
                linha.substring(34, 49),
                linha.substring(49, 52),
                linha.substring(52, 62),
                linha.substring(62, 76));
    }

    public static final class RegistroLegadoInvalidoException extends RuntimeException {
        public RegistroLegadoInvalidoException(String mensagem) {
            super(mensagem);
        }
    }
}
