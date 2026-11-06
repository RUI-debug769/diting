#!/usr/bin/env bash
# 把 cloud/ 部署到博客那台 ECS，并重启谛听服务。
#
#   HOST=<你的服务器> ./deploy.sh       推到 /opt/diting
#   HOST=x.x.x.x ./deploy.sh
#
# 只同步代码与模型；`.venv` 和 `data/` 留在服务器上，避免每次覆盖声纹。
set -euo pipefail

HOST="${HOST:-}"
USER_="${USER_:-root}"
KEY="${KEY:-$HOME/.ssh/id_ed25519}"
DEST="${DEST:-/opt/diting}"
HERE="$(cd "$(dirname "$0")" && pwd)"

if [ -z "$HOST" ]; then
  echo "用法：HOST=<你的服务器> USER_=<用户名> KEY=<私钥路径> ./deploy.sh" >&2
  exit 2
fi

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

say "1/4 建目录"
ssh -i "$KEY" "$USER_@$HOST" "mkdir -p $DEST/models $DEST/data $DEST/tools"

say "2/4 同步代码"
rsync -az --delete \
  --exclude '.venv' --exclude 'data' --exclude '__pycache__' --exclude 'models/*.onnx' \
  -e "ssh -i $KEY" "$HERE/" "$USER_@$HOST:$DEST/"

say "3/4 同步模型（第一次慢，之后按需）"
for f in silero_vad.onnx 3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx model.int8.onnx tokens.txt; do
  if [ -f "$HERE/models/$f" ]; then
    if ssh -i "$KEY" "$USER_@$HOST" "test -f $DEST/models/$f && [ \$(stat -c%s $DEST/models/$f) -eq $(stat -c%s "$HERE/models/$f") ]"; then
      echo "  跳过 $f（大小一致）"
    else
      echo "  传 $f ($(du -h "$HERE/models/$f" | cut -f1))"
      scp -q -i "$KEY" "$HERE/models/$f" "$USER_@$HOST:$DEST/models/$f"
    fi
  else
    echo "  ⚠ 本地缺 $f"
  fi
done

say "4/4 依赖与服务"
ssh -i "$KEY" "$USER_@$HOST" bash -s <<EOF
set -e
cd $DEST
# 这台 ECS 没装 python3-venv，直接用系统 python3 + pip3；依赖进 dist-packages。
needs_deps() {
  python3 -c 'import importlib.util as u,sys; sys.exit(0 if all(u.find_spec(m) for m in ("sherpa_onnx","numpy")) else 1)'
}
if ! needs_deps; then
  echo "  装依赖（sherpa-onnx / numpy）"
  pip3 install --no-cache-dir -q -r requirements.txt
fi
install -m 644 deploy/diting.service /etc/systemd/system/diting.service
systemctl daemon-reload
systemctl enable diting
systemctl restart diting
sleep 2
systemctl --no-pager --lines=12 status diting | head -20
EOF

say "完成。端口：板子 ${BOARD_PORT:-19001}，App ${APP_PORT:-19002}"
