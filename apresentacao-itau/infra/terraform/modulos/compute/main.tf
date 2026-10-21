# ECS Fargate (ARM64/Graviton) — ADR 002/022/023:
#   autorizacao  : caminho síncrono, atrás do NLB interno (API Gateway → VPC Link → NLB)
#   limites      : caminho síncrono, chamado pela autorização via ECS Service Connect; reconciliador via NLB:8081
#   contabilizacao: consumidor KCL do Kinesis; Fargate Spot com base on-demand (tolera interrupção: idempotente)

data "aws_region" "atual" {}

locals {
  servicos = {
    autorizacao = {
      porta_nlb           = 80
      cpu                 = 1024
      memoria             = 2048
      min                 = var.escala.autorizacao.min
      max                 = var.escala.autorizacao.max
      com_nlb             = true
      servidor_sc         = false
      usa_service_connect = true
      spot                = false
      ambiente            = var.ambiente_autorizacao
      politica_iam        = var.politica_iam_autorizacao
    }
    limites = {
      porta_nlb           = 8081
      cpu                 = 1024
      memoria             = 2048
      min                 = var.escala.limites.min
      max                 = var.escala.limites.max
      com_nlb             = true
      servidor_sc         = true
      usa_service_connect = true
      spot                = false
      ambiente            = var.ambiente_limites
      politica_iam        = var.politica_iam_limites
    }
    contabilizacao = {
      porta_nlb           = null
      cpu                 = 1024
      memoria             = 2048
      min                 = var.escala.contabilizacao.min
      max                 = var.escala.contabilizacao.max
      com_nlb             = false
      servidor_sc         = false
      usa_service_connect = false
      spot                = true
      ambiente            = var.ambiente_contabilizacao
      politica_iam        = var.politica_iam_contabilizacao
    }
  }
  com_nlb = { for nome, s in local.servicos : nome => s if s.com_nlb }
}

resource "aws_ecs_cluster" "this" {
  name = "${var.prefixo}financas"
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]
}

resource "aws_service_discovery_http_namespace" "this" {
  name        = "${var.prefixo}financas.local"
  description = "Namespace do ECS Service Connect (leste-oeste sem service mesh; App Mesh foi descontinuado)"
}

resource "aws_ecr_repository" "servico" {
  for_each             = local.servicos
  name                 = "${var.prefixo}${each.key}-service"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_cloudwatch_log_group" "servico" {
  for_each          = local.servicos
  name              = "/ecs/${var.prefixo}${each.key}-service"
  retention_in_days = 30
}

# ---- IAM: uma role de execução comum e uma role de task por serviço (least privilege) ------------------
data "aws_iam_policy_document" "confia_ecs_tasks" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execucao" {
  name               = "${var.prefixo}ecs-execucao"
  assume_role_policy = data.aws_iam_policy_document.confia_ecs_tasks.json
}

resource "aws_iam_role_policy_attachment" "execucao" {
  role       = aws_iam_role.execucao.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execucao_segredos" {
  count = length(var.segredos_contabilizacao) > 0 ? 1 : 0
  role  = aws_iam_role.execucao.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = ["secretsmanager:GetSecretValue"]
      # valueFrom pode apontar para uma chave do JSON ("<arn>:password::"); a permissão é no ARN do segredo.
      Resource = [for referencia in values(var.segredos_contabilizacao) : regex("^arn:[^:]+:secretsmanager:[^:]+:[^:]+:secret:[^:]+", referencia)]
    }]
  })
}

resource "aws_iam_role" "task" {
  for_each           = local.servicos
  name               = "${var.prefixo}${each.key}-task"
  assume_role_policy = data.aws_iam_policy_document.confia_ecs_tasks.json
}

resource "aws_iam_role_policy" "task" {
  for_each = local.servicos
  role     = aws_iam_role.task[each.key].id
  policy   = each.value.politica_iam
}

resource "aws_iam_role_policy_attachment" "xray" {
  for_each   = local.servicos
  role       = aws_iam_role.task[each.key].name
  policy_arn = "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"
}

# ---- Rede dos serviços ---------------------------------------------------------------------------------
resource "aws_security_group" "servicos" {
  name        = "${var.prefixo}servicos-financas"
  description = "Tarefas ECS: HTTP 8080 apenas de dentro da VPC (NLB, Service Connect, reconciliador)"
  vpc_id      = var.vpc_id

  ingress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = [var.cidr_vpc]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_lb" "interno" {
  name                             = "${var.prefixo}financas-interno"
  internal                         = true
  load_balancer_type               = "network"
  subnets                          = var.subnets_privadas
  enable_cross_zone_load_balancing = true
}

resource "aws_lb_target_group" "servico" {
  for_each             = local.com_nlb
  name                 = "${var.prefixo}${each.key}"
  port                 = 8080
  protocol             = "TCP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  health_check {
    protocol = "HTTP"
    path     = "/actuator/health/readiness"
    interval = 10
  }
}

resource "aws_lb_listener" "servico" {
  for_each          = local.com_nlb
  load_balancer_arn = aws_lb.interno.arn
  port              = each.value.porta_nlb
  protocol          = "TCP"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.servico[each.key].arn
  }
}

