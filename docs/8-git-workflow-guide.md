# Git Workflow Guide for CSED332 Project

**작성일**: 2025-10-24
**목적**: TA 공지사항을 준수하는 안전한 Git 사용 가이드

---

## ⚠️ TA 공지사항 요약

### **중요 규칙 (3번째 과제부터 점수 반영!)**

1. **바이너리/중간 파일 커밋 금지**
   - ❌ `.class` 파일
   - ❌ `.jar` 파일
   - ❌ `target/` 디렉토리
   - ❌ IDE 설정 파일

2. **프로젝트 구조 변경 금지**
   - ✅ Clone 후 바로 빌드/실행 가능해야 함
   - ✅ 주어진 구조 유지

3. **커밋 전 필수 작업**
   - ✅ `sbt clean` 실행
   - ✅ Staged changes 신중하게 검토
   - ❌ `git add -A` 무분별 사용 금지

---

## 1. 안전한 Git Workflow

### 1.1 매일 작업 시작 전

```bash
# 1. 최신 코드 받기
git pull origin main

# 2. 현재 상태 확인
git status

# 3. 브랜치 확인 (선택사항)
git branch
```

### 1.2 코드 작성 중

```bash
# 작업 중...
vim src/main/scala/distsort/core/Record.scala
vim src/test/scala/distsort/core/RecordSpec.scala

# 중간 저장 (아직 커밋 X)
# - 로컬에서만 작업
# - 자주 빌드하여 확인
sbt test
```

### 1.3 커밋 준비 (⭐ 가장 중요!)

```bash
# ========================================
# Step 1: 컴파일 산물 정리 (필수!)
# ========================================
sbt clean
# [success] Total time: 1 s

# ========================================
# Step 2: 현재 상태 확인
# ========================================
git status

# 예시 출력:
# On branch main
# Untracked files:
#   src/main/scala/distsort/core/Record.scala
#   src/test/scala/distsort/core/RecordSpec.scala
#   target/                              ← ❌ 이건 커밋하면 안됨!
#   .idea/                               ← ❌ 이것도 안됨!
#   project/target/                      ← ❌ 이것도 안됨!

# ========================================
# Step 3: 개별 파일 추가 (신중하게!)
# ========================================
# ✅ GOOD: 소스 파일만 선택적으로 추가
git add src/main/scala/distsort/core/Record.scala
git add src/test/scala/distsort/core/RecordSpec.scala

# ❌ BAD: 무분별하게 전부 추가
# git add -A  ← 절대 하지 마세요!
# git add .   ← 이것도 위험!

# ========================================
# Step 4: Staged changes 검토 (필수!)
# ========================================
git diff --staged

# 또는 더 자세하게
git diff --staged --name-status

# 예시 출력:
# A       src/main/scala/distsort/core/Record.scala
# A       src/test/scala/distsort/core/RecordSpec.scala

# ========================================
# Step 5: 최종 확인 (중요!)
# ========================================
git status

# 예시 출력:
# Changes to be committed:
#   new file:   src/main/scala/distsort/core/Record.scala
#   new file:   src/test/scala/distsort/core/RecordSpec.scala
#
# Untracked files:
#   target/        ← .gitignore로 무시됨
#   .idea/         ← .gitignore로 무시됨

# ========================================
# Step 6: 커밋 (메시지는 명확하게!)
# ========================================
git commit -m "Add Record class with unit tests

- Implement Record class with 10-byte key + 90-byte value
- Add RecordSpec with 7 test cases
- All tests passing (sbt test)
"

# ========================================
# Step 7: Push (원격 저장소에 업로드)
# ========================================
git push origin main
```

---

## 2. .gitignore 검증

### 2.1 .gitignore가 제대로 작동하는지 확인

```bash
# 1. Clean 상태에서 빌드
sbt clean
sbt compile
sbt test

# 2. Git 상태 확인
git status

# 기대 결과: target/, .idea/ 등이 "Untracked files"에 나타나지 않아야 함
# On branch main
# nothing to commit, working tree clean  ← 이상적!
```

### 2.2 .gitignore 테스트

```bash
# 임의로 바이너리 파일 생성
echo "test" > test.class
echo "test" > test.jar

# Git 상태 확인
git status
# test.class, test.jar가 무시되어야 함 (Untracked에 안 나타남)

# 정리
rm test.class test.jar
```

