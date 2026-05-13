# AI-star-be
AI-star Backend: SW Engineering Project

## 배포 URL
- https://api-aistar.kro.kr/

## 실행 환경
- **Language:** Java 21
- **Build Tool:** Gradle
- **Framework:** Spring Boot 4.0.4

## 실행 방법

### 1. repository clone
```bash
git clone https://github.com/AI-star-CAU/AI-star-be.git
cd AI-star-be
```

### 2. 설정 파일 준비

`src/main/resources/application.yml`은 DB 비밀번호, JWT secret 등 민감한 값을 포함할 수 있으므로 git에 커밋하지 않습니다.
대신 저장소에는 예시 파일인 `src/main/resources/application.example.yml`만 포함되어 있습니다.

로컬에서 실행할 때는 예시 파일을 복사한 뒤 실제 값을 채워 넣습니다.

```bash
cp src/main/resources/application.example.yml src/main/resources/application.yml
```

필수 설정:

| 설정 | 설명 |
| --- | --- |
| `SPRING_DATASOURCE_URL` | DB JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | DB 사용자명 |
| `SPRING_DATASOURCE_PASSWORD` | DB 비밀번호 |
| `JWT_SECRET` | JWT 서명용 secret. HS256 기준 32바이트 이상 권장 |
| `JWT_EXPIRATION` | JWT 만료 시간(ms). 기본값 `3600000` |

환경변수로도 실행할 수 있습니다.

```bash
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/aistar' \
SPRING_DATASOURCE_USERNAME='root' \
SPRING_DATASOURCE_PASSWORD='password' \
JWT_SECRET='your-32-bytes-or-longer-secret-key' \
./gradlew bootRun
```

빌드된 jar를 실행할 때도 같은 설정이 필요합니다.

```bash
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/aistar' \
SPRING_DATASOURCE_USERNAME='root' \
SPRING_DATASOURCE_PASSWORD='password' \
JWT_SECRET='your-32-bytes-or-longer-secret-key' \
java -jar build/libs/ai-star-server.jar
```

테스트는 `src/test/resources/application.yml`의 인메모리 H2 DB와 테스트용 더미 JWT secret을 사용합니다.
이 파일에는 실제 비밀값을 넣지 않습니다.

### 3. 서버 실행

#### Mac / Linux 
```bash
./gradlew clean build
java -jar build/libs/ai-star-server.jar
```

#### Windows
```bash
gradlew clean build
java -jar build/libs/ai-star-server.jar
```


#### IntelliJ를 이용한 실행을 권장

## 실행 확인

- http://localhost:8080
- http://localhost:8080/health
- http://localhost:8080/hello

- 3가지 기본 테스트용 엔드포인트 (method: GET)
