"""Regressões da barreira de prontidão: não aprovar infraestrutura incompleta."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('infra', Path(__file__).with_name('verificar-infra.py'))
infra = importlib.util.module_from_spec(spec)
spec.loader.exec_module(infra)


def resposta(*args):
    servico, operacao = args[:2]
    if servico == 'dynamodb':
        return {'Table': {'TableStatus': 'ACTIVE', 'LatestStreamArn': 'stream'}}
    if servico == 'kinesis':
        return {'StreamDescriptionSummary': {'StreamStatus': 'ACTIVE'}}
    if servico == 'sqs':
        return {'QueueUrl': 'http://localstack:4566/queue'}
    if operacao == 'get-function-configuration':
        return {'State': 'Active'}
    if operacao == 'list-event-source-mappings':
        return {'EventSourceMappings': [{'State': 'Enabled'}]}
    if operacao == 'describe-rule':
        return {'State': 'ENABLED'}
    return {'Targets': [{'Arn': 'sqs'}]}


class ProntidaoTest(unittest.TestCase):
    def test_infra_ativa(self):
        with patch.object(infra, 'comando'), patch.object(infra, 'aws', side_effect=resposta):
            infra.conferir()

    def test_lambda_failed_interrompe_sem_aprovar(self):
        def falha(*args):
            if args[:2] == ('lambda', 'get-function-configuration'):
                return {'State': 'Failed', 'StateReason': 'Docker not available'}
            return resposta(*args)
        with patch.object(infra, 'comando'), patch.object(infra, 'aws', side_effect=falha):
            with self.assertRaisesRegex(RuntimeError, 'Docker not available'):
                infra.conferir()

    def test_mapping_desabilitado_nao_passa(self):
        def falha(*args):
            if args[:2] == ('lambda', 'list-event-source-mappings'):
                return {'EventSourceMappings': [{'State': 'Disabled'}]}
            return resposta(*args)
        with patch.object(infra, 'comando'), patch.object(infra, 'aws', side_effect=falha):
            with self.assertRaisesRegex(ValueError, 'mapping'):
                infra.conferir()


if __name__ == '__main__':
    unittest.main()
