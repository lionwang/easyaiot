#!/bin/bash

# ============================================
# CentOS 7.9 单独启动 PostgreSQL 容器脚本
# ============================================
# 仅启动 docker-compose.yml 中的 PostgresSQL 服务（不启动其他中间件）
#
# 使用方法：
#   cd .scripts/docker
#   chmod +x start_postgresql_centos7.sh
#   ./start_postgresql_centos7.sh
#
# 选项：
#   -h, --help      显示帮助
#   -f, --force     跳过 CentOS 7 系统检查
#   --stop          停止 PostgreSQL 容器
#   --restart       重启 PostgreSQL 容器
#   --status        查看容器与健康状态
#   --no-init       跳过 PostgresSQL-init 权限初始化容器
#   --no-wait       启动后不等待 pg_isready
#
# 默认连接信息（与 docker-compose.yml 一致）：
#   主机: 127.0.0.1  端口: 5432
#   用户: postgres    密码: iot45722414822
#   数据库: postgres
# ============================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

COMPOSE_FILE="docker-compose.yml"
SERVICE_INIT="PostgresSQL-init"
SERVICE_PG="PostgresSQL"
CONTAINER_NAME="postgres-server"
INIT_CONTAINER="postgres-init"
NETWORK_NAME="easyaiot-network"
PG_PORT=5432

FORCE_OS_CHECK=false
RUN_INIT=true
WAIT_READY=true
ACTION="start"

print_info()    { echo -e "${BLUE}[INFO]${NC} $1"; }
print_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
print_error()   { echo -e "${RED}[ERROR]${NC} $1"; }

print_section() {
    echo ""
    echo -e "${CYAN}========================================${NC}"
    echo -e "${CYAN}  $1${NC}"
    echo -e "${CYAN}========================================${NC}"
    echo ""
}

show_help() {
    cat <<'EOF'
CentOS 7.9 单独启动 PostgreSQL 容器

用法:
  ./start_postgresql_centos7.sh [选项]

选项:
  -h, --help      显示此帮助
  -f, --force     跳过 CentOS 7 系统检查
  --stop          停止 PostgreSQL 容器
  --restart       重启 PostgreSQL 容器
  --status        查看容器状态
  --no-init       跳过权限初始化容器 PostgresSQL-init
  --no-wait       启动后不等待数据库就绪

示例:
  ./start_postgresql_centos7.sh
  ./start_postgresql_centos7.sh --restart
  sudo ./start_postgresql_centos7.sh   # 首次部署建议用 root/sudo 设置数据目录权限
EOF
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help)
                show_help
                exit 0
                ;;
            -f|--force)
                FORCE_OS_CHECK=true
                shift
                ;;
            --stop)
                ACTION="stop"
                shift
                ;;
            --restart)
                ACTION="restart"
                shift
                ;;
            --status)
                ACTION="status"
                shift
                ;;
            --no-init)
                RUN_INIT=false
                shift
                ;;
            --no-wait)
                WAIT_READY=false
                shift
                ;;
            *)
                print_error "未知选项: $1"
                show_help
                exit 1
                ;;
        esac
    done
}

# 检测是否为 CentOS 7.x（7.9 等）
check_centos7() {
    if [ "$FORCE_OS_CHECK" = true ]; then
        print_warning "已跳过 CentOS 7 系统检查 (--force)"
        return 0
    fi

    print_section "系统环境检查"

    local os_id="" os_version=""
    if [ -f /etc/os-release ]; then
        # shellcheck source=/dev/null
        source /etc/os-release
        os_id="${ID:-}"
        os_version="${VERSION_ID:-}"
    elif [ -f /etc/redhat-release ]; then
        if grep -qi "centos" /etc/redhat-release 2>/dev/null; then
            os_id="centos"
        fi
        os_version=$(grep -oE '[0-9]+\.[0-9]+' /etc/redhat-release | head -1)
    fi

    if [ "$os_id" != "centos" ]; then
        print_warning "当前系统不是 CentOS (ID=$os_id)，脚本仍可继续"
        print_info "非 CentOS 环境请使用: ./start_postgresql_centos7.sh --force"
        return 0
    fi

    local major="${os_version%%.*}"
    if [ "$major" != "7" ]; then
        print_warning "检测到 CentOS $os_version，本脚本针对 CentOS 7.9 优化"
        print_info "CentOS 8+ 通常使用 dnf，可参考 install_middleware_linux.sh"
    else
        print_success "CentOS 7.x ($os_version)"
    fi

    if command -v getenforce >/dev/null 2>&1; then
        local selinux_status
        selinux_status=$(getenforce 2>/dev/null || echo "未知")
        print_info "SELinux 状态: $selinux_status"
        if [ "$selinux_status" = "Enforcing" ]; then
            print_warning "SELinux 为 Enforcing 时，若挂载目录无法访问，可临时: setenforce 0"
            print_info "或给数据目录打标签: chcon -Rt svirt_sandbox_file_t db_data/"
        fi
    fi

    if systemctl is-active firewalld >/dev/null 2>&1; then
        print_info "firewalld 正在运行，若宿主机无法连接 5432，请放行端口:"
        print_info "  sudo firewall-cmd --permanent --add-port=5432/tcp && sudo firewall-cmd --reload"
    fi
}

