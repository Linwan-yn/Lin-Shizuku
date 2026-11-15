#!/system/bin/sh
# Lin-Shizuku 启动脚本（由 App 自动生成并刷新，请勿手动修改）
# 以 app_process 直连本应用 base.apk 启动服务端，进程名 Lin_server，
# 与旧版 Stellar 服务物理隔离，可共存互不干扰。
# 先启后停：先拉起新服务进程，确认新进程真实存活后，再清理旧进程；
# 新进程未存活时保留旧服务，避免“执行脚本反而把服务关掉”。
# setsid 脱离会话/进程组——App 脚本执行器超时销毁进程树时，新服务独立成组不被连坐。

echo "开始启动 Lin-Shizuku 服务..."
echo "检查现有服务"

APK="%%%APK_PATH%%%"
# APK 路径必须真实存在（覆盖安装/路径变化后 App 会重写脚本；终端拿到旧副本时直接报错）
if [ ! -f "$APK" ]; then
  echo "启动失败：APK 不存在（$APK）"
  echo "请重新打开 Lin-Shizuku 应用刷新启动脚本后再试"
  exit 1
fi

# 记录旧的 Lin_server / Stellar_server（含历史 daemon）进程，先不杀。
# pgrep -x 按 comm 精确匹配，避免把执行脚本的 sh 包装进程自身误判进去。
OLD_SERVER=$( { pgrep -x Lin_server 2>/dev/null; pgrep -x Stellar_server 2>/dev/null; } )
OLD_DAEMON=$( { pgrep -x Lin_daemon 2>/dev/null; pgrep -x Stellar_daemon 2>/dev/null; } )
OLD_ALL="$OLD_SERVER $OLD_DAEMON"

mkdir -p /data/local/tmp/Lin-Shizuku/Lin/log
# 顶层目录 0777——root umask 022 会建成 root:root 0755，导致 shell(uid 2000) 保存脚本时 Permission denied
chmod 0777 /data/local/tmp/Lin-Shizuku /data/local/tmp/Lin-Shizuku/Lin 2>/dev/null

echo "启动新服务进程（先启后停）..."
export CLASSPATH="$APK"
setsid nohup app_process /system/bin --nice-name=Lin_server lin.server.LinService >/data/local/tmp/Lin-Shizuku/Lin/log/Lin_server_launch.log 2>&1 &

# 最多等 12 秒让新进程出现（app_process 起 JVM 较慢）；候选里剔除旧 PID，再用 kill -0 校验真实存活。
NEW_PID=""
for i in $(seq 1 24); do
  sleep 0.5
  CANDIDATES=$( { pgrep -x Lin_server 2>/dev/null; pgrep -x Stellar_server 2>/dev/null; } )
  NEW_PID=""
  for p in $CANDIDATES; do
    case " $OLD_ALL " in
      *" $p "*) continue ;;          # 旧进程，不计入新 PID
    esac
    kill -0 "$p" 2>/dev/null && NEW_PID="$NEW_PID $p"
  done
  [ -n "$NEW_PID" ] && break
done

if [ -n "$NEW_PID" ]; then
  echo "启动成功：Lin_server 服务正在运行（新 PID:${NEW_PID}）"
  # 新进程已确认存活，再清理旧进程；逐个排除新 PID，防止误杀刚拉起的新实例。
  if [ -n "$OLD_ALL" ]; then
    echo "清理旧服务进程..."
    for p in $OLD_ALL; do
      case " $NEW_PID " in
        *" $p "*) ;;              # 防误杀：新 PID 不清理
        *) kill -9 "$p" 2>/dev/null ;;
      esac
    done
    sleep 1
    echo "旧服务已清理，切换完成"
  fi
else
  echo "启动失败：未检测到新服务进程，请查看 /data/local/tmp/Lin-Shizuku/Lin/log/Lin_server_launch.log"
  if [ -n "$OLD_ALL" ]; then
    echo "旧服务进程已保留，Lin-Shizuku 服务未受影响"
  fi
  exit 1
fi

# 激活服务后自动授予本应用 WRITE_SECURE_SETTINGS（关 USB/无线调试、开发者选项等系统开关依赖该权限）。
/system/bin/pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1
