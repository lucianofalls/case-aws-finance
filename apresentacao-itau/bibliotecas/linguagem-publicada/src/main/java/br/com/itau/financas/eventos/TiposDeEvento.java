package br.com.itau.financas.eventos;

/**
 * Catálogo de tipos CloudEvents publicados pela plataforma.
 * Formato: reverse-DNS + contexto + evento no passado + versão major.
 * Mudança compatível (campo opcional novo) não muda o tipo; incompatível cria ".v2".
 */
public final class TiposDeEvento {

    public static final String FONTE_AUTORIZACAO = "urn:itau:financas:autorizacao-service";
    public static final String FONTE_LIMITES = "urn:itau:financas:limites-service";
    public static final String FONTE_CORE_BANKING_ACL = "urn:itau:financas:corebanking-acl";

    public static final String TRANSACAO_AUTORIZADA = "br.com.itau.financas.autorizacao.transacao-autorizada.v1";
    public static final String TRANSACAO_RECUSADA = "br.com.itau.financas.autorizacao.transacao-recusada.v1";
    public static final String ESTORNO_REALIZADO = "br.com.itau.financas.autorizacao.estorno-realizado.v1";

    public static final String LIMITE_CONCEDIDO = "br.com.itau.financas.corebanking.limite-concedido.v1";
    public static final String LIMITE_ALTERADO = "br.com.itau.financas.limites.limite-alterado.v1";
    public static final String OPERACAO_REVERTIDA =
            "br.com.itau.financas.limites.operacao-revertida.v1";

    private static final String BASE_SCHEMAS = "https://schemas.itau.com.br/financas/";

    private TiposDeEvento() {
    }

    public static String schemaDe(String tipo) {
        String semPrefixo = tipo.substring(tipo.indexOf("financas.") + "financas.".length());
        return BASE_SCHEMAS + semPrefixo.replace('.', '/') + ".json";
    }
}
