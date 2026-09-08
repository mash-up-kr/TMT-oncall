# 배포와 VM 셋업 (TMT-342)

봇은 EC2 인스턴스(Amazon Linux 2023)에 systemd로 상주한다. **감시 대상(TMT-BE)과 다른
인스턴스여야 한다** — 앱이 죽어도 봇은 살아서 알린다는 전제가 여기서 나온다. 같은 인스턴스에
올리면 그 인스턴스가 죽는 순간 알릴 사람도 없다.

컨테이너에 넣지 않는다. 봇은 `claude`·`gh`·`git`·`./gradlew`를 직접 실행하고 전용 클론의
작업 트리를 고친다 — 그 도구들을 전부 이미지에 넣는 대신 호스트에서 그대로 쓴다.

## 1. VM 준비 (한 번)

Amazon Linux 2023은 `apt`가 아니라 `dnf`를 쓴다. 기본 로그인 계정은 `ec2-user`다.

```bash
# JDK 21 — PullRequestAgent가 전용 클론에서 ./gradlew build 를 돌린다.
# Amazon Linux의 JDK는 Corretto다
sudo dnf install -y java-21-amazon-corretto-devel git

# gh CLI — 기본 저장소에 없어 GitHub 공식 repo를 먼저 붙인다
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --add-repo https://cli.github.com/packages/rpm/gh-cli.repo
sudo dnf install -y gh
echo "$GITHUB_TOKEN" | gh auth login --with-token

# 에이전트 CLI. 설치 경로를 확인해 둔다 — systemd는 로그인 셸의 PATH를 모른다
which claude    # 예: /usr/local/bin/claude

# 봇을 돌릴 계정과 디렉터리
sudo useradd --system --create-home --shell /sbin/nologin oncall
sudo mkdir -p /opt/tmt-oncall /etc/tmt-oncall
sudo chown oncall:oncall /opt/tmt-oncall

# 전용 클론. 운영 배포 소스와 섞이면 안 되고, 봇 계정이 읽고 쓸 수 있어야 한다
sudo -u oncall git clone https://github.com/mash-up-kr/TMT-BE.git /home/oncall/tmt-oncall-workspace
```

`java -version`이 21을 찍는지 확인한다. 없다고 나오면 `dnf search corretto | grep 21`로 찾는다.

`gh` 저장소가 붙지 않으면 릴리즈 RPM을 직접 깐다 — `uname -m`이 `aarch64`(Graviton)인지
`x86_64`인지에 맞춰 받는다.

## 2. 환경변수 파일

값 목록과 각각의 의미는 [SPEC](SPEC.md)의 §환경변수에 있다. **기본값을 두지 않으므로
하나라도 비면 기동 단계에서 실패한다** — 빈 토큰으로 반쯤 뜬 채 도는 것이 더 나쁘다.

```bash
sudo install -m 600 -o root -g root /dev/null /etc/tmt-oncall/oncall.env
sudo vi /etc/tmt-oncall/oncall.env      # 로컬 .env 내용을 옮긴다
```

- 이 파일만 VM에 두고 권한을 `600`으로 조인다. **레포에는 커밋하지 않는다** (`.env`는 무시 목록)
- `ONCALL_AGENT_BINARY`는 1에서 확인한 **절대경로**로 적는다
- `TMT_WORKSPACE`는 위에서 만든 전용 클론 경로(`/home/oncall/tmt-oncall-workspace`). `oncall` 계정이
  읽고 쓸 수 있어야 한다 — 수정 에이전트가 그 작업 트리를 고친다
- `gh auth login`은 봇을 돌릴 `oncall` 계정으로도 해 둔다 (`sudo -u oncall gh auth login --with-token`).
  자격 증명은 계정별로 저장돼서, 로그인한 계정과 실행하는 계정이 다르면 PR 생성에서만 뒤늦게 막힌다

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

인바운드 포트를 열지 않으므로 방화벽은 손대지 않는다 — 봇은 나가는 호출만 한다.
Amazon Linux 2023의 SELinux는 기본이 permissive라 기동을 막지 않는다. `enforcing`으로 바꿔 뒀다면
거부 로그를 `sudo ausearch -m avc -ts recent`로 본다.

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
