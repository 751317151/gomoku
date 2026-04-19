#!/bin/bash
# 五子棋服务管理脚本
# 用法: ./gomoku.sh {start|stop|restart|status|log}

APP_NAME="gomoku-game"
APP_JAR="gomoku-game-1.0.jar"
APP_LOG="log/app.log"
APP_PID="app.pid"
JAVA_OPTS="-Xms128m -Xmx256m"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

get_pid() {
    if [ -f "$APP_PID" ]; then
        cat "$APP_PID"
    fi
}

is_running() {
    local pid=$(get_pid)
    if [ -n "$pid" ]; then
        kill -0 "$pid" 2>/dev/null
        return $?
    fi
    return 1
}

start() {
    if is_running; then
        echo -e "${YELLOW}[${APP_NAME}] 已在运行中，PID: $(get_pid)${NC}"
        return 1
    fi

    if [ ! -f "$APP_JAR" ]; then
        echo -e "${RED}[${APP_NAME}] 找不到: ${APP_JAR}${NC}"
        return 1
    fi

    echo -e "${GREEN}[${APP_NAME}] 启动中...${NC}"
    nohup java ${JAVA_OPTS} -jar "$APP_JAR" > "$APP_LOG" 2>&1 &
    echo $! > "$APP_PID"

    # 等待启动，最多10秒
    for i in $(seq 1 10); do
        sleep 1
        if is_running; then
            # 检查端口是否已监听
            if ss -tlnp 2>/dev/null | grep -q ':8090\|:8887'; then
                echo -e "${GREEN}[${APP_NAME}] 启动成功，PID: $(get_pid)${NC}"
                return 0
            fi
        else
            echo -e "${RED}[${APP_NAME}] 启动失败，查看日志: tail -50 ${APP_LOG}${NC}"
            rm -f "$APP_PID"
            return 1
        fi
    done

    echo -e "${YELLOW}[${APP_NAME}] 启动中，等待端口就绪... PID: $(get_pid)${NC}"
    return 0
}

stop() {
    if ! is_running; then
        echo -e "${YELLOW}[${APP_NAME}] 未运行${NC}"
        rm -f "$APP_PID"
        return 0
    fi

    local pid=$(get_pid)
    echo -e "${YELLOW}[${APP_NAME}] 停止中，PID: ${pid}${NC}"
    kill "$pid"

    # 等待进程退出，最多15秒
    for i in $(seq 1 15); do
        if ! is_running; then
            echo -e "${GREEN}[${APP_NAME}] 已停止${NC}"
            rm -f "$APP_PID"
            return 0
        fi
        sleep 1
    done

    # 超时强制终止
    echo -e "${RED}[${APP_NAME}] 优雅停止超时，强制终止${NC}"
    kill -9 "$pid"
    rm -f "$APP_PID"
    return 0
}

status() {
    if is_running; then
        echo -e "${GREEN}[${APP_NAME}] 运行中，PID: $(get_pid)${NC}"
        # 显示端口监听
        ss -tlnp 2>/dev/null | grep ':8090\|:8887' | awk '{print "  " $4}'
    else
        echo -e "${RED}[${APP_NAME}] 未运行${NC}"
    fi
}

log() {
    if [ -f "$APP_LOG" ]; then
        tail -100f "$APP_LOG"
    else
        echo -e "${RED}[${APP_NAME}] 日志文件不存在: ${APP_LOG}${NC}"
    fi
}

case "$1" in
    start)   start   ;;
    stop)    stop    ;;
    restart) stop; start ;;
    status)  status  ;;
    log)     log     ;;
    *)
        echo "用法: $0 {start|stop|restart|status|log}"
        exit 1
        ;;
esac
