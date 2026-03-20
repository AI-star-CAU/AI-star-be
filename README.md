# AI-star-be
AI-star Backend: SW Engineering Project

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

### 2. 서버 실행

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

