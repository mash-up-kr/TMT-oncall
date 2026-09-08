# 배포와 VM 셋업 (TMT-342)

봇은 Oracle Cloud VM에 systemd로 상주한다. 감시 대상(TMT-BE)과 분리된 곳에 두는 이유는
**앱이 죽어도 봇은 살아서 알리기** 위해서다.

컨테이너에 넣지 않는다. 봇은 `claude`·`gh`·`git`·`./gradlew`를 직접 실행하고 전용 클론의
작업 트리를 고친다 — 그 도구들을 전부 이미지에 넣는 대신 호스트에서 그대로 쓴다.

## 1. VM 준비 (한 번)

```bash
# JDK 21 — PullRequestAgent가 전용 클론에서 ./gradlew build 를 돌린다
sudo apt update && sudo apt install -y openjdk-21-jdk git

# gh CLI — PR 생성
sudo apt install -y gh
echo "$GITHUB_TOKEN" | gh auth login --with-token

# 에이전트 CLI. 설치 경로를 확인해 둔다 — systemd는 로그인 셸의 PATH를 모른다
which claude    # 예: /usr/local/bin/claude

# 전용 클론. 운영 배포 소스와 섞이면 안 된다
git clone https://github.com/mash-up-kr/TMT-BE.git ~/tmt-oncall-workspace

# 봇을 돌릴 계정과 디렉터리
sudo useradd --system --create-home --shell /usr/sbin/nologin oncall
sudo mkdir -p /opt/tmt-oncall /etc/tmt-oncall
sudo chown oncall:oncall /opt/tmt-oncall
```

## 2. 환경변수 파일

값 목록과 각각의 의미는 [SPEC](SPEC.md)의 §환경변수에 있다. **기본값을 두지 않으므로
하나라도 비면 기동 단계에서 실패한다** — 빈 토큰으로 반쯤 뜬 채 도는 것이 더 나쁘다.

```bash
sudo install -m 600 -o root -g root /dev/null /etc/tmt-oncall/oncall.env
sudo vi /etc/tmt-oncall/oncall.env      # 로컬 .env 내용을 옮긴다
```

- 이 파일만 VM에 두고 권한을 `600`으로 조인다. **레포에는 커밋하지 않는다** (`.env`는 무시 목록)
- `ONCALL_AGENT_BINARY`는 1에서 확인한 **절대경로**로 적는다
- `TMT_WORKSPACE`는 전용 클론 경로 (`/home/oncall/tmt-oncall-workspace` 등 `oncall` 계정이 읽고 쓸 수 있는 곳)

## 3. systemd 등록

```bash
sudo cp systemd/tmt-oncall.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now tmt-oncall
```

## 4. 배포 — 로컬에서 빌드해 jar만 올린다

```bash
./gradlew bootJar
scp build/libs/tmt-oncall-*.jar <vm>:/tmp/tmt-oncall.jar
ssh <vm> '
  sudo mv /opt/tmt-oncall/tmt-oncall.jar /opt/tmt-oncall/tmt-oncall.jar.prev 2>/dev/null
  sudo mv /tmp/tmt-oncall.jar /opt/tmt-oncall/tmt-oncall.jar
  sudo chown oncall:oncall /opt/tmt-oncall/tmt-oncall.jar
  sudo systemctl restart tmt-oncall
'
```

VM에서 `git pull` 후 빌드하지 않는 이유는 둘이다. Gradle 빌드는 봇이 상주할 작은 VM에서
메모리를 많이 쓰고, 레포가 private이라 VM에 또 하나의 자격 증명(배포 키)을 두게 된다.
**직전 jar를 `.prev`로 남기므로 롤백은 되돌려 재시작하면 끝난다.**

## 5. 확인

```bash
systemctl status tmt-oncall
journalctl -u tmt-oncall -f
```

기동에 성공하면 **be-온콜 채널에 기동 알림 한 줄**이 올라온다. 그 줄에 킬 스위치 상태가
함께 찍히므로, 봇이 살아 있는데 아무것도 하지 않는 상태를 바로 구분할 수 있다.

- 재시작이 반복되면 채널에 기동 알림이 반복된다 — 로그를 먼저 본다
- Sentry 폴링이 3회 연속 실패하면 채널에 알린다. **조용히 멈추는 것이 최악이라**
  실패 사실 자체를 알리고, 돌아오면 복구도 알린다

## 6. 운영 중 손이 가는 것

| 하는 일 | 방법 |
|---|---|
| 봇 정지·재개 | 채널에서 `/oncall off` · `/oncall on` (systemd는 그대로 둔다) |
| 완전 정지 | `sudo systemctl stop tmt-oncall` |
| 억제 해제 | 채널에서 `/oncall unmute <이슈 ID>` |
| 상태 확인 | 채널에서 `/oncall status` |
| 상태 저장소 | `ONCALL_STORE_PATH`의 SQLite 파일. 지우면 처리 이력·억제 목록이 사라진다 |
