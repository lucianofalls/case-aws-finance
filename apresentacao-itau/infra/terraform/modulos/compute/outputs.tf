output "nlb" {
  value = {
    arn      = aws_lb.interno.arn
    dns_name = aws_lb.interno.dns_name
  }
}

output "url_interna_limites" {
  description = "Usada pelo reconciliador (Lambda na VPC); a autorização usa Service Connect (http://limites:8080)."
  value       = "http://${aws_lb.interno.dns_name}:8081"
}

output "security_group_servicos" {
  value = aws_security_group.servicos.id
}

output "cluster" {
  value = aws_ecs_cluster.this.name
}

output "servicos" {
  value = { for nome, s in aws_ecs_service.servico : nome => s.name }
}

output "repositorios_ecr" {
  value = { for nome, r in aws_ecr_repository.servico : nome => r.repository_url }
}
