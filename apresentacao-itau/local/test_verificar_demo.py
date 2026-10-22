"""Testa se o aceite detecta erros financeiros; não substitui execução E2E."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('demo', Path(__file__).with_name('verificar-demo.py'))
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)


class VerificadorFinanceiroTest(unittest.TestCase):
    def posicao(self, total='1000.00', comprometido='80.00'):
        return {'limite_disponivel': '920.00', 'saldo_bloqueado': '50.00',
                'saldo_reservado': comprometido, 'limite_concedido': total}

    def test_separa_utilizado_do_comprometido(self):
        with patch.object(demo, 'http', return_value=(200, self.posicao(), {})):
            demo.saldo('CTR', '920', '50', '30')
            with self.assertRaisesRegex(AssertionError, 'Posição divergente'):
                demo.saldo('CTR', '920', '50', '80')

    def test_detecta_invariante_quebrada_mesmo_com_saldos_esperados(self):
        with patch.object(demo, 'http', return_value=(200, self.posicao(total='999.00'), {})):
            with self.assertRaisesRegex(AssertionError, 'Invariante financeira'):
                demo.saldo('CTR', '920', '50', '30')

    def test_mesma_quantidade_com_id_contabil_errado_nao_passa(self):
        esperado = '00000000-0000-0000-0000-000000000001'
        outro = '00000000-0000-0000-0000-000000000002'
        with patch.object(demo, 'aguardar'), patch.object(demo, 'sql', return_value=outro):
            with self.assertRaisesRegex(AssertionError, 'IDs contábeis divergentes'):
                demo.ledger('CTR', [esperado])


if __name__ == '__main__':
    unittest.main()
