package com.skthon.sixthsensebe.domain.user.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skthon.sixthsensebe.domain.user.entity.User;
import com.skthon.sixthsensebe.domain.user.mapper.UserMapper;
import com.skthon.sixthsensebe.domain.user.repository.UserRepository;
import com.skthon.sixthsensebe.global.s3.PathName;
import com.skthon.sixthsensebe.global.s3.service.S3Service;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class UserServiceProfileImageTest {

  private static final String OLD_URL = "https://example.com/profile/old.png";
  private static final String NEW_URL = "https://example.com/profile/new.png";

  private final UserRepository userRepository = mock(UserRepository.class);
  private final S3Service s3Service = mock(S3Service.class);
  private final UserService userService = new UserService(
      userRepository, mock(UserMapper.class), mock(PasswordEncoder.class), s3Service);
  private final MockMultipartFile file = new MockMultipartFile(
      "file", "new.png", "image/png", new byte[] {1});

  @BeforeEach
  void setUp() {
    TransactionSynchronizationManager.initSynchronization();
  }

  @AfterEach
  void tearDown() {
    TransactionSynchronizationManager.clearSynchronization();
  }

  @Test
  void deletesOldImageOnlyAfterCommit() {
    User user = userWithImage();
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(s3Service.uploadFile(PathName.PROFILE, file)).thenReturn(NEW_URL);

    userService.uploadProfileImage(1L, file);

    verify(s3Service, never()).deleteFile("profile/old.png");
    verify(s3Service, never()).deleteFile("profile/new.png");

    TransactionSynchronizationUtils.triggerAfterCommit();
    TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

    verify(s3Service).deleteFile("profile/old.png");
    verify(s3Service, never()).deleteFile("profile/new.png");
  }

  @Test
  void deletesNewImageAndKeepsOldImageAfterRollback() {
    User user = userWithImage();
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(s3Service.uploadFile(PathName.PROFILE, file)).thenReturn(NEW_URL);

    userService.uploadProfileImage(1L, file);
    TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

    verify(s3Service).deleteFile("profile/new.png");
    verify(s3Service, never()).deleteFile("profile/old.png");
  }

  @Test
  void keepsOldImageWhenUploadFails() {
    User user = userWithImage();
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(s3Service.uploadFile(PathName.PROFILE, file))
        .thenThrow(new IllegalStateException("upload failed"));

    assertThrows(IllegalStateException.class, () -> userService.uploadProfileImage(1L, file));

    verify(s3Service, never()).deleteFile("profile/old.png");
    verify(userRepository, never()).save(user);
  }

  @Test
  void deletesProfileImageOnlyAfterCommit() {
    User user = userWithImage();
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    userService.deleteProfileImage(1L);

    verify(s3Service, never()).deleteFile("profile/old.png");
    TransactionSynchronizationUtils.triggerAfterCommit();
    TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

    verify(s3Service).deleteFile("profile/old.png");
  }

  private User userWithImage() {
    return User.builder().id(1L).s3url(OLD_URL).build();
  }
}