resolve_compose_cmd() {
    if docker compose version >/dev/null 2>&1; then
        COMPOSE_CMD="docker compose"
    elif command -v docker-compose >/dev/null 2>&1; then
        COMPOSE_CMD="docker-compose"
    else
        print_error "未找到 docker compose / docker-compose"
        print_info "CentOS 7 安装示例:"
        print_info "  sudo yum install -y yum-utils"
        print_info "  sudo yum-config-manager --add-repo https://mirrors.huaweicloud.com/docker-ce/linux/centos/docker-ce.repo"
        print_info "  sudo yum install -y docker-ce docker-ce-cli containerd.io"
        print_info "  sudo systemctl enable --now docker"
        exit 1
    fi
    print_info "使用 Compose 命令: $COMPOSE_CMD"
}

check_docker() {
    print_info "检查 Docker 服务..."
    if docker info >/dev/null 2>&1; then
        print_success "Docker 可用"
        return 0
    fi

    print_warning "Docker 未运行或当前用户无权限，尝试启动..."
    if command -v systemctl >/dev/null 2>&1; then
        if [ "$EUID" -eq 0 ]; then
            systemctl start docker || true
        elif command -v sudo >/dev/null 2>&1; then
            sudo systemctl start docker || true
        fi
    fi

    if ! docker info >/dev/null 2>&1; then
        print_error "无法连接 Docker"
        print_info "请执行: sudo systemctl start docker"
        print_info "并将用户加入 docker 组: sudo usermod -aG docker \$USER && newgrp docker"
        exit 1
    fi
    print_success "Docker 已启动"
}

check_compose_file() {
    if [ ! -f "$COMPOSE_FILE" ]; then
        print_error "未找到 $COMPOSE_FILE，请在 .scripts/docker 目录下运行"
        exit 1
    fi
}

check_required_files() {
    local missing=0
    for f in postgresql-entrypoint.sh init-databases.sh; do
        if [ ! -f "$f" ]; then
            print_error "缺少必需文件: $SCRIPT_DIR/$f"
            missing=1
        fi
    done
    if [ ! -d "../postgresql" ]; then
        print_warning "目录 ../postgresql 不存在，将创建空目录（跳过 SQL 初始化脚本挂载内容）"
        mkdir -p "../postgresql"
    fi
    if [ "$missing" -eq 1 ]; then
        exit 1
    fi
}

ensure_network() {
    if docker network ls --format '{{.Name}}' | grep -q "^${NETWORK_NAME}$"; then
        print_success "Docker 网络 ${NETWORK_NAME} 已存在"
    else
        print_info "创建 Docker 网络 ${NETWORK_NAME}..."
        docker network create "$NETWORK_NAME" >/dev/null
        print_success "网络 ${NETWORK_NAME} 已创建"
    fi
}

create_data_dirs() {
    local data_dir="${SCRIPT_DIR}/db_data/data"
    local log_dir="${SCRIPT_DIR}/db_data/log"

    print_info "准备数据目录 db_data/{data,log}..."
    mkdir -p "$data_dir" "$log_dir"

    if [ "$EUID" -eq 0 ]; then
        chown -R 999:999 "$data_dir" "$log_dir"
        chmod -R 777 "$data_dir" "$log_dir"
        print_success "数据目录权限已设置 (999:999)"
    elif command -v sudo >/dev/null 2>&1; then
        if sudo chown -R 999:999 "$data_dir" "$log_dir" 2>/dev/null; then
            sudo chmod -R 777 "$data_dir" "$log_dir" 2>/dev/null || true
            print_success "数据目录权限已设置 (999:999)"
        else
            print_warning "无法设置目录属主，将依赖 PostgresSQL-init 容器修复权限"
        fi
    else
        print_warning "非 root 且无法 sudo，将依赖 PostgresSQL-init 容器修复权限"
    fi
}

