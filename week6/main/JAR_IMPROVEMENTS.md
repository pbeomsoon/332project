# JAR 빌드 개선 사항

## 변경 내용

### 1. build.sbt 개선

#### 의존성 정리
- ❌ 제거: `grpc-okhttp` (사용하지 않음, 충돌 방지)
- ✅ 유지: `grpc-netty` (기본 transport)

#### Assembly Merge Strategy 개선

**이전 (문제):**
```scala
case PathList("META-INF", xs @ _*) => MergeStrategy.last  // 서비스 정의 손실
case x => MergeStrategy.first  // 너무 광범위
```

**개선 (해결):**
```scala
// Netty 네이티브 라이브러리 보존
case PathList("META-INF", "native", xs @ _*) => MergeStrategy.first

// gRPC 서비스 정의 병합 (중요!)
case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat

// 서명 파일 제거 (충돌 방지)
case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".SF")) => MergeStrategy.discard

// module-info.class 제거 (Java 8 호환성)
case x if x.contains("module-info.class") => MergeStrategy.discard
```

### 2. Worker 자동 로컬 감지

**기능:**
- Master 주소가 로컬 인터페이스인지 자동 감지
- 로컬이면 `127.0.0.1`로 자동 변환
- 원격이면 원래 주소 사용

**코드:**
```scala
val isLocal = NetworkInterface.getNetworkInterfaces
  .flatMap(_.getInetAddresses)
  .exists(addr => addr.getHostAddress == masterAddr.getHostAddress)

if (isLocal) "127.0.0.1" else masterHost
```

## 테스트 방법

### 로컬에서 테스트 (WSL)

```bash
cd /mnt/c/Users/jihoo/Desktop/학부공부파일모음/25-2/SD/project_2025/main

# JAR 빌드 및 테스트
chmod +x test_jar.sh
./test_jar.sh
```

### 클러스터에 배포

```bash
# 변경된 파일만 동기화
rsync -avz --update \
  --exclude='target/' \
  --exclude='.git/' \
  main/ silver@141.223.16.227:~/main/ \
  -e "ssh -p 7777"

# 클러스터에서 빌드
ssh -p 7777 silver@141.223.16.227
cd ~/main
sbt assembly
```

### 실행 테스트

#### 방법 1: 같은 머신에서 (로컬 테스트)
```bash
# Terminal 1
master 3
# 출력 예: 10.1.25.21:40709

# Terminal 2-4 (자동으로 127.0.0.1 변환됨!)
worker 10.1.25.21:40709 -I input1 -O output
worker 10.1.25.21:40709 -I input1 -O output
worker 10.1.25.21:40709 -I input1 -O output
```

#### 방법 2: 다른 VM에서 (실제 분산)
```bash
# vm-1-master
master 3
# 출력: 2.2.2.254:33900

# vm01 (2.2.2.101)
worker 2.2.2.254:33900 -I input1 -O output

# vm02 (2.2.2.102)
worker 2.2.2.254:33900 -I input1 -O output

# vm03 (2.2.2.103)
worker 2.2.2.254:33900 -I input1 -O output
```

## 예상 출력

### Master 시작
```
master 3
10.1.25.21:40709
[로그...]
```

### Worker 연결 (로컬)
```
worker 10.1.25.21:40709 -I input1 -O output
Starting Worker with JAR (fast)...
[INFO] Master 10.1.25.21 is on same machine, using 127.0.0.1
[INFO] Creating gRPC channel to 127.0.0.1:40709
[INFO] Worker registered successfully
```

### 완료 시 (Master 출력)
```
10.1.25.21:40709
localhost, localhost, localhost
```

## 문제 해결

### JAR 실행 실패 시
```bash
# 1. Clean build
sbt clean
sbt assembly

# 2. JAR 내용 확인
jar tf target/scala-2.13/distsort.jar | grep -E "(Master|Worker|grpc|netty)" | head -20

# 3. 직접 실행으로 에러 확인
java -Djava.net.preferIPv4Stack=true \
  -cp target/scala-2.13/distsort.jar \
  distsort.master.Master 3
```

### 연결 실패 시
```bash
# 1. 포트 확인
ss -tlnp | grep <포트번호>

# 2. 주소 확인
ip addr show

# 3. 수동으로 127.0.0.1 시도
worker 127.0.0.1:<포트> -I input1 -O output
```

## 핵심 개선 사항

✅ JAR 빌드 최적화 (충돌 방지)
✅ 자동 로컬 주소 감지
✅ sbt와 JAR 동작 일관성
✅ Java 8 호환성 보장
✅ Netty 네이티브 라이브러리 보존
✅ gRPC 서비스 정의 병합
