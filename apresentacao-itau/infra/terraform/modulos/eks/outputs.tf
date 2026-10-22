output "cluster_name" { value=aws_eks_cluster.this.name }
output "cluster_endpoint" { value=aws_eks_cluster.this.endpoint }
output "cluster_ca_data" { value=aws_eks_cluster.this.certificate_authority[0].data
 sensitive=true }
output "security_group_cluster_id" { value=aws_eks_cluster.this.vpc_config[0].cluster_security_group_id }
output "nlb" { value={ arn=aws_lb.interno.arn, dns_name=aws_lb.interno.dns_name } }
output "target_groups" { value={ for k, t in aws_lb_target_group.servico : k=>t.arn } }
output "url_interna_limites" { value="http://${aws_lb.interno.dns_name}:8081" }
output "repositorios" { value={ for k, r in aws_ecr_repository.servico : k=>r.repository_url } }
