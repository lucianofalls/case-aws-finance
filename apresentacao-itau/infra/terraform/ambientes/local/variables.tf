variable "endpoint_localstack" {
  type    = string
  default = "http://localhost:4566"
  validation {
    condition     = contains(["http://localhost:4566", "http://127.0.0.1:4566"], var.endpoint_localstack)
    error_message = "O ambiente local só aceita LocalStack no loopback:4566."
  }
}
