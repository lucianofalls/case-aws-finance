package br.com.itau.financas.kernel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Valor monetário imutável com escala fixa de 2 casas (decimal(18,2) do contrato da API).
 * Operações entre moedas diferentes são proibidas: a plataforma não faz câmbio.
 */
public record Dinheiro(BigDecimal valor, Currency moeda) implements Comparable<Dinheiro> {

    private static final int ESCALA = 2;
    private static final int DIGITOS_INTEIROS_MAXIMOS = 16;

    public Dinheiro {
        Objects.requireNonNull(valor, "valor é obrigatório");
        Objects.requireNonNull(moeda, "moeda é obrigatória");
        if (valor.stripTrailingZeros().scale() > ESCALA) {
            throw new IllegalArgumentException("valor aceita no máximo " + ESCALA + " casas decimais: " + valor);
        }
        valor = valor.setScale(ESCALA, RoundingMode.UNNECESSARY);
        if (valor.precision() - valor.scale() > DIGITOS_INTEIROS_MAXIMOS) {
            throw new IllegalArgumentException("valor excede decimal(18,2): " + valor);
        }
    }

    public static Dinheiro de(String valor, String codigoMoeda) {
        return new Dinheiro(new BigDecimal(valor), Currency.getInstance(codigoMoeda));
    }

    public static Dinheiro de(BigDecimal valor, String codigoMoeda) {
        return new Dinheiro(valor, Currency.getInstance(codigoMoeda));
    }

    public static Dinheiro zero(Currency moeda) {
        return new Dinheiro(BigDecimal.ZERO, moeda);
    }

    public Dinheiro somar(Dinheiro outro) {
        exigirMesmaMoeda(outro);
        return new Dinheiro(valor.add(outro.valor), moeda);
    }

    public Dinheiro subtrair(Dinheiro outro) {
        exigirMesmaMoeda(outro);
        return new Dinheiro(valor.subtract(outro.valor), moeda);
    }

    public boolean ehPositivo() {
        return valor.signum() > 0;
    }

    public boolean ehNegativo() {
        return valor.signum() < 0;
    }

    public boolean maiorQue(Dinheiro outro) {
        return compareTo(outro) > 0;
    }

    public boolean menorQue(Dinheiro outro) {
        return compareTo(outro) < 0;
    }

    public String codigoMoeda() {
        return moeda.getCurrencyCode();
    }

    /** Representação usada nos contratos: string decimal, nunca ponto flutuante. */
    public String comoTexto() {
        return valor.toPlainString();
    }

    @Override
    public int compareTo(Dinheiro outro) {
        exigirMesmaMoeda(outro);
        return valor.compareTo(outro.valor);
    }

    private void exigirMesmaMoeda(Dinheiro outro) {
        if (!moeda.equals(outro.moeda)) {
            throw new IllegalArgumentException("moedas diferentes: " + moeda + " e " + outro.moeda);
        }
    }

    @Override
    public String toString() {
        return codigoMoeda() + " " + comoTexto();
    }
}
