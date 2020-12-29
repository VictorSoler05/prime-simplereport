data "terraform_remote_state" "global" {
  backend = "azurerm"
  config = {
    resource_group_name  = "prime-simple-report-test"
    storage_account_name = "usdssimplereportglobal"
    container_name       = "sr-tfstate"
    key                  = "global/terraform.tfstate"
  }
}

# Resource Groups
data "azurerm_resource_group" "global" {
  name = "${local.project}-${local.name}-management"
}

data "azurerm_resource_group" "prod" {
  name = "${local.project}-${local.name}-${local.env}"
}

data "azurerm_client_config" "current" {}

# Logs
data "azurerm_log_analytics_workspace" "global" {
  name                = "simple-report-log-workspace-global"
  resource_group_name = data.azurerm_resource_group.global.name
}

# Vaults
data "azurerm_key_vault" "global" {
  name                = "simple-report-global"
  resource_group_name = data.azurerm_resource_group.global.name
}

data "azurerm_key_vault" "db_keys" {
  name                = "simple-report-db-keys"
  resource_group_name = data.azurerm_resource_group.global.name
}

data "azurerm_key_vault_key" "db_encryption_key" {
  name         = local.env
  key_vault_id = data.azurerm_key_vault.db_keys.id
}

data "azurerm_key_vault_secret" "psql_connect_password" {
  name         = "psql-connect-password-${local.env}"
  key_vault_id = data.azurerm_key_vault.global.id
}