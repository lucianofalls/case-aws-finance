package br.com.itau.financas.autorizacao.application.port.out;

/** Autorização em nível de objeto: identidade autenticada precisa estar habilitada no contrato e na conta.
 * O provisioning de permissões vem de fonte corporativa CONFIÁVEL; nunca do payload do cliente.
 */
@FunctionalInterface
public interface AcessoClienteContratoPort {
    void exigirAcesso(String clienteId, String contratoId, String contaId);
}