---

## 3. 체크리스트 (커밋 전 필수!)

### 📝 커밋 전 체크리스트

```
커밋하기 전에 다음을 확인하세요:

[ ] sbt clean 실행했는가?
[ ] git status로 현재 상태 확인했는가?
[ ] git add로 개별 파일만 추가했는가? (git add -A 사용 안 했나?)
[ ] git diff --staged로 변경 내용 검토했는가?
[ ] .class, .jar, target/ 같은 파일이 포함되지 않았는가?
[ ] IDE 설정 파일(.idea/, *.iml)이 포함되지 않았는가?
[ ] 테스트가 모두 통과하는가? (sbt test)
[ ] 커밋 메시지가 명확한가?

모두 체크했다면 커밋하세요!
git commit -m "Clear message"
git push origin main
```

---

## 4. 일반적인 실수와 해결법

### 4.1 실수: .class 파일을 커밋했을 때

```bash
# 문제 발견
git status
# On branch main
# Changes to be committed:
#   new file:   target/scala-2.13/classes/distsort/core/Record.class  ← 이거 안됨!

# 해결: Unstage
git reset HEAD target/

# 다시 확인
git status
# 이제 target/이 Untracked files로 이동됨
```

### 4.2 실수: 이미 커밋한 경우 (아직 push 안 함)

```bash
# 마지막 커밋 취소
git reset --soft HEAD~1

# 파일은 그대로 유지되지만 커밋은 취소됨
# 다시 올바르게 git add & commit
```

### 4.3 실수: 이미 push한 경우 (⚠️ 위험)

```bash
# ⚠️ 주의: 이미 push한 커밋을 되돌리는 것은 위험합니다
# 팀 프로젝트라면 절대 하지 마세요!

# 방법 1: Revert (안전, 권장)
git revert HEAD
git push origin main

# 방법 2: Force push (위험, 비권장)
# git reset --hard HEAD~1
# git push -f origin main  ← 절대 하지 마세요!
```

---

## 5. 권장 Git Workflow (TDD 기반)

### 5.1 Feature 개발 시

```bash
# ========================================
# 1. 테스트 작성 (RED)
# ========================================
vim src/test/scala/distsort/core/RecordSpec.scala
sbt test  # 실패 확인

# 커밋 (테스트만)
sbt clean
git add src/test/scala/distsort/core/RecordSpec.scala
git commit -m "Add failing test for Record class (RED)"

# ========================================
# 2. 구현 (GREEN)
# ========================================
vim src/main/scala/distsort/core/Record.scala
sbt test  # 통과 확인

# 커밋 (구현)
sbt clean
git add src/main/scala/distsort/core/Record.scala
git commit -m "Implement Record class (GREEN)"

# ========================================
# 3. 리팩토링 (REFACTOR)
# ========================================
vim src/main/scala/distsort/core/Record.scala
sbt test  # 여전히 통과

# 커밋 (리팩토링)
sbt clean
git add src/main/scala/distsort/core/Record.scala
git commit -m "Refactor Record class (REFACTOR)"

# ========================================
# 4. Push
# ========================================
git push origin main
```

### 5.2 하루 작업 끝날 때

```bash
# 1. 최종 빌드 확인
sbt clean
sbt compile
sbt test

# 2. 커밋되지 않은 변경사항 확인
git status

# 3. 필요하면 커밋
git add <files>
git commit -m "End of day: <what you did>"
git push origin main

# 4. 내일을 위한 TODO (선택)
echo "TODO: Implement RecordReader" > TODO.md
git add TODO.md
git commit -m "Add TODO for tomorrow"
git push origin main
```

---

## 6. 유용한 Git 명령어

### 6.1 상태 확인

```bash
# 현재 상태
git status

# 최근 커밋 로그
git log --oneline -5

# 커밋되지 않은 변경사항
git diff

# Staged changes
git diff --staged

# 특정 파일의 변경 이력
git log --follow src/main/scala/distsort/core/Record.scala
```

### 6.2 파일 관리

```bash
# 파일 추가
git add <file>

# 파일 Unstage
git reset HEAD <file>

# 파일 삭제
git rm <file>

# 파일 이름 변경
git mv <old> <new>
```