# ---- Task definitions + serviços -----------------------------------------------------------------------
resource "aws_ecs_task_definition" "servico" {
  for_each                 = local.servicos
  family                   = "${var.prefixo}${each.key}-service"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memoria
  execution_role_arn       = aws_iam_role.execucao.arn
  task_role_arn            = aws_iam_role.task[each.key].arn

  runtime_platform {
    cpu_architecture        = "ARM64" # Graviton: ~20% mais barato e mais eficiente (Custo + Sustentabilidade)
    operating_system_family = "LINUX"
  }

  container_definitions = jsonencode([
    {
      name         = "app"
      image        = "${aws_ecr_repository.servico[each.key].repository_url}:${var.versao_imagem}"
      essential    = true
      portMappings = [{ name = "http", containerPort = 8080, protocol = "tcp", appProtocol = "http" }]
      environment  = [for chave, valor in merge(each.value.ambiente, { AWS_REGION = data.aws_region.atual.name }) : { name = chave, value = valor }]
      secrets      = each.key == "contabilizacao" ? [for chave, arn in var.segredos_contabilizacao : { name = chave, valueFrom = arn }] : []
      stopTimeout  = 30 # shutdown gracioso: conclui requisições e faz checkpoint do KCL
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.servico[each.key].name
          awslogs-region        = data.aws_region.atual.name
          awslogs-stream-prefix = "app"
        }
      }
    },
    {
      # ADOT (OpenTelemetry) → X-Ray / CloudWatch: traces com correlation_id ponta a ponta
      name      = "adot-collector"
      image     = "public.ecr.aws/aws-observability/aws-otel-collector:latest"
      essential = false
      command   = ["--config=/etc/ecs/ecs-default-config.yaml"]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.servico[each.key].name
          awslogs-region        = data.aws_region.atual.name
          awslogs-stream-prefix = "adot"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "servico" {
  for_each               = local.servicos
  name                   = "${each.key}-service"
  cluster                = aws_ecs_cluster.this.id
  task_definition        = aws_ecs_task_definition.servico[each.key].arn
  desired_count          = each.value.min
  enable_execute_command = false
  propagate_tags         = "SERVICE"

  capacity_provider_strategy {
    capacity_provider = "FARGATE"
    base              = each.value.spot ? 1 : each.value.min
    weight            = each.value.spot ? 1 : 100
  }

  dynamic "capacity_provider_strategy" {
    for_each = each.value.spot ? [1] : []
    content {
      capacity_provider = "FARGATE_SPOT"
      weight            = 3
    }
  }

  network_configuration {
    subnets          = var.subnets_privadas
    security_groups  = [aws_security_group.servicos.id]
    assign_public_ip = false
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  dynamic "load_balancer" {
    for_each = each.value.com_nlb ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.servico[each.key].arn
      container_name   = "app"
      container_port   = 8080
    }
  }

  # Service Connect só onde há chamada leste-oeste (autorização → limites): evita o proxy sidecar à toa.
  dynamic "service_connect_configuration" {
    for_each = each.value.usa_service_connect ? [1] : []
    content {
      enabled   = true
      namespace = aws_service_discovery_http_namespace.this.arn

      dynamic "service" {
        for_each = each.value.servidor_sc ? [1] : []
        content {
          port_name      = "http"
          discovery_name = each.key
          client_alias {
            port     = 8080
            dns_name = each.key
          }
          timeout {
            per_request_timeout_seconds = 2
          }
        }
      }
    }
  }

  lifecycle {
    ignore_changes = [desired_count] # gerido pelo auto scaling
  }

  depends_on = [aws_lb_listener.servico, aws_ecs_cluster_capacity_providers.this]
}

# ---- Auto scaling: target tracking + agenda de picos previsíveis (início do mês) -----------------------
resource "aws_appautoscaling_target" "servico" {
  for_each           = local.servicos
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.servico[each.key].name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = each.value.min
  max_capacity       = each.value.max
}

resource "aws_appautoscaling_policy" "cpu" {
  for_each           = aws_appautoscaling_target.servico
  name               = "${each.key}-cpu-60"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = each.value.service_namespace
  resource_id        = each.value.resource_id
  scalable_dimension = each.value.scalable_dimension

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 300
    scale_out_cooldown = 30 # sobe rápido, desce devagar
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

resource "aws_appautoscaling_scheduled_action" "inicio_do_mes" {
  for_each           = { for nome, alvo in aws_appautoscaling_target.servico : nome => alvo if nome != "contabilizacao" }
  name               = "${each.key}-inicio-do-mes"
  service_namespace  = each.value.service_namespace
  resource_id        = each.value.resource_id
  scalable_dimension = each.value.scalable_dimension
  schedule           = "cron(0 6 1 * ? *)"
  timezone           = "America/Sao_Paulo"

  scalable_target_action {
    min_capacity = local.servicos[each.key].min * 2
    max_capacity = local.servicos[each.key].max
  }
}

resource "aws_appautoscaling_scheduled_action" "pos_inicio_do_mes" {
  for_each           = aws_appautoscaling_scheduled_action.inicio_do_mes
  name               = "${each.key}-pos-inicio-do-mes"
  service_namespace  = each.value.service_namespace
  resource_id        = each.value.resource_id
  scalable_dimension = each.value.scalable_dimension
  schedule           = "cron(0 6 6 * ? *)"
  timezone           = "America/Sao_Paulo"

  scalable_target_action {
    min_capacity = local.servicos[each.key].min
    max_capacity = local.servicos[each.key].max
  }
}
