output "funcoes" {
  value = { for nome, f in aws_lambda_function.funcao : nome => f.function_name }
}

output "arns" {
  description = "ARN do alias \"atual\" de cada função (usado pela máquina de estados da conciliação)."
  value       = { for nome, alias in aws_lambda_alias.atual : nome => alias.arn }
}