### 6.3 변경 취소

```bash
# 작업 디렉토리의 변경 취소 (주의!)
git checkout -- <file>

# Staged changes Unstage
git reset HEAD <file>

# 마지막 커밋 취소 (로컬만)
git reset --soft HEAD~1

# 마지막 커밋 완전히 삭제 (주의!)
git reset --hard HEAD~1
```

---

## 7. 금지 사항

### ❌ 절대 하지 말 것

```bash
# 1. 무분별한 add
git add -A  # ← 금지!
git add .   # ← 위험!

# 2. Clean 없이 커밋
# sbt clean을 건너뛰고 바로 git add  ← 금지!

# 3. 바이너리 파일 강제 추가
git add -f target/  # ← 절대 금지!

# 4. .gitignore 무시
git add -f *.class  # ← 절대 금지!

# 5. Force push (협업 시)
git push -f origin main  # ← 매우 위험!

# 6. 큰 파일 커밋
git add large-file.dat  # ← 1GB 테스트 파일 등 금지!
```

---

## 8. 프로젝트 구조 보존

### 8.1 Clone 후 바로 빌드 가능해야 함

```bash
# 다른 컴퓨터에서 clone
git clone https://github.com/your-repo/distsort.git
cd distsort

# 즉시 빌드 가능
sbt compile  # ← 성공해야 함!
sbt test     # ← 성공해야 함!
```

### 8.2 필수 파일들

프로젝트 루트에 반드시 있어야 하는 파일:
```
distsort/
├── build.sbt                    ← 필수!
├── project/
│   ├── build.properties         ← 필수!
│   └── plugins.sbt              ← 필수!
├── src/
│   ├── main/
│   │   ├── scala/               ← 필수!
│   │   └── protobuf/            ← 필수 (gRPC 사용 시)
│   └── test/
│       └── scala/               ← 필수!
├── .gitignore                   ← 필수!
└── README.md                    ← 권장
```

---

## 9. 트러블슈팅

### 9.1 "이미 .class 파일을 커밋했어요!"

```bash
# Git이 추적하는 파일 제거 (로컬 파일은 유지)
git rm --cached -r target/
git rm --cached *.class

# .gitignore 추가/확인
vim .gitignore

# 커밋
git commit -m "Remove compiled files from git tracking"
git push origin main
```

### 9.2 "merge conflict가 났어요!"

```bash
# 1. 현재 변경사항 stash
git stash

# 2. Pull
git pull origin main

# 3. Stash 복원
git stash pop

# 4. Conflict 해결 후 커밋
vim <conflicted-file>
git add <conflicted-file>
git commit -m "Resolve merge conflict"
```

### 9.3 ".gitignore가 작동하지 않아요!"

```bash
# 이미 추적 중인 파일은 .gitignore가 무시함
# 해결: Git 추적에서 제거
git rm -r --cached .
git add .
git commit -m "Fix .gitignore"
```

---

## 10. 요약

### ✅ 올바른 Workflow

```bash
# 1. Clean
sbt clean

# 2. Status 확인
git status

# 3. 개별 파일 추가
git add src/main/scala/distsort/core/Record.scala
git add src/test/scala/distsort/core/RecordSpec.scala

# 4. Staged 확인
git diff --staged

# 5. 커밋
git commit -m "Add Record class with tests"

# 6. Push
git push origin main
```

### ❌ 잘못된 Workflow

```bash
# 1. Clean 없이 바로 add
git add -A  # ← target/, .idea/ 모두 포함됨!

# 2. 확인 없이 커밋
git commit -m "update"  # ← 뭐가 들어갔는지 모름!

# 3. Push
git push origin main  # ← 바이너리 파일이 올라감! (감점!)
```

---

## 11. 참고 자료

- **Git 공식 문서**: https://git-scm.com/doc
- **Pro Git Book**: https://git-scm.com/book/en/v2
- **.gitignore 템플릿**: https://github.com/github/gitignore

---

## 12. TA 연락처

문제가 생기면 TA에게 문의:
- **TA**: Seonggon Namgung
- **이메일**: (과제 공지 참조)

**기억하세요**: 3번째 과제부터는 Git 관리 실수가 점수에 반영됩니다!

---

**문서 버전**: v1.0
**최종 수정**: 2025-10-24