check_port_conflict() {
    print_info "检查端口 ${PG_PORT} 是否被占用..."
    local pid=""
    if command -v ss >/dev/null 2>&1; then
        pid=$(ss -lptn "sport = :${PG_PORT}" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1 || true)
    elif command -v netstat >/dev/null 2>&1; then
        pid=$(netstat -tlnp 2>/dev/null | grep ":${PG_PORT} " | awk '{print $7}' | cut -d'/' -f1 | head -1 || true)
    fi

    if [ -n "$pid" ] && [ "$pid" != "-" ]; then
        if docker ps --filter "name=${CONTAINER_NAME}" --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
            print_info "端口 ${PG_PORT} 已由 ${CONTAINER_NAME} 占用"
            return 0
        fi
        print_warning "端口 ${PG_PORT} 被宿主机进程 PID=${pid} 占用"
        print_info "可执行: sudo ./restart_postgresql.sh 或停止占用进程后再试"
        return 1
    fi
    print_success "端口 ${PG_PORT} 可用"
}

run_init_container() {
    print_section "运行 PostgreSQL 权限初始化 (${SERVICE_INIT})"
    $COMPOSE_CMD -f "$COMPOSE_FILE" up --no-deps "$SERVICE_INIT"
    if docker ps -a --filter "name=${INIT_CONTAINER}" --format '{{.Status}}' | grep -q "Exited (0)"; then
        print_success "权限初始化完成"
    else
        print_warning "初始化容器未以 0 退出，请检查: docker logs ${INIT_CONTAINER}"
    fi
    docker rm -f "$INIT_CONTAINER" 2>/dev/null || true
}

start_postgresql() {
    print_section "启动 PostgreSQL (${SERVICE_PG})"

    if [ "$RUN_INIT" = true ]; then
        run_init_container
    fi

    $COMPOSE_CMD -f "$COMPOSE_FILE" up -d --no-deps "$SERVICE_PG"
    print_success "已执行: $COMPOSE_CMD up -d --no-deps $SERVICE_PG"
}

wait_for_postgresql() {
    if [ "$WAIT_READY" = false ]; then
        return 0
    fi

    print_info "等待 PostgreSQL 就绪（最多 60 秒）..."
    local attempt=0
    local max_attempts=30
    while [ "$attempt" -lt "$max_attempts" ]; do
        if docker exec "$CONTAINER_NAME" pg_isready -U postgres >/dev/null 2>&1; then
            print_success "PostgreSQL 已就绪"
            return 0
        fi
        attempt=$((attempt + 1))
        sleep 2
    done

    print_warning "健康检查超时，容器可能仍在初始化"
    print_info "查看日志: docker logs ${CONTAINER_NAME}"
    return 1
}

show_connection_info() {
    print_section "连接信息"
    echo "  容器名:   ${CONTAINER_NAME}"
    echo "  地址:     127.0.0.1:${PG_PORT}"
    echo "  用户:     postgres"
    echo "  密码:     iot45722414822"
    echo "  数据库:   postgres"
    echo ""
    print_info "常用命令:"
    echo "  docker ps | grep ${CONTAINER_NAME}"
    echo "  docker logs -f ${CONTAINER_NAME}"
    echo "  docker exec -it ${CONTAINER_NAME} psql -U postgres -d postgres"
    echo "  ./test_postgresql_connection.sh"
}

stop_postgresql() {
    print_section "停止 PostgreSQL"
    if docker ps --filter "name=${CONTAINER_NAME}" --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
        docker stop "$CONTAINER_NAME"
        print_success "容器已停止"
    else
        $COMPOSE_CMD -f "$COMPOSE_FILE" stop "$SERVICE_PG" 2>/dev/null || true
        print_info "容器未在运行"
    fi
}

show_status() {
    print_section "PostgreSQL 状态"
    docker ps -a --filter "name=${CONTAINER_NAME}" --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' || true
    echo ""
    if docker ps --filter "name=${CONTAINER_NAME}" --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
        if docker exec "$CONTAINER_NAME" pg_isready -U postgres 2>/dev/null; then
            print_success "pg_isready: 正常"
        else
            print_warning "pg_isready: 未就绪"
        fi
    fi
}

main() {
    parse_args "$@"

    case "$ACTION" in
        stop)
            check_docker
            resolve_compose_cmd
            stop_postgresql
            exit 0
            ;;
        status)
            check_docker
            show_status
            exit 0
            ;;
        restart)
            check_docker
            resolve_compose_cmd
            check_compose_file
            stop_postgresql
            sleep 2
            ACTION="start"
            ;;
    esac

    print_section "CentOS 7.9 PostgreSQL 独立启动"
    check_centos7
    check_docker
    resolve_compose_cmd
    check_compose_file
    check_required_files
    ensure_network
    create_data_dirs
    check_port_conflict || print_warning "端口冲突可能导致启动失败，继续尝试..."

    start_postgresql
    wait_for_postgresql || true
    show_connection_info
    print_success "PostgreSQL 独立启动流程完成"
}

main "$@"
