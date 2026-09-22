package com.skthon.sixthsensebe.domain.favorite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.skthon.sixthsensebe.domain.education.service.EducationService;
import com.skthon.sixthsensebe.domain.favorite.repository.FavoriteRepository;
import com.skthon.sixthsensebe.domain.jobposting.entity.JobPosting;
import com.skthon.sixthsensebe.domain.jobposting.mapper.JobPostingMapper;
import com.skthon.sixthsensebe.domain.jobposting.repository.JobPostingRepository;
import com.skthon.sixthsensebe.domain.user.entity.Role;
import com.skthon.sixthsensebe.domain.user.entity.User;
import com.skthon.sixthsensebe.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Import(FavoriteService.class)
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FavoriteServiceConcurrencyTest {

  private static final int REQUEST_COUNT = 2;

  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36");

  @DynamicPropertySource
  static void configureDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
  }

  @Autowired
  private FavoriteService favoriteService;

  @MockitoSpyBean
  private FavoriteRepository favoriteRepository;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private JobPostingRepository jobPostingRepository;

  @PersistenceContext
  private EntityManager entityManager;

  @MockitoBean
  private JobPostingMapper jobPostingMapper;

  @MockitoBean
  private EducationService educationService;

  private ExecutorService executorService;

  @BeforeEach
  void setUp() {
    executorService = Executors.newFixedThreadPool(REQUEST_COUNT);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    executorService.shutdownNow();
    executorService.awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("동일한 사용자가 같은 채용공고를 동시에 찜하면 하나만 저장된다")
  void concurrentFavoriteRequestsSaveOnlyOneFavorite() throws Exception {
    User user = userRepository.saveAndFlush(User.builder()
        .name("동시성 테스트 사용자")
        .username("favorite-concurrency-user")
        .password("password")
        .role(Role.WORKER)
        .build());

    JobPosting jobPosting = jobPostingRepository.saveAndFlush(JobPosting.builder()
        .postName("동시성 테스트 채용공고")
        .companyName("테스트 회사")
        .build());

    CountDownLatch queryCompletedLatch = new CountDownLatch(REQUEST_COUNT);
    CountDownLatch startLatch = new CountDownLatch(1);

    doAnswer(invocation -> {
      Object result = entityManager.createQuery("""
              SELECT f
              FROM Favorite f
              WHERE f.user.id = :userId
                AND f.jobPosting.id = :jobPostingId
              """)
          .setParameter("userId", user.getId())
          .setParameter("jobPostingId", jobPosting.getId())
          .getResultStream()
          .findFirst();
      queryCompletedLatch.countDown();

      if (!queryCompletedLatch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("두 요청이 제한 시간 안에 찜 조회를 완료하지 못했습니다.");
      }

      return result;
    }).when(favoriteRepository)
        .findByUserIdAndJobPostingId(user.getId(), jobPosting.getId());

    AtomicInteger successCount = new AtomicInteger();
    AtomicInteger duplicateFailureCount = new AtomicInteger();
    List<Future<?>> futures = new ArrayList<>();

    for (int i = 0; i < REQUEST_COUNT; i++) {
      futures.add(executorService.submit(() -> {
        startLatch.await();

        try {
          favoriteService.toggleJobPostingFavorite(user.getId(), jobPosting.getId());
          successCount.incrementAndGet();
        } catch (DataIntegrityViolationException exception) {
          duplicateFailureCount.incrementAndGet();
        }

        return null;
      }));
    }

    startLatch.countDown();

    for (Future<?> future : futures) {
      future.get(10, TimeUnit.SECONDS);
    }

    assertThat(successCount.get()).isEqualTo(1);
    assertThat(duplicateFailureCount.get()).isEqualTo(1);
    assertThat(favoriteRepository.count()).isEqualTo(1);
  }
}
