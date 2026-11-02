package com.portfolio.wallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * What {@code scripts/generate-dev-secrets.sh} prints must be what {@link RequiredSecrets} accepts:
 * the script once emitted PKCS#1 while the application requires PKCS#8. Skipped where Bash or
 * openssl is missing. The generated values stay in this process: no assertion below includes one,
 * and the script's stderr is discarded.
 */
class GenerateDevSecretsScriptTest {

  private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath().getParent();
  private static final String SCRIPT = "scripts/generate-dev-secrets.sh";
  private static final long TIMEOUT_SECONDS = 120;
  private static final int LINE_COUNT = 4;

  private record Output(int exitCode, String stdout) {}

  /**
   * On Windows, starting plain {@code bash} ignores PATH order and finds {@code System32\bash.exe}
   * first, the WSL launcher, so look for a real Bash such as Git Bash on PATH.
   */
  private static Optional<String> bashExecutable() {
    if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
      return Optional.of("bash");
    }
    for (String directory : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
      String lower = directory.toLowerCase(Locale.ROOT);
      Path candidate = Path.of(directory, "bash.exe");
      if (!lower.contains("\\system32") && !lower.contains("\\windowsapps")) {
        if (Files.isRegularFile(candidate)) {
          return Optional.of(candidate.toString());
        }
      }
    }
    return Optional.empty();
  }

  private static Output run(String... command) throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder(command)
            .directory(REPOSITORY_ROOT.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    process.getOutputStream().close();
    CompletableFuture<byte[]> stdout =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return process.getInputStream().readAllBytes();
              } catch (IOException e) {
                return new byte[0];
              }
            });
    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      return new Output(-1, "");
    }
    return new Output(process.exitValue(), new String(stdout.join(), StandardCharsets.UTF_8));
  }

  /** The Bash to use; the test is skipped when it cannot run the script's prerequisites. */
  private static String requireBashAndOpenssl() throws InterruptedException {
    Optional<String> bash = bashExecutable();
    assumeTrue(bash.isPresent(), "no usable bash on PATH");
    boolean ready;
    try {
      ready = run(bash.get(), "-c", "command -v openssl").exitCode() == 0;
    } catch (IOException e) {
      ready = false;
    }
    assumeTrue(ready, "bash or openssl is not available");
    return bash.get();
  }

  @Test
  void itsOutputIsAcceptedByRequiredSecretsAndTheKeySigns()
      throws IOException, InterruptedException, GeneralSecurityException {
    String bash = requireBashAndOpenssl();
    assertThat(REPOSITORY_ROOT.resolve(SCRIPT)).isRegularFile();

    Output output = run(bash, SCRIPT);

    assertThat(output.exitCode()).as("script exit code").isZero();
    // Split on \n only: a stray carriage return is part of the value, and must fail the parser.
    List<String> names = new ArrayList<>();
    Map<String, String> values = new HashMap<>();
    for (String line : output.stdout().split("\n")) {
      int separator = line.indexOf('=');
      assertThat(separator).as("every line is KEY=value").isPositive();
      names.add(line.substring(0, separator));
      values.put(line.substring(0, separator), line.substring(separator + 1));
    }
    assertThat(names)
        .hasSize(LINE_COUNT)
        .containsExactlyInAnyOrder(
            RequiredSecrets.JWT_PRIVATE_KEY,
            RequiredSecrets.JWT_KID,
            RequiredSecrets.OTP_HMAC_SECRET,
            RequiredSecrets.REFRESH_TOKEN_PEPPER);

    // RequiredSecrets' failure messages name the variable and never contain its value.
    assertThatCode(() -> RequiredSecrets.from(values::get)).doesNotThrowAnyException();
    RequiredSecrets secrets = RequiredSecrets.from(values::get);

    byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(secrets.jwtPrivateKey());
    signer.update(data);
    byte[] signature = signer.sign();
    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(secrets.jwtPublicKey());
    verifier.update(data);
    assertThat(verifier.verify(signature)).as("signature made with the printed key").isTrue();
  }
}
