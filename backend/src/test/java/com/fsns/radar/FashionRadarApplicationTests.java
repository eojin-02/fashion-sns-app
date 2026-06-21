package com.fsns.radar;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 전체 컨텍스트 로드 스모크 테스트 — DB(PostGIS)/Redis가 필요하다.
 * "integration" 태그로 기본 test 실행에서 제외된다 (build.gradle 참고).
 * 인프라 기동 후 실행: ./gradlew integrationTest
 */
@Tag("integration")
@SpringBootTest
class FashionRadarApplicationTests {

	@Test
	void contextLoads() {
	}

}
